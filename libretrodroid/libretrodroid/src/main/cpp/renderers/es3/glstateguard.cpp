/*
 *     RaineyWrapper addition to LibretroDroid. GPL-3.0-or-later, like the rest of LibretroDroid.
 */

#include "glstateguard.h"

namespace libretrodroid {

GLStateGuard::GLStateGuard() {
    for (int i = 0; i < CAPABILITY_COUNT; ++i) {
        capabilities[i] = glIsEnabled(CAPABILITIES[i]);
    }
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFramebuffer);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFramebuffer);
    glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer);
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vertexArray);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
    glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &pixelUnpackBuffer);
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pixelPackBuffer);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTexture);
    for (int i = 0; i < TEXTURE_UNITS; ++i) {
        glActiveTexture(GL_TEXTURE0 + i);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &textures[i]);
        glGetIntegerv(GL_SAMPLER_BINDING, &samplers[i]);
    }
    glGetIntegerv(GL_VIEWPORT, viewport);
    glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
    glGetBooleanv(GL_DEPTH_WRITEMASK, &depthMask);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpackAlignment);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &unpackRowLength);

    // The clean state the frontend's drawing code was written against.
    for (GLenum capability : CAPABILITIES) {
        glDisable(capability);
    }
    glBindVertexArray(0); // the frontend draws from client-side arrays, which need VAO 0
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    for (int i = 0; i < TEXTURE_UNITS; ++i) {
        glActiveTexture(GL_TEXTURE0 + i);
        glBindSampler(i, 0);
    }
    glActiveTexture(GL_TEXTURE0);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDepthMask(GL_TRUE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
}

GLStateGuard::~GLStateGuard() {
    for (int i = 0; i < CAPABILITY_COUNT; ++i) {
        if (capabilities[i]) glEnable(CAPABILITIES[i]); else glDisable(CAPABILITIES[i]);
    }
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
    glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
    glUseProgram(program);
    glBindVertexArray(vertexArray);
    glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
    for (int i = 0; i < TEXTURE_UNITS; ++i) {
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        glBindSampler(i, samplers[i]);
    }
    glActiveTexture(activeTexture);
    glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
    glDepthMask(depthMask);
    glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
    glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlignment);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, unpackRowLength);
}

} // namespace libretrodroid
