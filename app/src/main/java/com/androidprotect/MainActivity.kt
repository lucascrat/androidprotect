package com.androidprotect

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    
    // Standard permission launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Trigger UI refresh
        Log.d("MainActivity", "Permissions results: $results")
    }

    // Media Projection launcher
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AntiTheftService.mediaProjectionResultCode = result.resultCode
            AntiTheftService.mediaProjectionData = result.data
            Toast.makeText(this, "Transmissão de tela autorizada!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Autorização de tela rejeitada.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        setContent {
            AndroidProtectTheme {
                MainScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val context = this
        val deviceId = remember { 
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "Desconhecido" 
        }
        
        // Configuration States
        var serverIp by remember { mutableStateOf(AntiTheftService.serverIpAddress) }
        var isServiceActive by remember { mutableStateOf(AntiTheftService.isServiceRunning) }
        var testResult by remember { mutableStateOf<String?>(null) }
        var isTestingConnection by remember { mutableStateOf(false) }

        // Dynamic Permissions Checklist States
        var hasLocationPerm by remember { mutableStateOf(false) }
        var hasBgLocationPerm by remember { mutableStateOf(false) }
        var hasCameraPerm by remember { mutableStateOf(false) }
        var hasMicPerm by remember { mutableStateOf(false) }
        var hasNotifyPerm by remember { mutableStateOf(false) }
        
        val hasScreenCapturePerm = AntiTheftService.mediaProjectionData != null

        // Trigger updates when screen starts or active
        LaunchedEffect(Unit) {
            hasLocationPerm = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            hasBgLocationPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else true
            hasCameraPerm = hasPermission(Manifest.permission.CAMERA)
            hasMicPerm = hasPermission(Manifest.permission.RECORD_AUDIO)
            hasNotifyPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            } else true
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Elegant Gradient Header
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "AndroidProtect",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00D2FF)
            )
            Text(
                text = "Painel de Configuração de Segurança",
                color = Color(0xFF8E94A5),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            // Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .border(1.dp, Color(0xFF252630), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("STATUS DO DISPOSITIVO", fontSize = 11.sp, color = Color(0xFF8E94A5), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ID do Aparelho:", color = Color.White, fontSize = 13.sp)
                            Text(deviceId, color = Color(0xFF00D2FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        
                        Box(
                            modifier = Modifier
                                .background(
                                    if (isServiceActive) Color(0xFF1E3A24) else Color(0xFF3A1E24),
                                    RoundedCornerShape(50.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isServiceActive) "ATIVO" else "INATIVO",
                                color = if (isServiceActive) Color(0xFF39FF14) else Color(0xFFFF3838),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Connection Settings Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .border(1.dp, Color(0xFF252630), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("ENDEREÇO DO SERVIDOR", fontSize = 11.sp, color = Color(0xFF8E94A5), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = serverIp,
                        onValueChange = { serverIp = it },
                        label = { Text("IP ou Domínio do Servidor") },
                        placeholder = { Text("Ex: 192.168.1.100") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00D2FF),
                            unfocusedBorderColor = Color(0xFF252630),
                            focusedLabelColor = Color(0xFF00D2FF),
                            cursorColor = Color(0xFF00D2FF),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Test Connection Button
                        OutlinedButton(
                            onClick = {
                                isTestingConnection = true
                                testResult = null
                                thread {
                                    try {
                                        val address = InetAddress.getByName(serverIp)
                                        val reachable = address.isReachable(3000)
                                        testResult = if (reachable) "Conexão OK!" else "Servidor Inalcançável."
                                    } catch (e: Exception) {
                                        testResult = "Erro: ${e.message}"
                                    } finally {
                                        isTestingConnection = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isTestingConnection && serverIp.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00D2FF))
                        ) {
                            Text(if (isTestingConnection) "Testando..." else "Testar Conexão")
                        }

                        // Toggle Service Button
                        Button(
                            onClick = {
                                AntiTheftService.serverIpAddress = serverIp
                                val serviceIntent = Intent(context, AntiTheftService::class.java).apply {
                                    putExtra("SERVER_IP", serverIp)
                                }
                                
                                if (isServiceActive) {
                                    // Stop
                                    context.stopService(serviceIntent)
                                    isServiceActive = false
                                } else {
                                    // Start
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                    isServiceActive = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServiceActive) Color(0xFFFF3838) else Color(0xFF00D2FF)
                            )
                        ) {
                            Text(
                                text = if (isServiceActive) "Parar Serviço" else "Iniciar Serviço",
                                color = if (isServiceActive) Color.White else Color(0xFF0A0B10),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    
                    testResult?.let {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = it,
                            color = if (it.contains("OK")) Color(0xFF39FF14) else Color(0xFFFF3838),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }

            // Permissions Section Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .border(1.dp, Color(0xFF252630), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("CENTRAL DE PERMISSÕES", fontSize = 11.sp, color = Color(0xFF8E94A5), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    PermissionItem("Localização (GPS)", hasLocationPerm) {
                        requestPermissions(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )) { hasLocationPerm = true }
                    }
                    
                    PermissionItem("Localização Sempre Ativa", hasBgLocationPerm) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                                hasBgLocationPerm = true
                            }
                        }
                    }
                    
                    PermissionItem("Câmera Fotográfica", hasCameraPerm) {
                        requestPermissions(arrayOf(Manifest.permission.CAMERA)) { hasCameraPerm = true }
                    }
                    
                    PermissionItem("Gravador de Áudio", hasMicPerm) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { hasMicPerm = true }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        PermissionItem("Notificações de Sistema", hasNotifyPerm) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { hasNotifyPerm = true }
                        }
                    }
                }
            }

            // Media Projection Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .border(1.dp, Color(0xFF252630), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("TRANSMISSÃO DE TELA REMOTA", fontSize = 11.sp, color = Color(0xFF8E94A5), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "A transmissão exige autorização única. Clique abaixo para habilitar o streaming de tela no painel web.",
                        color = Color(0xFF8E94A5),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Button(
                        onClick = {
                            val intent = mediaProjectionManager.createScreenCaptureIntent()
                            screenCaptureLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasScreenCapturePerm) Color(0xFF1E3A24) else Color(0xFF252630)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Icon(
                                imageVector = if (hasScreenCapturePerm) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (hasScreenCapturePerm) Color(0xFF39FF14) else Color(0xFFFF9900),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (hasScreenCapturePerm) "Transmissão Autorizada!" else "Autorizar Captura de Tela",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionItem(
        title: String,
        granted: Boolean,
        onRequest: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 14.sp)
            
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Concedido",
                        tint = Color(0xFF39FF14),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Liberado", color = Color(0xFF39FF14), fontSize = 12.sp)
                }
            } else {
                TextButton(
                    onClick = onRequest,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Conceder", color = Color(0xFFFF2A85), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions(permissions: Array<String>, onSuccess: () -> Unit) {
        val ungranted = permissions.filter { !hasPermission(it) }
        if (ungranted.isEmpty()) {
            onSuccess()
        } else {
            requestPermissionsLauncher.launch(ungranted.toTypedArray())
        }
    }
}

// Custom theme for consistent dark dashboard aesthetic
@Composable
fun AndroidProtectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00D2FF),
            secondary = Color(0xFFFF2A85),
            background = Color(0xFF0A0B10),
            surface = Color(0xFF12141D)
        ),
        content = content
    )
}
