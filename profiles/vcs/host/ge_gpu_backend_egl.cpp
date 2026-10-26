#include "ge_gpu_backend.hpp"
#include "vcs_config.hpp"

#if defined(__ANDROID__)
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <android/log.h>
#endif

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace vcs {
namespace {

constexpr char kLogTag[] = "VCSEGL";

#if defined(__ANDROID__)

struct EglTexture {
    GLuint handle{};
    std::uint32_t width{};
    std::uint32_t height{};
    std::uint64_t signature{};
};

struct DrawBatch {
    GeGpuDrawDescriptor draw{};
    std::uint32_t first_vertex{};
    std::uint32_t vertex_count{};
    bool textured{};
    GLuint texture_handle{};
};

struct EglState {
    std::mutex mutex;
    ANativeWindow *window{};
    EGLDisplay display{EGL_NO_DISPLAY};
    EGLSurface surface{EGL_NO_SURFACE};
    EGLContext context{EGL_NO_CONTEXT};
    EGLConfig config{};
    std::uint32_t window_width{};
    std::uint32_t window_height{};

    GLuint program_untextured{};
    GLuint program_textured{};
    GLuint vbo{};
    GLuint vao{};

    GLint u_untextured_matrix{-1};
    GLint u_textured_matrix{-1};
    GLint u_textured_sampler{-1};

    std::unordered_map<std::uint64_t, EglTexture> texture_cache;
    GLuint last_uploaded_texture{};

    std::vector<GeGpuVertex> vertices;
    std::vector<DrawBatch> batches;

    std::uint32_t display_framebuffer{};
    std::uint32_t world_framebuffer_address{};

    bool initialized{};
    bool graphics_ready{};
    GeGpuBackendReport report{};
};

EglState &egl_state() {
    static EglState state;
    return state;
}

const char *kVertexShaderSource = R"(#version 300 es
layout(location = 0) in vec3 a_position;
layout(location = 1) in vec2 a_texcoord;
layout(location = 2) in vec4 a_color;

uniform mat4 u_matrix;
out vec2 v_texcoord;
out vec4 v_color;

void main() {
    vec4 pos = vec4(a_position, 1.0);
    if (pos.z > 1.0) pos.z = (pos.z / 32768.0) - 1.0;
    gl_Position = u_matrix * pos;
    v_texcoord = a_texcoord;
    v_color = a_color;
}
)";

const char *kFragmentUntexturedSource = R"(#version 300 es
precision mediump float;
in vec4 v_color;
out vec4 fragColor;

void main() {
    fragColor = v_color;
}
)";

const char *kFragmentTexturedSource = R"(#version 300 es
precision mediump float;
in vec2 v_texcoord;
in vec4 v_color;
uniform sampler2D u_texture;
out vec4 fragColor;

void main() {
    vec4 tex = texture(u_texture, v_texcoord);
    if (tex.a < 0.05) discard;
    fragColor = tex * v_color;
}
)";

GLuint compile_shader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char info[512]{};
        glGetShaderInfoLog(shader, sizeof(info), nullptr, info);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Shader compile error: %s", info);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint link_program(GLuint vs, GLuint fs) {
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char info[512]{};
        glGetProgramInfoLog(program, sizeof(info), nullptr, info);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Program link error: %s", info);
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

