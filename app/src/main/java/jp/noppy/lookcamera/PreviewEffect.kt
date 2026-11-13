package jp.noppy.lookcamera

import android.graphics.SurfaceTexture
import android.opengl.EGL14.EGL_BAD_NATIVE_WINDOW
import android.opengl.EGL14.EGL_BAD_SURFACE
import android.opengl.EGL14.EGL_SUCCESS
import android.opengl.EGLSurface
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor

internal class PreviewEffect(initial: LookSelection, private val onError: (Throwable) -> Unit) :
    SurfaceProcessor, AutoCloseable {
    private val thread = HandlerThread("Look preview").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command ->
        if (!handler.post(command)) command.run()
    }
    private var egl: GlContext? = null
    private var renderer: LookRenderer? = null
    private val outputs = mutableMapOf<SurfaceOutput, EGLSurface>()
    private val inputs = mutableSetOf<SurfaceTexture>()
    private var activeInput: SurfaceTexture? = null
    private var frameCount = 0
    private var firstFrameNanos = 0L
    private val sourceTransform = FloatArray(16)
    private val outputTransform = FloatArray(16)
    @Volatile private var closed = false
    @Volatile var selection = initial

    val effect: CameraEffect = object : CameraEffect(PREVIEW, executor, this, onError) {}

    private fun context(): GlContext = egl ?: GlContext().also { gl ->
        try {
            renderer = LookRenderer(external = true)
            egl = gl
        } catch (error: Exception) {
            gl.close()
            if (error is UnsupportedCameraTexture) onError(error)
            throw error
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (closed) {
            request.willNotProvideSurface()
            return
        }
        try {
            context().makeCurrent()
            val name = texture(GL_TEXTURE_EXTERNAL_OES)
            val size = request.resolution
            val input = SurfaceTexture(name)
            input.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(input)
            inputs += input
            activeInput = input
            frameCount = 0
            input.setOnFrameAvailableListener({ draw(request, it, name) }, handler)
            request.provideSurface(surface, executor) {
                try {
                    input.setOnFrameAvailableListener(null)
                    surface.release()
                    input.release()
                    runCatching {
                        egl?.makeCurrent()
                        deleteTexture(name)
                    }
                } finally {
                    inputs -= input
                    if (activeInput == input) activeInput = null
                    finishIfIdle()
                }
            }
        } catch (error: Exception) {
            request.willNotProvideSurface()
            Log.e("LookCamera", "Preview input failed", error)
            throw ProcessingException()
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (closed) {
            output.close()
            return
        }
        try {
            val surface = output.getSurface(executor) { removeOutput(output) }
            outputs[output] = context().window(surface)
        } catch (error: Exception) {
            output.close()
            Log.e("LookCamera", "Preview output failed", error)
            throw ProcessingException()
        }
    }

    private fun draw(request: SurfaceRequest, input: SurfaceTexture, name: Int) {
        if (closed || activeInput != input || outputs.isEmpty()) return
        try {
            val gl = checkNotNull(egl)
            gl.makeCurrent()
            input.updateTexImage()
            input.getTransformMatrix(sourceTransform)
            val timestamp = input.timestamp
            if (frameCount++ == 0) firstFrameNanos = timestamp
            if (frameCount == 121 || frameCount == 3601) {
                val elapsed = (timestamp - firstFrameNanos) / 1_000_000_000.0
                if (elapsed > 0) {
                    val fps = (frameCount - 1) / elapsed
                    Log.i("LookCamera", "Preview ${"%.1f".format(java.util.Locale.US, fps)} fps over ${frameCount - 1} frames")
                }
            }
            val current = selection
            val size = request.resolution
            val failed = mutableListOf<SurfaceOutput>()
            for ((output, surface) in outputs) {
                gl.makeCurrent(surface)
                output.updateTransformMatrix(outputTransform, sourceTransform)
                checkNotNull(renderer).draw(name, output.size.width, output.size.height,
                    size.width, size.height, outputTransform, current)
                when (val result = gl.present(surface)) {
                    EGL_SUCCESS -> Unit
                    EGL_BAD_SURFACE, EGL_BAD_NATIVE_WINDOW -> failed += output
                    else -> error("eglSwapBuffers failed: 0x${result.toString(16)}")
                }
            }
            failed.forEach(::removeOutput)
            if (failed.isNotEmpty() && outputs.isEmpty() && !closed) request.invalidate()
        } catch (error: Exception) {
            Log.e("LookCamera", "Preview rendering failed", error)
            onError(error)
            close()
        }
    }

    private fun removeOutput(output: SurfaceOutput) {
        try {
            outputs.remove(output)?.let { egl?.destroy(it) }
        } catch (error: Exception) {
            Log.w("LookCamera", "Preview surface cleanup failed", error)
        } finally {
            output.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.post {
            outputs.keys.toList().forEach(::removeOutput)
            finishIfIdle()
        }
    }

    private fun finishIfIdle() {
        if (closed && inputs.isEmpty() && outputs.isEmpty()) {
            try {
                runCatching {
                    egl?.makeCurrent()
                    renderer?.close()
                }
                egl?.close()
            } finally {
                renderer = null
                egl = null
                thread.quitSafely()
            }
        }
    }
}

