package jp.noppy.lookcamera

import android.graphics.Bitmap
import android.opengl.EGL14.*
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Half
import java.util.concurrent.ConcurrentHashMap

internal class GlContext : AutoCloseable {
    private val display = acquireDisplay()
    private var context = EGL_NO_CONTEXT
    private var anchor = EGL_NO_SURFACE
    private val config: EGLConfig

    init {
        try {
            val attributes = intArrayOf(
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT or EGL_PBUFFER_BIT, EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            config = checkNotNull(configs[0])
            context = eglCreateContext(display, config, EGL_NO_CONTEXT,
                intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE), 0)
            check(context != EGL_NO_CONTEXT)
            anchor = eglCreatePbufferSurface(display, config,
                intArrayOf(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE), 0)
            check(anchor != EGL_NO_SURFACE)
            makeCurrent()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun window(surface: android.view.Surface): EGLSurface =
        eglCreateWindowSurface(display, config, surface, intArrayOf(EGL_NONE), 0).also {
            check(it != EGL_NO_SURFACE) { "eglCreateWindowSurface: ${eglGetError()}" }
        }

    fun makeCurrent(surface: EGLSurface = anchor) {
        check(eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent: ${eglGetError()}" }
    }

    fun present(surface: EGLSurface): Int =
        if (eglSwapBuffers(display, surface)) EGL_SUCCESS else eglGetError()

    fun destroy(surface: EGLSurface) {
        try {
            makeCurrent()
        } finally {
            eglDestroySurface(display, surface)
        }
    }

    override fun close() {
        if (display == EGL_NO_DISPLAY) return
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        if (anchor != EGL_NO_SURFACE) eglDestroySurface(display, anchor)
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context)
        eglReleaseThread()
        releaseDisplay(display)
    }

    companion object {
        private var clients = 0

        @Synchronized private fun acquireDisplay(): android.opengl.EGLDisplay {
            val display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
            check(display != EGL_NO_DISPLAY)
            if (clients == 0) check(eglInitialize(display, null, 0, null, 0))
            clients++
            return display
        }

        @Synchronized private fun releaseDisplay(display: android.opengl.EGLDisplay) {
            if (--clients == 0) eglTerminate(display)
        }
    }
}

internal fun texture(target: Int): Int {
    val name = IntArray(1)
    glGenTextures(1, name, 0)
    glBindTexture(target, name[0])
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    if (target == GL_TEXTURE_3D) glTexParameteri(target, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
    return name[0]
}

internal fun deleteTexture(name: Int) = glDeleteTextures(1, intArrayOf(name), 0)

internal class UnsupportedCameraTexture : IllegalStateException("GL_OES_EGL_image_external_essl3 is required")

internal class LookRenderer(private val external: Boolean) : AutoCloseable {
    private val program = if (external) {
        val extensions = glGetString(GL_EXTENSIONS).orEmpty().split(' ')
        if ("GL_OES_EGL_image_external_essl3" !in extensions) throw UnsupportedCameraTexture()
        makeProgram(FRAGMENT_CAMERA)
    } else {
        makeProgram(FRAGMENT_BITMAP)
    }
    private val uniforms = listOf("uTransform", "uSource", "uLut", "uIntensity", "uTextureSize", "uEffects", "uLutScale", "uFrameSize", "uHalation")
        .associateWith { glGetUniformLocation(program, it) }
    private val lut = texture(GL_TEXTURE_3D)
    private val lutBuffer = ByteBuffer.allocateDirect(LUT_SIZE * LUT_SIZE * LUT_SIZE * 8)
        .order(ByteOrder.nativeOrder()).asShortBuffer()
    private var currentGrade: Grade? = null

    fun draw(source: Int, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int,
             transform: FloatArray, selection: LookSelection) {
        val grade = selection.preset.grade
        glViewport(0, 0, width, height)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(if (external) GL_TEXTURE_EXTERNAL_OES else GL_TEXTURE_2D, source)
        glUniform1i(uniforms.getValue("uSource"), 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_3D, lut)
        if (currentGrade != grade) uploadLut(grade)
        glUniform1i(uniforms.getValue("uLut"), 1)
        glUniform2f(uniforms.getValue("uLutScale"), (LUT_SIZE - 1f) / LUT_SIZE, 0.5f / LUT_SIZE)
        glUniformMatrix4fv(uniforms.getValue("uTransform"), 1, false, transform, 0)
        glUniform1f(uniforms.getValue("uIntensity"), selection.amount)
        glUniform2f(uniforms.getValue("uTextureSize"), sourceWidth.toFloat(), sourceHeight.toFloat())
        glUniform4f(uniforms.getValue("uEffects"), grade.grain, grade.vignette, grade.bloom, grade.sharpness)
        glUniform2f(uniforms.getValue("uFrameSize"), width.toFloat(), height.toFloat())
        glUniform1f(uniforms.getValue("uHalation"), grade.halation)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glActiveTexture(GL_TEXTURE0)
    }

    private fun uploadLut(grade: Grade) {
        lutBuffer.clear()
        lutBuffer.put(luts.computeIfAbsent(grade, ::buildLut))
        lutBuffer.flip()
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, LUT_SIZE, LUT_SIZE, LUT_SIZE,
            0, GL_RGBA, GL_HALF_FLOAT, lutBuffer)
        currentGrade = grade
    }

    override fun close() {
        deleteTexture(lut)
        glDeleteProgram(program)
    }

    companion object {
        private const val LUT_SIZE = 33
        private val luts = ConcurrentHashMap<Grade, ShortArray>()

        private fun buildLut(grade: Grade): ShortArray {
            val texels = ShortArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 4)
            val scale = 1f / (LUT_SIZE - 1)
            var index = 0
            for (b in 0 until LUT_SIZE) for (g in 0 until LUT_SIZE) for (r in 0 until LUT_SIZE) {
                val rgb = grade.map(r * scale, g * scale, b * scale)
                for (channel in rgb) texels[index++] = Half.toHalf(channel)
                texels[index++] = Half.toHalf(1f)
            }
            return texels
        }
        private const val VERTEX = """#version 300 es
            uniform mat4 uTransform;
            out vec2 vUv;
            out vec2 vFrame;
            void main() {
                vFrame = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                vUv = (uTransform * vec4(vFrame, 0.0, 1.0)).xy;
                gl_Position = vec4(vFrame * 2.0 - 1.0, 0.0, 1.0);
            }
        """
        private const val SHADING = """
            uniform highp sampler3D uLut;
            uniform float uIntensity;
            uniform vec2 uTextureSize;
            uniform vec2 uFrameSize;
            uniform float uHalation;
            uniform vec4 uEffects;
            uniform vec2 uLutScale;
            in vec2 vUv;
            in vec2 vFrame;
            out vec4 color;
            const vec3 LUMA = vec3(.2126, .7152, .0722);
            vec3 grade(vec3 rgb) {
                return texture(uLut, clamp(rgb, 0.0, 1.0) * uLutScale.x + uLutScale.y).rgb;
            }
            float highlight(vec2 uv) {
                return smoothstep(.69, .99, dot(texture(uSource, uv).rgb, LUMA));
            }
            float spread(vec2 uv, vec2 d) {
                return (highlight(uv + vec2(d.x, 0)) + highlight(uv - vec2(d.x, 0)) +
                    highlight(uv + vec2(0, d.y)) + highlight(uv - vec2(0, d.y))) * .25;
            }
            float hash21(vec2 p) {
                vec3 h = fract(vec3(p.xyx) * .1031);
                h += dot(h, h.yzx + 33.33);
                return fract((h.x + h.y) * h.z);
            }
            float grain(vec2 position) {
                vec2 cell = floor(position);
                vec2 f = fract(position);
                f = f * f * (3.0 - 2.0 * f);
                float fine = mix(mix(hash21(cell), hash21(cell + vec2(1, 0)), f.x),
                    mix(hash21(cell + vec2(0, 1)), hash21(cell + 1.0), f.x), f.y);
                return .76 * (fine - .5) + .24 * (hash21(floor(position * 2.1)) - .5);
            }
            void main() {
                vec3 source = texture(uSource, vUv).rgb;
                if (uIntensity <= 0.0) {
                    color = vec4(source, 1.0);
                    return;
                }
                vec3 result = grade(source);
                if (abs(uEffects.w) > 0.0) {
                    vec2 d = 1.4 / uTextureSize;
                    vec3 neighbors = (texture(uSource, vUv + vec2(d.x, 0)).rgb +
                        texture(uSource, vUv - vec2(d.x, 0)).rgb +
                        texture(uSource, vUv + vec2(0, d.y)).rgb +
                        texture(uSource, vUv - vec2(0, d.y)).rgb) * .25;
                    vec3 detail = source - neighbors;
                    float edge = max(abs(detail.r), max(abs(detail.g), abs(detail.b)));
                    float detailWeight = uEffects.w < 0.0
                        ? 1.0 - smoothstep(.09, .25, edge)
                        : smoothstep(.012, .055, edge) * (1.0 - smoothstep(.16, .38, edge));
                    result += detail * uEffects.w * detailWeight;
                }
                if (uEffects.z > 0.0 || uHalation > 0.0) {
                    vec2 uvScale = min(uTextureSize.x, uTextureSize.y) / uTextureSize;
                    float core = smoothstep(.69, .99, dot(source, LUMA));
                    float inner = spread(vUv, .0028 * uvScale);
                    float outer = spread(vUv, .0078 * uvScale);
                    float glow = uEffects.z * (.67 * inner + .33 * outer);
                    float bleed = uHalation * max(0.0, .55 * inner + .45 * outer - core);
                    result += max(vec3(0.0), 1.0 - result) *
                        (.28 * glow + .68 * bleed * vec3(1.0, .33, .10));
                }
                float radius = length((vFrame - .5) * 1.52);
                result *= 1.0 - uEffects.y * smoothstep(.31, 1.0, radius);
                if (uEffects.x > 0.0) {
                    vec2 aspect = uFrameSize / min(uFrameSize.x, uFrameSize.y);
                    float luma = clamp(dot(result, LUMA), 0.0, 1.0);
                    float density = smoothstep(.01, .22, luma) * (1.0 - .88 * smoothstep(.38, 1.0, luma));
                    result += grain(vFrame * aspect * 1050.0) * uEffects.x * density;
                }
                color = vec4(mix(source, clamp(result, 0.0, 1.0), uIntensity), 1.0);
            }
        """
        private const val FRAGMENT_CAMERA = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform highp samplerExternalOES uSource;
        """ + SHADING
        private const val FRAGMENT_BITMAP = """#version 300 es
            precision highp float;
            uniform sampler2D uSource;
        """ + SHADING

        private fun compile(type: Int, source: String): Int {
            val shader = glCreateShader(type)
            glShaderSource(shader, source)
            glCompileShader(shader)
            val status = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = glGetShaderInfoLog(shader)
                glDeleteShader(shader)
                error(message)
            }
            return shader
        }

        private fun makeProgram(fragment: String): Int {
            val vertex = compile(GL_VERTEX_SHADER, VERTEX)
            try {
                val pixel = compile(GL_FRAGMENT_SHADER, fragment)
                try {
                    val program = glCreateProgram()
                    glAttachShader(program, vertex)
                    glAttachShader(program, pixel)
                    glLinkProgram(program)
                    val status = IntArray(1)
                    glGetProgramiv(program, GL_LINK_STATUS, status, 0)
                    if (status[0] == 0) {
                        val message = glGetProgramInfoLog(program)
                        glDeleteProgram(program)
                        error(message)
                    }
                    return program
                } finally {
                    glDeleteShader(pixel)
                }
            } finally {
                glDeleteShader(vertex)
            }
        }
    }
}

internal class BitmapLookRenderer : AutoCloseable {
    private val egl = GlContext()
    private val renderer = try {
        LookRenderer(external = false)
    } catch (error: Throwable) {
        egl.close()
        throw error
    }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun apply(input: Bitmap, selection: LookSelection): Bitmap {
        if (selection.amount == 0f) return input
        val width = input.width
        val height = input.height
        val source = texture(GL_TEXTURE_2D)
        val output = texture(GL_TEXTURE_2D)
        val framebuffer = IntArray(1)
        try {
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, width, height)
            glGenFramebuffers(1, framebuffer, 0)
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
            glBindTexture(GL_TEXTURE_2D, source)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, input, 0)
            renderer.draw(source, width, height, width, height, identity, selection)
            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            check(glGetError() == GL_NO_ERROR) { "JPEG の画像処理に失敗しました" }
            pixels.rewind()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(pixels) }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glDeleteFramebuffers(1, framebuffer, 0)
            deleteTexture(output)
            deleteTexture(source)
        }
    }

    override fun close() {
        try {
            renderer.close()
        } finally {
            egl.close()
        }
    }
}

