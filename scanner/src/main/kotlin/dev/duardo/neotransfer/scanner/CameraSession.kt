package dev.duardo.neotransfer.scanner

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.TapToFocusInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/** CameraX preview + analysis, with the camera state the controls show. Main thread only. */
internal class CameraSession(context: Context, onFrame: (Frame) -> Unit) {
    val preview = PreviewView(context).apply {
        // TextureView: lets Compose draw and animate above the preview, and gives a frame to freeze.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val main = ContextCompat.getMainExecutor(context)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "Scanner") }
    private val controller = LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        setTapToFocusAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
    }
    val analyzer = CameraAnalyzer(main, onFrame, ::suggestZoom)

    var failed by mutableStateOf(false)
        private set
    var hasFlash by mutableStateOf(false)
        private set
    var torch by mutableStateOf(false)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var maxZoom by mutableFloatStateOf(1f)
        private set
    var focus by mutableStateOf<TapToFocusInfo?>(null)
        private set
    private var chosenZoomAt = 0L
    private var owner: LifecycleOwner? = null

    fun setMode(mode: ScanMode) {
        analyzer.mode = mode
        // Text needs more pixels per character than QR codes.
        val target = if (mode == ScanMode.QR) Size(1280, 720) else Size(1920, 1080)
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
    }

    fun bind(lifecycleOwner: LifecycleOwner) {
        owner = lifecycleOwner
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        preview.controller = controller
        controller.initializationFuture.addListener({
            failed = runCatching { controller.initializationFuture.get() }.isFailure ||
                !controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        }, main)
        runCatching { controller.bindToLifecycle(lifecycleOwner) }.onFailure { failed = true }
        controller.zoomState.observe(lifecycleOwner) { state ->
            zoom = state.zoomRatio
            maxZoom = state.maxZoomRatio
            analyzer.maxZoom = state.maxZoomRatio
            hasFlash = controller.cameraInfo?.hasFlashUnit() == true
        }
        controller.torchState.observe(lifecycleOwner) { torch = it == TorchState.ON }
        controller.tapToFocusInfoState.observe(lifecycleOwner) { focus = it }
    }

    fun unbind() {
        owner?.let { lifecycleOwner ->
            controller.zoomState.removeObservers(lifecycleOwner)
            controller.torchState.removeObservers(lifecycleOwner)
            controller.tapToFocusInfoState.removeObservers(lifecycleOwner)
        }
        owner = null
        controller.clearImageAnalysisAnalyzer()
        controller.unbind()
        preview.controller = null
    }

    fun close() = analyzer.close(executor)

    fun toggleTorch() {
        controller.enableTorch(!torch)
    }

    fun chooseZoom(ratio: Float) {
        chosenZoomAt = SystemClock.elapsedRealtime()
        controller.setZoomRatio(ratio)
    }

    // ML Kit asks for more zoom on small or far codes; a zoom the person just picked wins for a while.
    private fun suggestZoom(ratio: Float) {
        if (SystemClock.elapsedRealtime() - chosenZoomAt < 4_000 || ratio <= zoom + 0.05f) return
        controller.setZoomRatio(ratio.coerceAtMost(maxZoom))
    }
}
