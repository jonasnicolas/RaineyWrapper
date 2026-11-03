/*
 *     RaineyWrapper addition to LibretroDroid. GPL-3.0-or-later, like the rest of LibretroDroid.
 */

#ifndef LIBRETRODROID_VULKANCONTEXT_H
#define LIBRETRODROID_VULKANCONTEXT_H

#include <mutex>
#include <optional>
#include <vector>

#include <vulkan/vulkan.h>
#include "../../libretro/libretro-common/include/libretro.h"
#include "../../libretro/libretro-common/include/libretro_vulkan.h"

namespace libretrodroid {

/**
 * RaineyWrapper: the frontend side of libretro's Vulkan hardware-render API
 * (RETRO_HW_CONTEXT_VULKAN), so Vulkan cores such as PCSX2 can run.
 *
 * LibretroDroid itself still presents with OpenGL ES. Each frame the core hands over a finished
 * VkImage (set_image); copyFrameTo() blits it into a VkImage that is backed by an
 * AHardwareBuffer, which VulkanRenderer also exposes to GL as a texture.
 *
 * One instance per process: the core keeps the VkDevice for its whole lifetime, even if the
 * GL surface (and with it the renderer) is recreated.
 */
class VulkanContext {
public:
    static VulkanContext& getInstance();

    /** Creates the instance and device, letting the core take part via the negotiation interface. */
    bool initialize(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);
    bool isInitialized() const { return device != VK_NULL_HANDLE; }

    const retro_hw_render_interface_vulkan* getRenderInterface() const { return &renderInterface; }

    /**
     * Copies the last image the core submitted into [target] (an AHardwareBuffer-backed image of
     * targetWidth x targetHeight), waiting for the copy to finish so GL can sample it.
     * Returns false if the core hasn't submitted an image.
     */
    bool copyFrameTo(VkImage target, unsigned targetWidth, unsigned targetHeight, unsigned frameWidth, unsigned frameHeight);

    bool supportsForeignQueue() const { return foreignQueueFamily; }

    VkDevice device = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties = nullptr;
    VkPhysicalDeviceMemoryProperties memoryProperties {};

private:
    VulkanContext() = default;

    bool createInstance(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);
    bool createDevice(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);
    std::vector<const char*> frontendDeviceExtensions(VkPhysicalDevice physicalDevice);

    static VkInstance createInstanceWrapper(void* opaque, const VkInstanceCreateInfo* info);
    static VkDevice createDeviceWrapper(VkPhysicalDevice gpu, void* opaque, const VkDeviceCreateInfo* info);

    // retro_hw_render_interface_vulkan callbacks
    static void setImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores,
                         const VkSemaphore* semaphores, uint32_t srcQueueFamily);
    static uint32_t getSyncIndex(void* handle);
    static uint32_t getSyncIndexMask(void* handle);
    static void setCommandBuffers(void* handle, uint32_t num, const VkCommandBuffer* buffers);
    static void waitSyncIndex(void* handle);
    static void lockQueue(void* handle);
    static void unlockQueue(void* handle);
    static void setSignalSemaphore(void* handle, VkSemaphore semaphore);

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    bool foreignQueueFamily = false;
    bool deviceExtensionsAdded = false;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;

    std::mutex queueMutex;
    retro_hw_render_interface_vulkan renderInterface {};

    // Handed over by the core for the current frame.
    std::optional<retro_vulkan_image> pendingImage;
    std::vector<VkSemaphore> pendingWaitSemaphores;
    std::vector<VkCommandBuffer> pendingCommandBuffers;
    VkSemaphore pendingSignalSemaphore = VK_NULL_HANDLE;
    uint32_t pendingSrcQueueFamily = VK_QUEUE_FAMILY_IGNORED;
    uint32_t syncIndex = 0;

    // Storage for the extension list passed to the core's create_device (v1).
    std::vector<const char*> requiredExtensions;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_VULKANCONTEXT_H