bool init_egl_context(EglState &s) {
    if (s.window == nullptr) return false;
    if (s.display != EGL_NO_DISPLAY && s.surface != EGL_NO_SURFACE) return true;

    s.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s.display == EGL_NO_DISPLAY) return false;

    EGLint major = 0, minor = 0;
    if (!eglInitialize(s.display, &major, &minor)) return false;

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_NONE
    };

    EGLint num_configs = 0;
    if (!eglChooseConfig(s.display, attribs, &s.config, 1, &num_configs) || num_configs < 1)
        return false;

    s.surface = eglCreateWindowSurface(s.display, s.config, s.window, nullptr);
    if (s.surface == EGL_NO_SURFACE) return false;

    const EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    s.context = eglCreateContext(s.display, s.config, EGL_NO_CONTEXT, context_attribs);
    if (s.context == EGL_NO_CONTEXT) return false;

    if (!eglMakeCurrent(s.display, s.surface, s.surface, s.context)) return false;

    s.window_width = static_cast<std::uint32_t>(ANativeWindow_getWidth(s.window));
    s.window_height = static_cast<std::uint32_t>(ANativeWindow_getHeight(s.window));

    GLuint vs = compile_shader(GL_VERTEX_SHADER, kVertexShaderSource);
    GLuint fs_untex = compile_shader(GL_FRAGMENT_SHADER, kFragmentUntexturedSource);
    GLuint fs_tex = compile_shader(GL_FRAGMENT_SHADER, kFragmentTexturedSource);

    s.program_untextured = link_program(vs, fs_untex);
    s.program_textured = link_program(vs, fs_tex);

    glDeleteShader(vs);
    glDeleteShader(fs_untex);
    glDeleteShader(fs_tex);

    s.u_untextured_matrix = glGetUniformLocation(s.program_untextured, "u_matrix");
    s.u_textured_matrix = glGetUniformLocation(s.program_textured, "u_matrix");
    s.u_textured_sampler = glGetUniformLocation(s.program_textured, "u_texture");

    glGenVertexArrays(1, &s.vao);
    glGenBuffers(1, &s.vbo);

    glBindVertexArray(s.vao);
    glBindBuffer(GL_ARRAY_BUFFER, s.vbo);

    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(GeGpuVertex), reinterpret_cast<void *>(offsetof(GeGpuVertex, x)));
    glEnableVertexAttribArray(0);

    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(GeGpuVertex), reinterpret_cast<void *>(offsetof(GeGpuVertex, u)));
    glEnableVertexAttribArray(1);

    glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, GL_TRUE, sizeof(GeGpuVertex), reinterpret_cast<void *>(offsetof(GeGpuVertex, rgba)));
    glEnableVertexAttribArray(2);

    glBindVertexArray(0);

    s.graphics_ready = true;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "EGL OpenGL ES 3.0 initialized successfully (%ux%u)",
                        s.window_width, s.window_height);
    return true;
}

void cleanup_egl(EglState &s) {
    if (s.display != EGL_NO_DISPLAY) {
        eglMakeCurrent(s.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (s.context != EGL_NO_CONTEXT) eglDestroyContext(s.display, s.context);
        if (s.surface != EGL_NO_SURFACE) eglDestroySurface(s.display, s.surface);
        eglTerminate(s.display);
    }
    s.display = EGL_NO_DISPLAY;
    s.surface = EGL_NO_SURFACE;
    s.context = EGL_NO_CONTEXT;
    s.graphics_ready = false;
}

#endif // __ANDROID__

} // namespace

bool ge_gpu_backend_initialize(std::string &) noexcept {
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    s.initialized = true;
    return true;
}

bool initialize_ge_gpu_backend(std::string &error) {
    return ge_gpu_backend_initialize(error);
}

bool ge_gpu_backend_active() noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    return s.graphics_ready;
#else
    return false;
#endif
}

bool ge_gpu_backend_transfer_ready() noexcept { return true; }

bool ge_gpu_backend_graphics_ready() noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    return s.graphics_ready;
#else
    return false;
#endif
}

void ge_gpu_backend_record_draw(const GeGpuDrawDescriptor &) noexcept {}

void ge_gpu_backend_observe_camera(const std::array<float, 12> &,
                                   const std::array<float, 16> &,
                                   const std::array<float, 6> &,
                                   const std::array<float, 3> &,
                                   const GeGpuDrawDescriptor &,
                                   std::uint32_t) noexcept {}

bool ge_gpu_backend_stage_vertices(const GeGpuDrawDescriptor &, std::span<const GeGpuVertex>) noexcept { return true; }

bool ge_gpu_backend_is_framebuffer_feedback_texture(const GeGpuDrawDescriptor &draw) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    if (!s.graphics_ready || !draw.texture_enabled) return false;
    const std::uint32_t texture_address = draw.texture_address & 0x001FFFF0u;
    return texture_address != 0u && (texture_address == s.world_framebuffer_address || texture_address == s.display_framebuffer);
