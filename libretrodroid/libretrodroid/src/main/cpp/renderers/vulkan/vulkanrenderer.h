/*
 *     RaineyWrapper addition to LibretroDroid. GPL-3.0-or-later, like the rest of LibretroDroid.
 */

#ifndef LIBRETRODROID_VULKANRENDERER_H
#define LIBRETRODROID_VULKANRENDERER_H

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <vulkan/vulkan.h>

#include "../renderer.h"
#include "../es3/es3utils.h"

namespace libretrodroid {

/**
 * RaineyWrapper: renderer for Vulkan cores. Frames are blitted (by VulkanContext) into a VkImage
 * backed by an AHardwareBuffer, and the same buffer is bound to a GL texture through an EGLImage,
 * so the rest of LibretroDroid's GL presentation (shaders, layout) works unchanged.
 */
class VulkanRenderer: public Renderer {
public:
    VulkanRenderer(unsigned width, unsigned height, ShaderManager::Chain shaders);
    ~VulkanRenderer() override;

    uintptr_t getTexture() override;
    uintptr_t getFramebuffer() override;
    void onNewFrame(const void* data, unsigned width, unsigned height, size_t pitch) override;
    void setPixelFormat(int pixelFormat) override;
    std::pair<float, float> getUsedFraction() override;
    void updateRenderedResolution(unsigned int width, unsigned int height) override;
    bool rendersInVideoCallback() override;
    void setShaders(ShaderManager::Chain shaders) override;
    PassData getPassData(unsigned int layer) override;

private:
    bool createTarget();
    void destroyTarget();

    unsigned width;
    unsigned height;
    bool isDirty = false;
    bool hasFrame = false;
    ShaderManager::Chain shaders;
    std::unique_ptr<ES3Utils::Framebuffers> framebuffers = std::make_unique<ES3Utils::Framebuffers>();

    AHardwareBuffer* hardwareBuffer = nullptr;
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    VkImage vkImage = VK_NULL_HANDLE;
    VkDeviceMemory vkMemory = VK_NULL_HANDLE;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_VULKANRENDERER_H
