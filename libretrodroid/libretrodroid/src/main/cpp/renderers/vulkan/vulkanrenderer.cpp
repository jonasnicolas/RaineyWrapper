/*
 *     RaineyWrapper addition to LibretroDroid. GPL-3.0-or-later, like the rest of LibretroDroid.
 */

#include "vulkanrenderer.h"

#include <algorithm>
#include <GLES2/gl2ext.h>

#include "vulkancontext.h"
#include "../../log.h"
#include "../../libretro/libretro-common/include/libretro.h"

namespace libretrodroid {

namespace {
PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBuffer = nullptr;
PFNEGLCREATEIMAGEKHRPROC eglCreateImage = nullptr;
PFNEGLDESTROYIMAGEKHRPROC eglDestroyImage = nullptr;
PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2D = nullptr;

void loadEglFunctions() {
    if (eglCreateImage) return;
    eglGetNativeClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress("eglGetNativeClientBufferANDROID");
    eglCreateImage = (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
    eglDestroyImage = (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
    glEGLImageTargetTexture2D = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) eglGetProcAddress("glEGLImageTargetTexture2DOES");
}

uint32_t findMemoryType(const VkPhysicalDeviceMemoryProperties& properties, uint32_t typeBits) {
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if (typeBits & (1u << i)) return i;
    }
    return 0;
}
} // namespace

VulkanRenderer::VulkanRenderer(unsigned width, unsigned height, ShaderManager::Chain shaders)
    : width(width), height(height), shaders(std::move(shaders)) {
    loadEglFunctions();
    if (!createTarget()) {
        LOGE("Vulkan: could not create the shared frame buffer");
    }
    framebuffers = ES3Utils::buildShaderPasses(this->width, this->height, this->shaders);
}

VulkanRenderer::~VulkanRenderer() {
    destroyTarget();
}

bool VulkanRenderer::createTarget() {
    auto& vulkan = VulkanContext::getInstance();
    if (!vulkan.isInitialized() || !eglGetNativeClientBuffer || !eglCreateImage || !glEGLImageTargetTexture2D) {
        return false;
    }

    AHardwareBuffer_Desc desc {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&desc, &hardwareBuffer) != 0) return false;

    // Vulkan side: an image whose memory is the hardware buffer.
    VkAndroidHardwareBufferPropertiesANDROID properties { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID };
    if (vulkan.getAhbProperties(vulkan.device, hardwareBuffer, &properties) != VK_SUCCESS) return false;

    VkExternalMemoryImageCreateInfo external { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO };
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imageInfo { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    imageInfo.pNext = &external;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = { width, height, 1 };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(vulkan.device, &imageInfo, nullptr, &vkImage) != VK_SUCCESS) return false;

    VkImportAndroidHardwareBufferInfoANDROID import { VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID };
    import.buffer = hardwareBuffer;
    VkMemoryDedicatedAllocateInfo dedicated { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO };
    dedicated.pNext = &import;
    dedicated.image = vkImage;
    VkMemoryAllocateInfo allocInfo { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    allocInfo.pNext = &dedicated;
    allocInfo.allocationSize = properties.allocationSize;
    allocInfo.memoryTypeIndex = findMemoryType(vulkan.memoryProperties, properties.memoryTypeBits);
    if (vkAllocateMemory(vulkan.device, &allocInfo, nullptr, &vkMemory) != VK_SUCCESS) return false;
    if (vkBindImageMemory(vulkan.device, vkImage, vkMemory, 0) != VK_SUCCESS) return false;

    // GL side: the same buffer as a texture.
    EGLClientBuffer clientBuffer = eglGetNativeClientBuffer(hardwareBuffer);
    EGLint attributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    eglImage = eglCreateImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attributes);
    if (eglImage == EGL_NO_IMAGE_KHR) return false;

    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glEGLImageTargetTexture2D(GL_TEXTURE_2D, (GLeglImageOES) eglImage);
    GLint filter = shaders.linearTexture ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    LOGI("Vulkan: shared frame buffer %ux%u ready", width, height);
    return true;
}

void VulkanRenderer::destroyTarget() {
    auto& vulkan = VulkanContext::getInstance();
    if (texture) glDeleteTextures(1, &texture);
    if (eglImage != EGL_NO_IMAGE_KHR && eglDestroyImage) eglDestroyImage(eglGetCurrentDisplay(), eglImage);
    if (vulkan.isInitialized()) {
        vkDeviceWaitIdle(vulkan.device);
        if (vkImage) vkDestroyImage(vulkan.device, vkImage, nullptr);
        if (vkMemory) vkFreeMemory(vulkan.device, vkMemory, nullptr);
    }
    if (hardwareBuffer) AHardwareBuffer_release(hardwareBuffer);
    texture = 0;
    eglImage = EGL_NO_IMAGE_KHR;
    vkImage = VK_NULL_HANDLE;
    vkMemory = VK_NULL_HANDLE;
    hardwareBuffer = nullptr;
}

void VulkanRenderer::onNewFrame(const void* data, unsigned frameWidth, unsigned frameHeight, size_t pitch) {
    Renderer::onNewFrame(data, frameWidth, frameHeight, pitch);
    if (isDirty) {
        destroyTarget();
        createTarget();
        framebuffers = ES3Utils::buildShaderPasses(width, height, shaders);
        isDirty = false;
    }
    if (data == RETRO_HW_FRAME_BUFFER_VALID && vkImage != VK_NULL_HANDLE) {
        hasFrame = VulkanContext::getInstance().copyFrameTo(vkImage, width, height, frameWidth, frameHeight) || hasFrame;
    }
}

uintptr_t VulkanRenderer::getTexture() {
    return texture;
}

uintptr_t VulkanRenderer::getFramebuffer() {
    return 0; // Vulkan cores present through set_image, not a GL framebuffer
}

void VulkanRenderer::setPixelFormat(int) {}

std::pair<float, float> VulkanRenderer::getUsedFraction() {
    if (width == 0 || height == 0 || lastFrameSize.first <= 0 || lastFrameSize.second <= 0) return {1.0F, 1.0F};
    return {
        std::min(1.0F, (float) lastFrameSize.first / (float) width),
        std::min(1.0F, (float) lastFrameSize.second / (float) height)
    };
}

void VulkanRenderer::updateRenderedResolution(unsigned int newWidth, unsigned int newHeight) {
    if (newWidth > width || newHeight > height) {
        width = std::max(width, newWidth);
        height = std::max(height, newHeight);
        isDirty = true;
    }
}

bool VulkanRenderer::rendersInVideoCallback() {
    return true;
}

void VulkanRenderer::setShaders(ShaderManager::Chain newShaders) {
    if (newShaders != shaders) {
        shaders = newShaders;
        isDirty = true;
    }
}

Renderer::PassData VulkanRenderer::getPassData(unsigned int layer) {
    PassData result;
    if (layer < framebuffers->size()) {
        result.framebuffer = framebuffers->at(layer)->framebuffer;
        result.width = framebuffers->at(layer)->width;
        result.height = framebuffers->at(layer)->height;
    }
    if (layer > 0 && layer < framebuffers->size() + 1) {
        result.texture = framebuffers->at(layer - 1)->texture;
    }
    return result;
}

} // namespace libretrodroid