#else
    return false;
#endif
}

bool ge_gpu_backend_texture_needed(const GeGpuDrawDescriptor &draw) noexcept {
    if (ge_gpu_backend_is_framebuffer_feedback_texture(draw)) return false;
    return draw.texture_enabled;
}

bool ge_gpu_backend_enabled() noexcept {
    return true;
}

void ge_gpu_backend_shutdown() noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    cleanup_egl(s);
    s.initialized = false;
#endif
}

void shutdown_ge_gpu_backend() noexcept {
    ge_gpu_backend_shutdown();
}

void ge_gpu_backend_prepare_texture_keys(GeGpuDrawDescriptor &draw) noexcept {
    if (!draw.texture_enabled) {
        draw.texture_cache_key_hint = 0u;
        draw.texture_image_key_hint = 0u;
        return;
    }
    draw.texture_cache_key_hint = static_cast<std::uint64_t>(draw.texture_address & 0x001FFFF0u)
        | (static_cast<std::uint64_t>(draw.clut_address & 0x001FFFF0u) << 32);
    draw.texture_image_key_hint = draw.texture_cache_key_hint;
}

bool ge_gpu_backend_texture_signature_needed(const GeGpuDrawDescriptor &draw) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    if (!s.graphics_ready || !draw.texture_enabled) return false;
    const std::uint64_t key = draw.texture_cache_key_hint != 0u ? draw.texture_cache_key_hint : draw.texture_address;
    auto it = s.texture_cache.find(key);
    if (it == s.texture_cache.end()) return true;
    return it->second.signature != draw.texture_content_signature;
#else
    return false;
#endif
}

GeGpuWidescreenHud ge_gpu_backend_widescreen_hud(const GeGpuDrawDescriptor &) noexcept {
    return {1.3333333f, 256.0f, 1.0f, true};
}

void ge_gpu_backend_note_through_extent(const GeGpuDrawDescriptor &, float, float) noexcept {}

bool ge_gpu_backend_adopt_shared_texture(const GeGpuDrawDescriptor &) noexcept {
    return false;
}

bool ge_gpu_backend_texture_available(const GeGpuDrawDescriptor &draw) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    const std::uint64_t key = draw.texture_cache_key_hint != 0u ? draw.texture_cache_key_hint : draw.texture_address;
    return s.texture_cache.find(key) != s.texture_cache.end();
#else
    return false;
#endif
}

bool ge_gpu_backend_upload_decoded_texture(const GeGpuDrawDescriptor &draw,
                                            std::uint32_t width, std::uint32_t height,
                                            std::span<const std::byte> rgba8) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    if (!s.graphics_ready || rgba8.empty() || width == 0u || height == 0u) return false;

    const std::uint64_t key = draw.texture_cache_key_hint != 0u ? draw.texture_cache_key_hint : draw.texture_address;
    auto &tex = s.texture_cache[key];
    if (tex.handle == 0) {
        glGenTextures(1, &tex.handle);
        tex.width = 0;
        tex.height = 0;
        tex.signature = 0;
    }
    glBindTexture(GL_TEXTURE_2D, tex.handle);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, draw.texture_linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, draw.texture_linear ? GL_LINEAR : GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, draw.texture_clamp_u ? GL_CLAMP_TO_EDGE : GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, draw.texture_clamp_v ? GL_CLAMP_TO_EDGE : GL_REPEAT);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    if (tex.width != width || tex.height != height || tex.signature != draw.texture_content_signature) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, static_cast<GLsizei>(width), static_cast<GLsizei>(height),
                     0, GL_RGBA, GL_UNSIGNED_BYTE, rgba8.data());
        tex.width = width;
        tex.height = height;
        tex.signature = draw.texture_content_signature;
    }
    s.last_uploaded_texture = tex.handle;
    ++s.report.decoded_texture_uploads;
    return true;
#else
    return false;
#endif
}

