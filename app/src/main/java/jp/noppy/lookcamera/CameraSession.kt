package jp.noppy.lookcamera

import android.content.Context
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.TimeUnit

internal data class ZoomReading(val zoomRatio: Float, val minZoomRatio: Float, val maxZoomRatio: Float)

internal data class ExposureReading(val index: Int, val range: IntRange, val step: Float) {
    val ev: Float get() = index * step
}

internal enum class Flash(val mode: Int, val label: String) {
    OFF(ImageCapture.FLASH_MODE_OFF, "フラッシュ オフ"),
    AUTO(ImageCapture.FLASH_MODE_AUTO, "フラッシュ 自動"),
    ON(ImageCapture.FLASH_MODE_ON, "フラッシュ オン");
    fun next() = entries[(ordinal + 1) % entries.size]
}

internal class CameraSession(
    context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val front: Boolean,
    flash: Flash,
    initial: LookSelection,
) : AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private var closed = false
    private val effect = PreviewEffect(initial) { error -> main.execute { if (!closed) fail(error) } }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var capture: ImageCapture? = null
    private var desiredFlash = flash
    var zoom by mutableStateOf<ZoomReading?>(null)
        private set
    var exposure by mutableStateOf<ExposureReading?>(null)
        private set
    private var exposureRequest = 0
    var streaming by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var canFlip by mutableStateOf(false)
        private set
    var hasFlash by mutableStateOf(false)
        private set

    private val streamObserver = Observer<PreviewView.StreamState> {
        streaming = it == PreviewView.StreamState.STREAMING
        if (streaming) refreshExposure() else ++exposureRequest
    }
    private val zoomObserver = Observer<ZoomState> {
        zoom = ZoomReading(it.zoomRatio, it.minZoomRatio, it.maxZoomRatio)
    }
    private val orientation = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            capture?.targetRotation = when (degrees) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }
    }

    init {
        view.previewStreamState.observe(owner, streamObserver)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!closed) {
                try {
                    provider = future.get()
                    view.doOnLayout { if (!closed) bind() }
                } catch (exception: Exception) {
                    fail(exception)
                }
            }
        }, main)
    }

    private fun bind() {
        try {
            val service = checkNotNull(provider)
            val back = service.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val selfie = service.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            canFlip = back && selfie
            val selector = if (selfie && (front || !back)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val rotation = view.display?.rotation ?: Surface.ROTATION_0
            val preview = Preview.Builder().setTargetRotation(rotation).build().also {
                preview = it
                it.setSurfaceProvider(view.surfaceProvider)
            }
            val image = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(rotation)
                .setJpegQuality(100)
                .build().also { capture = it }
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(image)
                .setViewPort(checkNotNull(view.viewPort)).addEffect(effect.effect).build()
            val bound = service.bindToLifecycle(owner, selector, group)
            camera = bound
            bound.cameraInfo.zoomState.observe(owner, zoomObserver)
            refreshExposure()
            hasFlash = bound.cameraInfo.hasFlashUnit()
            setFlash(desiredFlash)
            orientation.enable()
        } catch (exception: Exception) {
            fail(exception)
        }
    }

    fun setLook(selection: LookSelection) { effect.selection = selection }

    fun setFlash(flash: Flash) {
        desiredFlash = flash
        capture?.flashMode = if (hasFlash) flash.mode else ImageCapture.FLASH_MODE_OFF
    }

    fun focus(x: Float, y: Float) {
        if (!streaming) return
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
    }

    fun zoomTo(ratio: Float) {
        val limits = zoom ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(limits.minZoomRatio, limits.maxZoomRatio))
    }

    private fun refreshExposure() {
        val ae = camera?.cameraInfo?.exposureState
        exposure = if (ae?.isExposureCompensationSupported == true) {
            val range = ae.exposureCompensationRange
            ExposureReading(ae.exposureCompensationIndex, range.lower..range.upper,
                ae.exposureCompensationStep.toFloat())
        } else null
    }

    fun setExposure(index: Int) {
        val bound = camera ?: return
        val current = exposure ?: return
        val target = index.coerceIn(current.range)
        if (target == current.index) return
        val revision = ++exposureRequest
        exposure = current.copy(index = target)
        val request = bound.cameraControl.setExposureCompensationIndex(target)
        request.addListener({
            if (!closed && revision == exposureRequest) {
                val applied = runCatching { request.get() }
                    .getOrElse { bound.cameraInfo.exposureState.exposureCompensationIndex }
                exposure = current.copy(index = applied)
            }
        }, main)
    }

    fun shoot(model: CameraModel): Boolean {
        val current = capture ?: return false
        return streaming && error == null &&
            model.shoot(current, camera?.cameraInfo?.lensFacing == CameraSelector.LENS_FACING_FRONT)
    }

    private fun fail(exception: Throwable) {
        Log.e("LookCamera", "Camera unavailable", exception)
        if (exception is UnsupportedCameraTexture) {
            error = "この端末の GPU はライブ Look に対応していません"
        } else if (error == null) {
            error = "カメラを起動できませんでした"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        orientation.disable()
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        view.previewStreamState.removeObserver(streamObserver)
        provider?.unbind(*listOfNotNull(preview, capture).toTypedArray())
        effect.close()
    }
}

