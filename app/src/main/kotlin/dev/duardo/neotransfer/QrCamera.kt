package dev.duardo.neotransfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCamera(modifier: Modifier, paused: Boolean, onCode: (String) -> Unit, onError: (String) -> Unit) {
    val code by rememberUpdatedState(onCode)
    val error by rememberUpdatedState(onError)
    val pause by rememberUpdatedState(paused)
    AndroidView(modifier = modifier, factory = { context ->
        CameraPreview(context, { pause }, { code(it) }, { error(it) })
    }, onRelease = { it.close() })
}

private class CameraPreview(context: Context, private val paused: () -> Boolean,
                            private val code: (String) -> Unit, private val error: (String) -> Unit) : TextureView(context) {
    private val thread = HandlerThread("NeoTransferCamera").apply { start() }
    private val worker = Handler(thread.looper)
    private val stopped = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    @Volatile private var ownedTexture: SurfaceTexture? = null
    private var device: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var preview: Surface? = null
    private var frameAt = 0L
    private var previous = ""
    private var codeAt = 0L
    private var bufferWidth = 640
    private var bufferHeight = 480
    private val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { ownedTexture = texture; open(texture) }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { transform() }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { close(); return false }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    private fun open(texture: SurfaceTexture) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            report("Permite la cámara para escanear"); return
        }
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: throw IllegalStateException("No hay cámara trasera disponible")
            val map = requireNotNull(manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).toSet()
            val size = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it in previewSizes && it.width <= 1280 }
                .minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) }
                ?: throw IllegalStateException("La cámara no ofrece un formato compatible")
            bufferWidth = size.width; bufferHeight = size.height
            texture.setDefaultBufferSize(size.width, size.height)
            transform()
            images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ source ->
                    val frame = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    frame.use {
                        val now = SystemClock.elapsedRealtime()
                        if (stopped.get() || paused() || now - frameAt < 200) return@use
                        frameAt = now
                        val plane = frame.planes[0]
                        val data = ByteArray(frame.width * frame.height)
                        for (y in 0 until frame.height) for (x in 0 until frame.width) {
                            data[y * frame.width + x] = plane.buffer.get(y * plane.rowStride + x * plane.pixelStride)
                        }
                        val luminance = PlanarYUVLuminanceSource(data, frame.width, frame.height, 0, 0, frame.width, frame.height, false)
                        try {
                            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text
                            if (result != previous || now - codeAt > 3_000) {
                                previous = result; codeAt = now
                                post { if (!stopped.get() && !paused()) code(result) }
                            }
                        } catch (_: ReaderException) { /* No readable QR in this frame. */ }
                        finally { reader.reset() }
                    }
                }, worker)
            }
            preview = Surface(texture)
            opening.set(true)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening.set(false)
                    if (stopped.get()) { camera.close(); return }
                    device = camera
                    val surface = preview ?: return
                    val imageSurface = images?.surface ?: return
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(surface, imageSurface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (stopped.get()) { session.close(); return }
                            capture = session
                            try {
                                session.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface); addTarget(imageSurface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                }.build(), null, worker)
                            } catch (_: Exception) { report("No se pudo iniciar la cámara") }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { report("No se pudo configurar la cámara") }
                    }, worker)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opening.set(false); device = camera; camera.close()
                    if (!stopped.get()) report("La cámara se ha desconectado")
                }
                override fun onError(camera: CameraDevice, reason: Int) {
                    opening.set(false); device = camera; camera.close()
                    if (!stopped.get()) report("La cámara no está disponible")
                }
                override fun onClosed(camera: CameraDevice) { finishClose() }
            }, worker)
        } catch (_: Exception) { opening.set(false); report("No se pudo abrir la cámara. Comprueba el permiso de cámara.") }
    }

    private fun transform() {
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val buffer = RectF(0f, 0f, bufferHeight.toFloat(), bufferWidth.toFloat())
        val matrix = Matrix()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            buffer.offset(view.centerX() - buffer.centerX(), view.centerY() - buffer.centerY())
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL)
            val scale = maxOf(height.toFloat() / bufferHeight, width.toFloat() / bufferWidth)
            matrix.postScale(scale, scale, view.centerX(), view.centerY())
            matrix.postRotate(90f * (rotation - 2), view.centerX(), view.centerY())
        } else {
            val ratio = bufferHeight.toFloat() / bufferWidth
            matrix.postScale(maxOf(1f, height * ratio / width), maxOf(1f, width / ratio / height), view.centerX(), view.centerY())
            if (rotation == Surface.ROTATION_180) matrix.postRotate(180f, view.centerX(), view.centerY())
        }
        setTransform(matrix)
    }

    private fun report(message: String) { if (!stopped.get()) post { error(message) }; close() }
    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        worker.post {
            runCatching { capture?.stopRepeating(); capture?.abortCaptures() }
            capture?.close()
            val camera = device
            if (camera != null) camera.close() else if (!opening.get()) finishClose()
        }
    }

    private fun finishClose() {
        if (!released.compareAndSet(false, true)) return
        images?.close(); preview?.release(); ownedTexture?.release(); ownedTexture = null
        thread.quitSafely()
    }
}