bool ge_gpu_backend_upload_decoded_texture_chain(const GeGpuDrawDescriptor &draw,
                                                  std::span<const GeGpuDecodedMipLevel> levels) noexcept {
    if (levels.empty()) return false;
    return ge_gpu_backend_upload_decoded_texture(draw, levels[0].width, levels[0].height, levels[0].rgba8);
}

bool ge_gpu_backend_upload_decoded_texture_chain_packed(const GeGpuDrawDescriptor &draw,
                                                         std::uint32_t base_width, std::uint32_t base_height,
                                                         std::uint32_t, std::vector<std::byte> rgba8) noexcept {
    return ge_gpu_backend_upload_decoded_texture(draw, base_width, base_height, rgba8);
}

bool ge_gpu_backend_copy_last_texture_rgba(std::span<std::byte>) noexcept { return false; }

void ge_gpu_backend_accumulate_color_triangles(const GeGpuDrawDescriptor &draw,
                                                std::span<const GeGpuVertex> triangle_vertices) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    if (!s.graphics_ready || triangle_vertices.empty()) return;

    const std::uint32_t first = static_cast<std::uint32_t>(s.vertices.size());
    s.vertices.insert(s.vertices.end(), triangle_vertices.begin(), triangle_vertices.end());

    GLuint tex_handle = 0;
    bool textured = false;
    if (draw.texture_enabled) {
        const std::uint64_t key = draw.texture_cache_key_hint != 0u ? draw.texture_cache_key_hint : draw.texture_address;
        auto it = s.texture_cache.find(key);
        if (it != s.texture_cache.end()) {
            tex_handle = it->second.handle;
            textured = true;
        }
    }

    s.batches.push_back({draw, first, static_cast<std::uint32_t>(triangle_vertices.size()), textured, tex_handle});
    s.report.game_vertices += triangle_vertices.size();
    s.report.game_triangles += triangle_vertices.size() / 3u;
#endif
}

void ge_gpu_backend_accumulate_hardware_triangles(const GeGpuDrawDescriptor &,
                                                   const GeGpuHardwareTransform &,
                                                   std::span<const GeGpuVertex>,
                                                   std::span<const std::uint32_t>) noexcept {}

bool ge_gpu_backend_accumulate_hardware_packed_0115(const GeGpuDrawDescriptor &,
                                                    const GeGpuHardwareTransform &,
                                                    std::span<const std::byte>,
                                                    std::uint32_t,
                                                    std::span<const std::uint32_t>) noexcept { return false; }

void ge_gpu_backend_set_native_window(void *native_window) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    s.window = static_cast<ANativeWindow *>(native_window);
    if (s.window == nullptr) {
        cleanup_egl(s);
    }
#endif
}

void ge_gpu_backend_set_display_framebuffer(std::uint32_t address) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    s.display_framebuffer = address & 0x001FFFF0u;
#endif
}

