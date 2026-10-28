package com.example.camaraip

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var urlText: TextView
    private lateinit var statusText: TextView
    private lateinit var streamText: TextView
    private lateinit var kiosk: Kiosk

    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    /** Recibe los cuadros del video y codifica los JPEG fuera del hilo principal. */
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var imageAnalysis: ImageAnalysis? = null
    private var server: SnapshotServer? = null
    @Volatile private var rtspServer: RtspServer? = null

    // Video H.264 para RTSP: solo se toca desde el hilo de la cámara (salvo pedir keyframes).
    @Volatile private var encoder: H264Encoder? = null
    private var lastVideoFrameNs = 0L
    private var failedEncoderSize: Size? = null
    private val photosServed = AtomicInteger()
    private var hasBothCameras = false

    // Acceso oculto al menú de administración: varios toques seguidos en el panel de arriba.
    private var adminTaps = 0
    private var firstAdminTapAt = 0L

    /** Pedidos HTTP esperando el próximo cuadro del video. */
    private val pendingRequests = ConcurrentLinkedQueue<FrameRequest>()

    private class FrameRequest(val maxWidth: Int?, val quality: Int) {
        val result = CompletableFuture<Jpeg>()
    }

    private class Jpeg(val bytes: ByteArray, val width: Int, val height: Int)

    /** Rotación física del teléfono, para que las fotos salgan derechas aunque la UI esté fija en portrait. */
    @Volatile private var physicalRotation = Surface.ROTATION_0

    private var lensFacing: Int
        get() = prefs.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        set(value) = prefs.edit { putInt(KEY_LENS_FACING, value) }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusText.setText(R.string.permission_denied)
        }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                physicalRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageAnalysis?.targetRotation = physicalRotation
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshUrlOnUiThread()
        override fun onLost(network: Network) = refreshUrlOnUiThread()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            refreshUrlOnUiThread()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        urlText = findViewById(R.id.url_text)
        statusText = findViewById(R.id.status_text)
        streamText = findViewById(R.id.stream_text)
        kiosk = Kiosk(this)

        enterFullscreen()
        findViewById<View>(R.id.info_panel).setOnClickListener { onAdminTap() }

        kiosk.grantCameraPermission()
        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // Recién con el permiso de cámara resuelto, para no bloquear el diálogo de permisos.
        if (hasCameraPermission()) kiosk.lockIfEnabled()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        refreshUrl()
        startServers()
    }

    override fun onStop() {
        super.onStop()
        server?.stop()
        server = null
        rtspServer?.stop()
        rtspServer = null
        analysisExecutor.execute { stopEncoder() }
        orientationListener.disable()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    // ---------------------------------------------------------------- Cámara

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val hasBack = provider.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = provider.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA)
        if (lensFacing == CameraSelector.LENS_FACING_BACK && !hasBack) lensFacing = CameraSelector.LENS_FACING_FRONT
        if (lensFacing == CameraSelector.LENS_FACING_FRONT && !hasFront) lensFacing = CameraSelector.LENS_FACING_BACK
        hasBothCameras = hasBack && hasFront

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // 4:3 es el aspecto nativo del sensor: la foto aprovecha todo el campo de visión.
        val aspectRatio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatio).build())
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Las fotos salen del stream de video (siempre enfocado y expuesto), no de una captura
        // de foto completa: así la respuesta tarda decenas de ms en vez de casi un segundo.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Cuadros ya rotados según la posición del teléfono: el video RTSP sale derecho.
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(physicalRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectRatio)
                    .setResolutionStrategy(
                        ResolutionStrategy(FRAME_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

        imageAnalysis = null
        provider.unbindAll()
        try {
            provider.bindToLifecycle(this, selector, preview, analysis)
            imageAnalysis = analysis
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la cámara", e)
            statusText.text = getString(R.string.status_error, e.message)
        }
    }

    private fun ProcessCameraProvider.hasCameraSafe(selector: CameraSelector) =
        runCatching { hasCamera(selector) }.getOrDefault(false)

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCamera()
    }

    /** Llamado por CameraX con cada cuadro del video. */
    private fun onFrame(image: ImageProxy) {
        try {
            streamFrame(image)
            servePhotoRequests(image)
        } finally {
            image.close()
        }
    }

    /** Responde los pedidos HTTP de foto que estén esperando, con este cuadro. */
    private fun servePhotoRequests(image: ImageProxy) {
        var request = pendingRequests.poll() ?: return
        val frame = try {
            image.toBitmap()
        } catch (e: Exception) {
            request.result.completeExceptionally(e)
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        while (true) {
            try {
                request.result.complete(encodeJpeg(frame, rotation, request.maxWidth, request.quality))
            } catch (e: Exception) {
                request.result.completeExceptionally(e)
            }
            request = pendingRequests.poll() ?: break
        }
        frame.recycle()
    }

    /** Alimenta el encoder H.264 mientras haya clientes RTSP conectados. */
    private fun streamFrame(image: ImageProxy) {
        val rtsp = rtspServer
        if (rtsp == null || !rtsp.hasClients) {
            stopEncoder()
            return
        }
        val timestamp = image.imageInfo.timestamp
        if (timestamp - lastVideoFrameNs < MIN_VIDEO_FRAME_INTERVAL_NS) return

        var current = encoder
        if (current != null && (current.width != image.width || current.height != image.height)) {
            stopEncoder() // cambió la orientación o la cámara: hace falta otro encoder
            current = null
        }
        if (current == null) {
            val size = Size(image.width, image.height)
            if (size == failedEncoderSize) return
            current = try {
                H264Encoder(image.width, image.height, VIDEO_FPS, VIDEO_BITRATE, rtsp::setConfig, rtsp::sendFrame)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo iniciar el encoder H.264 para $size", e)
                failedEncoderSize = size
                return
            }
            encoder = current
        }

        lastVideoFrameNs = timestamp
        try {
            current.encode(image)
        } catch (e: Exception) {
            Log.w(TAG, "Falló la codificación de video", e)
            stopEncoder()
        }
    }

    private fun stopEncoder() {
        val current = encoder ?: return
        encoder = null
        current.stop()
        rtspServer?.clearConfig()
    }

    /**
     * Rota los píxeles (sin depender del tag EXIF de orientación, que muchos sistemas de
     * reconocimiento facial ignoran), achica si hace falta y codifica en JPEG.
     */
    private fun encodeJpeg(frame: Bitmap, rotationDegrees: Int, maxWidth: Int?, quality: Int): Jpeg {
        val uprightWidth = if (rotationDegrees % 180 == 0) frame.width else frame.height
        val scale = if (maxWidth != null && maxWidth < uprightWidth) maxWidth.toFloat() / uprightWidth else 1f

        val output = if (rotationDegrees == 0 && scale == 1f) {
            frame
        } else {
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                postScale(scale, scale)
            }
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        }
        val out = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val jpeg = Jpeg(out.toByteArray(), output.width, output.height)
        if (output !== frame) output.recycle()
        return jpeg
    }

    /** Se llama desde un hilo del servidor HTTP: bloquea hasta tener el JPEG. */
    private fun capturePhoto(query: Map<String, String>): ByteArray {
        if (imageAnalysis == null) throw SnapshotServer.HttpException(503, "Camara no lista")

        val request = FrameRequest(
            maxWidth = query["width"]?.toIntOrNull()?.takeIf { it > 0 },
            quality = query["quality"]?.toIntOrNull()?.coerceIn(1, 100) ?: JPEG_QUALITY,
        )
        val start = SystemClock.elapsedRealtime()
        pendingRequests.add(request)

        val jpeg = try {
            request.result.get(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            pendingRequests.remove(request)
            throw SnapshotServer.HttpException(504, "La camara no respondio a tiempo")
        } catch (e: ExecutionException) {
            throw e.cause as? Exception ?: e
        }

        val elapsedMs = SystemClock.elapsedRealtime() - start
        val count = photosServed.incrementAndGet()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            statusText.text = getString(
                R.string.status_served, count, time, jpeg.width, jpeg.height, jpeg.bytes.size / 1024, elapsedMs
            )
        }
        return jpeg.bytes
    }

    // ---------------------------------------------------------------- Administración

    private fun onAdminTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - firstAdminTapAt > ADMIN_TAP_WINDOW_MS) {
            firstAdminTapAt = now
            adminTaps = 0
        }
        if (++adminTaps >= ADMIN_TAPS) {
            adminTaps = 0
            askAdminPin()
        }
    }

    private fun askAdminPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val container = FrameLayout(this).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin / 2, margin, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_pin_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (input.text.toString() == ADMIN_PIN) {
                    showAdminMenu()
                } else {
                    Toast.makeText(this, R.string.admin_pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAdminMenu() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (hasBothCameras) actions += getString(R.string.switch_camera) to ::switchCamera
        if (kiosk.isDeviceOwner) {
            actions += if (kiosk.isEnabled) {
                getString(R.string.admin_kiosk_off) to {
                    kiosk.disable()
                    Toast.makeText(this, R.string.kiosk_disabled, Toast.LENGTH_LONG).show()
                }
            } else {
                getString(R.string.admin_kiosk_on) to kiosk::enable
            }
            actions += getString(R.string.admin_remove_owner) to ::confirmRemoveDeviceOwner
        }

        val title = when {
            !kiosk.isDeviceOwner -> R.string.admin_title_no_owner
            kiosk.isEnabled -> R.string.admin_title_kiosk_on
            else -> R.string.admin_title_kiosk_off
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveDeviceOwner() {
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_remove_owner)
            .setMessage(R.string.admin_remove_owner_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> kiosk.removeDeviceOwner() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- Servidor y red

    private fun startServers() {
        val newServer = SnapshotServer(PORT) { query -> capturePhoto(query) }
        try {
            newServer.start()
            server = newServer
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servidor", e)
            statusText.text = getString(R.string.server_error, PORT, e.message)
        }

        streamText.text = getString(R.string.rtsp_clients, 0)
        val newRtspServer = RtspServer(
            RTSP_PORT,
            onPlayingCountChanged = { count ->
                runOnUiThread { streamText.text = getString(R.string.rtsp_clients, count) }
            },
            onKeyFrameNeeded = { encoder?.requestKeyFrame() },
        )
        try {
            newRtspServer.start()
            rtspServer = newRtspServer
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servidor RTSP", e)
            streamText.text = getString(R.string.server_error, RTSP_PORT, e.message)
        }
    }

    private fun refreshUrlOnUiThread() = runOnUiThread { refreshUrl() }

    private fun refreshUrl() {
        val addresses = localIpv4Addresses()
        urlText.text = if (addresses.isEmpty()) {
            getString(R.string.no_network)
        } else {
            addresses.joinToString("\n") { "http://$it:$PORT/photo\nrtsp://$it:$RTSP_PORT/live" }
        }
    }

    /** IPs IPv4 de la red local; prioriza Wi-Fi/Ethernet/hotspot sobre datos móviles. */
    private fun localIpv4Addresses(): List<String> {
        val byInterface = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .map { iface ->
                    iface.name to iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                }
                .filter { it.second.isNotEmpty() }
        }.getOrDefault(emptyList())

        val lan = byInterface.filter { (name, _) -> LAN_INTERFACE_PREFIXES.any { name.startsWith(it) } }
        return (lan.ifEmpty { byInterface }).flatMap { it.second }
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // La imagen también ocupa la zona del notch / cámara frontal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Solo el panel de texto se corre para no quedar debajo del notch; la cámara ocupa todo.
        val infoPanel = findViewById<View>(R.id.info_panel)
        val basePadding = infoPanel.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(infoPanel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(basePadding + bars.left, basePadding + bars.top, basePadding + bars.right, basePadding)
            insets
        }
    }

    private companion object {
        const val TAG = "CamaraIP"
        const val PORT = 8080
        const val RTSP_PORT = 8554

        /** Video RTSP: cuadros por segundo y bitrate del H.264. */
        const val VIDEO_FPS = 15
        const val VIDEO_BITRATE = 2_000_000
        const val MIN_VIDEO_FRAME_INTERVAL_NS = 800_000_000L / VIDEO_FPS // 80% del intervalo: tolera jitter
        const val CAPTURE_TIMEOUT_SECONDS = 10L
        const val KEY_LENS_FACING = "lens_facing"

        /**
         * Resolución máxima de las fotos (orientación del sensor: ancho x alto en horizontal).
         * Se elige la más cercana que soporte la cámara. Con ?width= se puede achicar por pedido.
         */
        val FRAME_SIZE = Size(1280, 960)

        /** Calidad JPEG por defecto (1-100). Con ?quality= se puede cambiar por pedido. */
        const val JPEG_QUALITY = 85

        val LAN_INTERFACE_PREFIXES = listOf("wlan", "eth", "swlan", "ap")

        /** PIN del menú de administración (5 toques seguidos en el panel de arriba). Cambialo. */
        const val ADMIN_PIN = "1234"
        const val ADMIN_TAPS = 5
        const val ADMIN_TAP_WINDOW_MS = 3_000L
    }
}
