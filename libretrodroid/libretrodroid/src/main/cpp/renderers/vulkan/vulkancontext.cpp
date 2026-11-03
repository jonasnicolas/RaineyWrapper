/*
 *     RaineyWrapper addition to LibretroDroid. GPL-3.0-or-later, like the rest of LibretroDroid.
 */

#include "vulkancontext.h"

#include <algorithm>
#include <cstring>

#include "../../log.h"

namespace libretrodroid {

static constexpr uint32_t SYNC_INDEX_COUNT = 2;

VulkanContext& VulkanContext::getInstance() {
    static VulkanContext instance;
    return instance;
}

bool VulkanContext::initialize(const retro_hw_render_context_negotiation_interface_vulkan* negotiation) {
    if (isInitialized()) return true;
    if (!createInstance(negotiation) || !createDevice(negotiation)) {
        LOGE("Vulkan: failed to create the instance or device");
        return false;
    }

    getAhbProperties = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
        vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    vkGetPhysicalDeviceMemoryProperties(gpu, &memoryProperties);

    VkCommandPoolCreateInfo poolInfo { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool);

    VkCommandBufferAllocateInfo allocInfo { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    allocInfo.commandPool = commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    vkAllocateCommandBuffers(device, &allocInfo, &commandBuffer);

    VkFenceCreateInfo fenceInfo { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    vkCreateFence(device, &fenceInfo, nullptr, &fence);

    renderInterface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    renderInterface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    renderInterface.handle = this;
    renderInterface.instance = instance;
    renderInterface.gpu = gpu;
    renderInterface.device = device;
    renderInterface.get_device_proc_addr = vkGetDeviceProcAddr;
    renderInterface.get_instance_proc_addr = vkGetInstanceProcAddr;
    renderInterface.queue = queue;
    renderInterface.queue_index = queueFamily;
    renderInterface.set_image = &setImage;
    renderInterface.get_sync_index = &getSyncIndex;
    renderInterface.get_sync_index_mask = &getSyncIndexMask;
    renderInterface.set_command_buffers = &setCommandBuffers;
    renderInterface.wait_sync_index = &waitSyncIndex;
    renderInterface.lock_queue = &lockQueue;
    renderInterface.unlock_queue = &unlockQueue;
    renderInterface.set_signal_semaphore = &setSignalSemaphore;

    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(gpu, &properties);
    LOGI("Vulkan: using %s (API %u.%u), AHB import %s, foreign queue %s",
         properties.deviceName, VK_VERSION_MAJOR(properties.apiVersion), VK_VERSION_MINOR(properties.apiVersion),
         getAhbProperties ? "yes" : "no", foreignQueueFamily ? "yes" : "no");
    return getAhbProperties != nullptr;
}

// --- instance / device ---------------------------------------------------------------------------

static VkApplicationInfo defaultApplicationInfo() {
    VkApplicationInfo app { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    app.pApplicationName = "RaineyWrapper";
    app.pEngineName = "LibretroDroid";
    app.apiVersion = VK_API_VERSION_1_1;
    return app;
}

VkInstance VulkanContext::createInstanceWrapper(void*, const VkInstanceCreateInfo* info) {
    // Hardware-buffer import relies on Vulkan 1.1 (external memory, YCbCr conversion are core there).
    VkInstanceCreateInfo copy = *info;
    VkApplicationInfo app = info->pApplicationInfo ? *info->pApplicationInfo : defaultApplicationInfo();
    app.apiVersion = std::max(app.apiVersion, (uint32_t) VK_API_VERSION_1_1);
    copy.pApplicationInfo = &app;
    VkInstance result = VK_NULL_HANDLE;
    if (vkCreateInstance(&copy, nullptr, &result) != VK_SUCCESS) return VK_NULL_HANDLE;
    return result;
}

bool VulkanContext::createInstance(const retro_hw_render_context_negotiation_interface_vulkan* negotiation) {
    VkApplicationInfo fallback = defaultApplicationInfo();
    const VkApplicationInfo* app = negotiation && negotiation->get_application_info
        ? negotiation->get_application_info() : nullptr;
    if (app == nullptr) app = &fallback;

    if (negotiation && negotiation->interface_version >= 2 && negotiation->create_instance) {
        instance = negotiation->create_instance(vkGetInstanceProcAddr, app, &createInstanceWrapper, this);
    } else {
        VkInstanceCreateInfo info { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
        info.pApplicationInfo = app;
        instance = createInstanceWrapper(this, &info);
    }
    if (instance == VK_NULL_HANDLE) return false;

    uint32_t count = 0;
    vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (count == 0) return false;
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance, &count, devices.data());
    gpu = devices[0];
    return true;
}

std::vector<const char*> VulkanContext::frontendDeviceExtensions(VkPhysicalDevice physicalDevice) {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, available.data());
    auto has = [&](const char* name) {
        return std::any_of(available.begin(), available.end(), [&](auto& e) { return strcmp(e.extensionName, name) == 0; });
    };

    std::vector<const char*> result;
    if (has(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) {
        result.push_back(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
    }
    if (has(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
        result.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    }
    return result;
}

VkDevice VulkanContext::createDeviceWrapper(VkPhysicalDevice physicalDevice, void* opaque, const VkDeviceCreateInfo* info) {
    auto* self = static_cast<VulkanContext*>(opaque);
    // Add the extensions the frontend needs to share frames with GL, keeping the core's own.
    std::vector<const char*> extensions(info->ppEnabledExtensionNames, info->ppEnabledExtensionNames + info->enabledExtensionCount);
    // Mali GPUs fault (JOB_READ_FAULT -> VK_ERROR_DEVICE_LOST) on out-of-bounds buffer reads that
    // desktop GPUs tolerate; robust buffer access makes such reads return zero instead.
    VkPhysicalDeviceFeatures supported;
    vkGetPhysicalDeviceFeatures(physicalDevice, &supported);
    //
    // Cores developed against desktop drivers may also use features they never enabled (PCSX2 uses
    // dual-source blend factors without enabling dualSrcBlend); enable those when the GPU has them,
    // so the device matches what the core assumes.
    auto enableFeatures = [&](VkPhysicalDeviceFeatures& f) {
        if (supported.robustBufferAccess) f.robustBufferAccess = VK_TRUE;
        if (supported.dualSrcBlend) f.dualSrcBlend = VK_TRUE;
    };
    if (info->pEnabledFeatures) enableFeatures(*const_cast<VkPhysicalDeviceFeatures*>(info->pEnabledFeatures));
    for (auto* next = (VkBaseOutStructure*) info->pNext; next != nullptr; next = next->pNext) {
        if (next->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2) enableFeatures(((VkPhysicalDeviceFeatures2*) next)->features);
    }
    LOGI("Vulkan: GPU features robustBufferAccess=%d dualSrcBlend=%d", supported.robustBufferAccess, supported.dualSrcBlend);

    for (const char* extension : self->frontendDeviceExtensions(physicalDevice)) {
        bool present = std::any_of(extensions.begin(), extensions.end(), [&](const char* e) { return strcmp(e, extension) == 0; });
        if (!present) extensions.push_back(extension);
    }
    self->foreignQueueFamily = std::any_of(extensions.begin(), extensions.end(),
        [](const char* e) { return strcmp(e, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) == 0; });
    self->deviceExtensionsAdded = true;

    VkDeviceCreateInfo copy = *info;
    copy.enabledExtensionCount = (uint32_t) extensions.size();
    copy.ppEnabledExtensionNames = extensions.data();
    VkDevice result = VK_NULL_HANDLE;
    if (vkCreateDevice(physicalDevice, &copy, nullptr, &result) != VK_SUCCESS) return VK_NULL_HANDLE;
    return result;
}

bool VulkanContext::createDevice(const retro_hw_render_context_negotiation_interface_vulkan* negotiation) {
    retro_vulkan_context context {};
    bool created = false;

    if (negotiation && negotiation->interface_version >= 2 && negotiation->create_device2) {
        created = negotiation->create_device2(&context, instance, gpu, VK_NULL_HANDLE, vkGetInstanceProcAddr,
                                              &createDeviceWrapper, this);
    } else if (negotiation && negotiation->create_device) {
        requiredExtensions = frontendDeviceExtensions(gpu);
        foreignQueueFamily = std::any_of(requiredExtensions.begin(), requiredExtensions.end(),
            [](const char* e) { return strcmp(e, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) == 0; });
        created = negotiation->create_device(&context, instance, gpu, VK_NULL_HANDLE, vkGetInstanceProcAddr,
                                             requiredExtensions.data(), (unsigned) requiredExtensions.size(),
                                             nullptr, 0, nullptr);
    } else {
        // No negotiation: create a graphics-capable device with every supported feature enabled.
        uint32_t count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, nullptr);
        std::vector<VkQueueFamilyProperties> families(count);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, families.data());
        uint32_t family = 0;
        for (uint32_t i = 0; i < count; ++i) {
            if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { family = i; break; }
        }
        float priority = 1.0F;
        VkDeviceQueueCreateInfo queueInfo { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
        queueInfo.queueFamilyIndex = family;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkPhysicalDeviceFeatures features;
        vkGetPhysicalDeviceFeatures(gpu, &features);
        VkDeviceCreateInfo info { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
        info.queueCreateInfoCount = 1;
        info.pQueueCreateInfos = &queueInfo;
        info.pEnabledFeatures = &features;
        context.device = createDeviceWrapper(gpu, this, &info);
        if (context.device != VK_NULL_HANDLE) {
            context.gpu = gpu;
            context.queue_family_index = family;
            vkGetDeviceQueue(context.device, family, 0, &context.queue);
            created = true;
        }
    }

    if (!created || context.device == VK_NULL_HANDLE) return false;
    device = context.device;
    if (context.gpu != VK_NULL_HANDLE) gpu = context.gpu;
    queue = context.queue;
    queueFamily = context.queue_family_index;
    return true;
}

// --- per-frame handoff ---------------------------------------------------------------------------

void VulkanContext::setImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores,
                             const VkSemaphore* semaphores, uint32_t srcQueueFamily) {
    auto* self = static_cast<VulkanContext*>(handle);
    if (image) self->pendingImage = *image; else self->pendingImage.reset();
    self->pendingWaitSemaphores.assign(semaphores, semaphores + numSemaphores);
    self->pendingSrcQueueFamily = srcQueueFamily;
}

uint32_t VulkanContext::getSyncIndex(void* handle) {
    return static_cast<VulkanContext*>(handle)->syncIndex;
}

uint32_t VulkanContext::getSyncIndexMask(void*) {
    return (1u << SYNC_INDEX_COUNT) - 1;
}

void VulkanContext::setCommandBuffers(void* handle, uint32_t num, const VkCommandBuffer* buffers) {
    auto* self = static_cast<VulkanContext*>(handle);
    self->pendingCommandBuffers.insert(self->pendingCommandBuffers.end(), buffers, buffers + num);
}

void VulkanContext::waitSyncIndex(void*) {
    // copyFrameTo() waits for each frame's GPU work before returning, so every sync index is free.
}

void VulkanContext::lockQueue(void* handle) {
    static_cast<VulkanContext*>(handle)->queueMutex.lock();
}

void VulkanContext::unlockQueue(void* handle) {
    static_cast<VulkanContext*>(handle)->queueMutex.unlock();
}

void VulkanContext::setSignalSemaphore(void* handle, VkSemaphore semaphore) {
    static_cast<VulkanContext*>(handle)->pendingSignalSemaphore = semaphore;
}

bool VulkanContext::copyFrameTo(VkImage target, unsigned targetWidth, unsigned targetHeight,
                                unsigned frameWidth, unsigned frameHeight) {
    if (!pendingImage.has_value()) return false;

    const retro_vulkan_image& image = pendingImage.value();
    VkImage source = image.create_info.image;
    VkImageSubresourceRange range = image.create_info.subresourceRange;
    range.levelCount = 1;
    range.layerCount = 1;

    vkResetCommandBuffer(commandBuffer, 0);
    VkCommandBufferBeginInfo begin { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commandBuffer, &begin);

    bool ownershipTransfer = pendingSrcQueueFamily != VK_QUEUE_FAMILY_IGNORED && pendingSrcQueueFamily != queueFamily;
    uint32_t foreign = foreignQueueFamily ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_IGNORED;
    uint32_t ours = foreignQueueFamily ? queueFamily : VK_QUEUE_FAMILY_IGNORED;

    VkImageMemoryBarrier toTransfer[2] {};
    toTransfer[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransfer[0].srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    toTransfer[0].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toTransfer[0].oldLayout = image.image_layout;
    toTransfer[0].newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    toTransfer[0].srcQueueFamilyIndex = ownershipTransfer ? pendingSrcQueueFamily : VK_QUEUE_FAMILY_IGNORED;
    toTransfer[0].dstQueueFamilyIndex = ownershipTransfer ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
    toTransfer[0].image = source;
    toTransfer[0].subresourceRange = range;

    // The shared image's previous contents are discarded; it comes back from GL ("foreign").
    toTransfer[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransfer[1].srcAccessMask = 0;
    toTransfer[1].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransfer[1].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toTransfer[1].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransfer[1].srcQueueFamilyIndex = foreign;
    toTransfer[1].dstQueueFamilyIndex = ours;
    toTransfer[1].image = target;
    toTransfer[1].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 2, toTransfer);

    unsigned width = std::min(frameWidth, targetWidth);
    unsigned height = std::min(frameHeight, targetHeight);
    VkImageBlit blit {};
    blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, range.baseMipLevel, range.baseArrayLayer, 1 };
    blit.srcOffsets[1] = { (int32_t) width, (int32_t) height, 1 };
    blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    // Vulkan images start at the top row, GL textures at the bottom: flip while copying.
    blit.dstOffsets[0] = { 0, (int32_t) height, 0 };
    blit.dstOffsets[1] = { (int32_t) width, 0, 1 };
    vkCmdBlitImage(commandBuffer, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);

    VkImageMemoryBarrier toFinal[2] {};
    toFinal[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toFinal[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toFinal[0].dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    toFinal[0].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    // Give the core its image back as it was (UNDEFINED isn't a valid target layout).
    toFinal[0].newLayout = image.image_layout != VK_IMAGE_LAYOUT_UNDEFINED
        ? image.image_layout : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    toFinal[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toFinal[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toFinal[0].image = source;
    toFinal[0].subresourceRange = range;

    toFinal[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toFinal[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toFinal[1].dstAccessMask = 0;
    toFinal[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toFinal[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toFinal[1].srcQueueFamilyIndex = ours; // release to GL
    toFinal[1].dstQueueFamilyIndex = foreign;
    toFinal[1].image = target;
    toFinal[1].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 0, nullptr, 0, nullptr, 2, toFinal);
    vkEndCommandBuffer(commandBuffer);

    std::vector<VkCommandBuffer> commandBuffers = pendingCommandBuffers;
    commandBuffers.push_back(commandBuffer);
    std::vector<VkPipelineStageFlags> waitStages(pendingWaitSemaphores.size(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);

    VkSubmitInfo submit { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    submit.waitSemaphoreCount = (uint32_t) pendingWaitSemaphores.size();
    submit.pWaitSemaphores = pendingWaitSemaphores.data();
    submit.pWaitDstStageMask = waitStages.data();
    submit.commandBufferCount = (uint32_t) commandBuffers.size();
    submit.pCommandBuffers = commandBuffers.data();
    if (pendingSignalSemaphore != VK_NULL_HANDLE) {
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &pendingSignalSemaphore;
    }

    VkResult result;
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        result = vkQueueSubmit(queue, 1, &submit, fence);
    }
    if (result == VK_SUCCESS) {
        vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
        vkResetFences(device, 1, &fence);
    } else {
        LOGE("Vulkan: frame copy submit failed (%d)", result);
    }

    // Semaphores and command buffers are single-use; a repeated frame must not wait on them again.
    pendingWaitSemaphores.clear();
    pendingCommandBuffers.clear();
    pendingSignalSemaphore = VK_NULL_HANDLE;
    syncIndex = (syncIndex + 1) % SYNC_INDEX_COUNT;
    return result == VK_SUCCESS;
}

} // namespace libretrodroid