bool ge_gpu_backend_finish_color_frame(std::uint64_t) noexcept {
#if defined(__ANDROID__)
    EglState &s = egl_state();
    std::lock_guard lock(s.mutex);
    if (s.window == nullptr) {
        s.vertices.clear();
        s.batches.clear();
        return false;
    }
    if (!s.graphics_ready) {
        if (!init_egl_context(s)) {
            s.vertices.clear();
            s.batches.clear();
            return false;
        }
    }

    eglMakeCurrent(s.display, s.surface, s.surface, s.context);
    glViewport(0, 0, static_cast<GLsizei>(s.window_width), static_cast<GLsizei>(s.window_height));

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    // PSP 480x272 ortho projection matrix
    const float ortho[16] = {
        2.0f / 480.0f, 0.0f,           0.0f, 0.0f,
        0.0f,          -2.0f / 272.0f, 0.0f, 0.0f,
        0.0f,          0.0f,           1.0f, 0.0f,
        -1.0f,         1.0f,           0.0f, 1.0f
    };

    glBindVertexArray(s.vao);
    glBindBuffer(GL_ARRAY_BUFFER, s.vbo);
    glBufferData(GL_ARRAY_BUFFER, s.vertices.size() * sizeof(GeGpuVertex), s.vertices.data(), GL_STREAM_DRAW);

    const std::uint32_t target_fb = s.display_framebuffer != 0u ? s.display_framebuffer : s.world_framebuffer_address;

    for (const DrawBatch &batch : s.batches) {
        const std::uint32_t fb = batch.draw.framebuffer_address & 0x001FFFF0u;
        if (target_fb != 0u && fb != target_fb && fb != s.display_framebuffer && fb != s.world_framebuffer_address) {
            continue; // Skip offscreen shadow/reflection passes to eliminate flickering!
        }

        if (batch.draw.scissor_x1 > batch.draw.scissor_x0 && batch.draw.scissor_y1 > batch.draw.scissor_y0) {
            glEnable(GL_SCISSOR_TEST);
            GLint sx = static_cast<GLint>(batch.draw.scissor_x0 * s.window_width / 480);
            GLint sw = static_cast<GLint>((batch.draw.scissor_x1 - batch.draw.scissor_x0 + 1) * s.window_width / 480);
            GLint sh = static_cast<GLint>((batch.draw.scissor_y1 - batch.draw.scissor_y0 + 1) * s.window_height / 272);
            GLint sy = static_cast<GLint>(s.window_height - (batch.draw.scissor_y0 * s.window_height / 272 + sh));
            glScissor(std::max(0, sx), std::max(0, sy), std::max(1, sw), std::max(1, sh));
        } else {
            glDisable(GL_SCISSOR_TEST);
        }

        if (batch.draw.depth_test_enabled) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
        } else {
            glDisable(GL_DEPTH_TEST);
        }

        if (batch.draw.blend_enabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }

        if (batch.textured && batch.texture_handle != 0) {
            glUseProgram(s.program_textured);
            glUniformMatrix4fv(s.u_textured_matrix, 1, GL_FALSE, ortho);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, batch.texture_handle);
            glUniform1i(s.u_textured_sampler, 0);
        } else {
            glUseProgram(s.program_untextured);
            glUniformMatrix4fv(s.u_untextured_matrix, 1, GL_FALSE, ortho);
        }

        glDrawArrays(GL_TRIANGLES, static_cast<GLint>(batch.first_vertex), static_cast<GLsizei>(batch.vertex_count));
    }

    eglSwapBuffers(s.display, s.surface);

    s.vertices.clear();
    s.batches.clear();
    ++s.report.game_frames;
    return true;
#else
    return false;
#endif
}

bool ge_gpu_backend_copy_game_frame_rgba(std::span<std::byte>) noexcept { return false; }

bool ge_gpu_backend_presents_directly() noexcept {
#if defined(__ANDROID__)
    return true; // Direct hardware swapchain presentation via eglSwapBuffers!
#else
    return false;
#endif
}

std::uint32_t ge_gpu_backend_owned_framebuffer() noexcept {
#if defined(__ANDROID__)
    return egl_state().display_framebuffer;
#else
    return 0u;
#endif
}

std::uint32_t ge_gpu_backend_display_framebuffer() noexcept {
#if defined(__ANDROID__)
    return egl_state().display_framebuffer;
#else
    return 0u;
#endif
}

std::span<const std::byte> ge_gpu_backend_game_frame_rgba() noexcept { return {}; }
bool ge_gpu_backend_copy_offscreen_rgba(std::span<std::byte>) noexcept { return false; }
void ge_gpu_backend_mark_window_presented() noexcept {}

GeGpuBackendReport ge_gpu_backend_report() {
#if defined(__ANDROID__)
    return egl_state().report;
#else
    return {};
#endif
}

const char *ge_gpu_backend_name(GeGpuBackendKind kind) noexcept {
    switch (kind) {
        case GeGpuBackendKind::Software: return "Software";
        case GeGpuBackendKind::DirectX12: return "DirectX12";
        case GeGpuBackendKind::Vulkan: return "OpenGL ES 3.0 (EGL)";
    }
    return "Unknown";
}

} // namespace vcs
