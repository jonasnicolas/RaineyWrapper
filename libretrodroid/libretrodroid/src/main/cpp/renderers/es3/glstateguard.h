/*
 *     RaineyWrapper addition to LibretroDroid. GPL-3.0-or-later, like the rest of LibretroDroid.
 */

#ifndef LIBRETRODROID_GLSTATEGUARD_H
#define LIBRETRODROID_GLSTATEGUARD_H

#include <GLES3/gl3.h>

namespace libretrodroid {

/**
 * RaineyWrapper: isolates the frontend's GL work from a hardware-rendered core's GL state.
 *
 * Hardware cores and LibretroDroid share one GL context, and LibretroDroid draws the core's
 * framebuffer to the screen from inside the core's video callback, i.e. in the middle of
 * retro_run(). Cores such as Dolphin and Flycast (which ask for RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT)
 * leave a vertex array object, buffers, scissor/blend state and color masks bound, which made
 * LibretroDroid's own draw fail (black screen), and they cache their GL state, so LibretroDroid's
 * changes corrupted their rendering afterwards.
 *
 * Constructing a guard saves everything the frontend touches and switches to a clean, default
 * state; destroying it restores the core's state exactly.
 */
class GLStateGuard {
public:
    GLStateGuard();
    ~GLStateGuard();

    GLStateGuard(const GLStateGuard&) = delete;
    GLStateGuard& operator=(const GLStateGuard&) = delete;

private:
    static constexpr GLenum CAPABILITIES[] = {
        GL_BLEND, GL_CULL_FACE, GL_DEPTH_TEST, GL_STENCIL_TEST, GL_SCISSOR_TEST, GL_DITHER,
        GL_POLYGON_OFFSET_FILL, GL_SAMPLE_ALPHA_TO_COVERAGE, GL_SAMPLE_COVERAGE,
        GL_RASTERIZER_DISCARD, GL_PRIMITIVE_RESTART_FIXED_INDEX,
    };
    static constexpr int CAPABILITY_COUNT = sizeof(CAPABILITIES) / sizeof(CAPABILITIES[0]);
    static constexpr int TEXTURE_UNITS = 2; // the frontend's shaders use units 0 and 1

    GLboolean capabilities[CAPABILITY_COUNT] {};
    GLint drawFramebuffer = 0;
    GLint readFramebuffer = 0;
    GLint renderbuffer = 0;
    GLint program = 0;
    GLint vertexArray = 0;
    GLint arrayBuffer = 0;
    GLint pixelUnpackBuffer = 0;
    GLint pixelPackBuffer = 0;
    GLint activeTexture = 0;
    GLint textures[TEXTURE_UNITS] {};
    GLint samplers[TEXTURE_UNITS] {};
    GLint viewport[4] {};
    GLboolean colorMask[4] {};
    GLboolean depthMask = GL_TRUE;
    GLfloat clearColor[4] {};
    GLint unpackAlignment = 4;
    GLint unpackRowLength = 0;
};

} // namespace libretrodroid

#endif // LIBRETRODROID_GLSTATEGUARD_H
