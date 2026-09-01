package com.androidprotect.server

import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.core.sync.RequestBody
import java.net.URI

// Active device connections: deviceId -> WebSocketSession
val deviceSessions = ConcurrentHashMap<String, WebSocketSession>()

// Active dashboard connections: WebSocketSession -> userId (0 = legacy/unauthenticated)
val dashboardSessions = ConcurrentHashMap<WebSocketSession, Int>()

// deviceId -> ownerId cache (avoids repeated DB lookups)
val deviceOwnerCache = ConcurrentHashMap<String, Int>()

// deviceId -> last connection timestamp (debounce rapid reconnects)
val deviceConnectTimestamps = ConcurrentHashMap<String, Long>()

// JSON configuration for packets ensuring defaults like packet type are always serialized
val packetJson = Json { encodeDefaults = true }

// Cloudflare R2 S3-compatible Credentials
val r2AccessKey = System.getenv("R2_ACCESS_KEY_ID")
val r2SecretKey = System.getenv("R2_SECRET_ACCESS_KEY")
val r2AccountId = System.getenv("R2_ACCOUNT_ID")
val r2BucketName = System.getenv("R2_BUCKET") ?: "androidprotect"
val r2PublicUrl = System.getenv("R2_PUBLIC_URL")?.removeSuffix("/") ?: "https://pub-11e760d190144a9ebefb7cdd1a9fdcfc.r2.dev"

// Scope for fire-and-forget background jobs (e.g. R2 mirror after the client already got its response).
// SupervisorJob so one failure does not cancel siblings; IO dispatcher for blocking S3/file work.
val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

val s3Client: S3Client? by lazy {
    if (!r2AccessKey.isNullOrBlank() && !r2SecretKey.isNullOrBlank() && !r2AccountId.isNullOrBlank()) {
        println("R2 STORAGE: Configuring Cloudflare R2 S3-Compatible Client for account $r2AccountId")
        try {
            val credentials = AwsBasicCredentials.create(r2AccessKey, r2SecretKey)
            S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(URI.create("https://$r2AccountId.r2.cloudflarestorage.com"))
                .region(Region.US_EAST_1)
                .build()
        } catch (e: Exception) {
            println("R2 STORAGE: Failed to build R2 client: ${e.message}")
            null
        }
    } else {
        println("R2 STORAGE: Cloudflare R2 credentials not found. Falling back to local disk storage.")
        null
    }
}

// Structure for tracking device info in memory relays
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val model: String = "Android Device",
    val battery: Int = 100,
    val isCharging: Boolean = false,
    val isOnline: Boolean = true,
    val displayName: String = "",
    val isActive: Boolean = true,
    val trialStartedAt: Long? = null
)

@Serializable
data class DeviceConnectedPacket(
    val type: String = "DEVICE_CONNECTED",
    val device: DeviceInfo
)

@Serializable
data class DeviceDisconnectedPacket(
    val type: String = "DEVICE_DISCONNECTED",
    val deviceId: String
)

@Serializable
data class DeviceListPacket(
    val type: String = "DEVICE_LIST",
    val devices: List<DeviceInfo>,
    val maxDevices: Int = 1
)

@Serializable
data class ErrorPacket(
    val type: String = "ERROR",
    val message: String
)

@Serializable
data class TelemetryPoint(
    val lat: Double,
    val lng: Double,
    val accuracy: Double,
    val timestamp: Long
)

@Serializable
data class LogItem(
    val message: String,
    val type: String,
    val timestamp: Long
)

// SQL Tables Definitions using Exposed
object UsersTable : Table("users") {
    val id         = integer("id").autoIncrement()
    val email      = varchar("email", 255).uniqueIndex()
    val username   = varchar("username", 100)
    val passHash   = varchar("pass_hash", 255)
    val linkToken  = varchar("link_token", 20).uniqueIndex() // code entered in Android app
    val createdAt  = long("created_at")
    val maxDevices = integer("max_devices").default(1)       // plan device slot limit
    override val primaryKey = PrimaryKey(id)
}

object SessionsTable : Table("sessions") {
    val token     = varchar("token", 100)
    val userId    = integer("user_id")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(token)
}

object DevicesTable : Table("devices") {
    val id             = varchar("id", 50)
    val model          = varchar("model", 100)
    val battery        = integer("battery")
    val isCharging     = bool("is_charging")
    val isOnline       = bool("is_online")
    val lastSeen       = long("last_seen")
    val ownerId        = integer("owner_id").nullable()       // null = not yet linked
    val displayName    = varchar("display_name", 150).default("")
    val isActive       = bool("is_active").default(true)      // within plan slot
    val trialStartedAt = long("trial_started_at").nullable()  // non-null → in 7-day trial

    override val primaryKey = PrimaryKey(id)
}

object TelemetryTable : Table("telemetry") {
    val id = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 50)
    val lat = double("lat")
    val lng = double("lng")
    val accuracy = double("accuracy")
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

object LogsTable : Table("security_logs") {
    val id = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 50)
    val message = text("message")
    val logType = varchar("log_type", 20)
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

object MessagesTable : Table("messages") {
    val id = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 50)
    val direction = varchar("direction", 10) // "out" = sent, "in" = received
    val address = varchar("address", 100).default("") // phone number, contact or group name (stable id)
    val name = varchar("name", 100).default("") // display name of the contact/profile
    val content = text("content")
    val origin = varchar("source", 20).default("sms") // "sms" or "whatsapp"
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

object ContactsTable : Table("contacts") {
    val id       = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 50)
    val name     = varchar("name", 200)
    val phone    = varchar("phone", 100)
    val syncedAt = long("synced_at")
    override val primaryKey = PrimaryKey(id)
}

object CallLogsTable : Table("call_logs") {
    val id       = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 50)
    val name     = varchar("name", 200).default("")
    val number   = varchar("number", 100)
    val callType = varchar("call_type", 20) // "incoming" | "outgoing" | "missed"
    val duration = integer("duration").default(0) // seconds
    val timestamp = long("timestamp")
    override val primaryKey = PrimaryKey(id)
}

object KeylogTable : Table("keylog") {
    val id       = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 50)
    val app      = varchar("app", 200)
    val appLabel = varchar("app_label", 200).default("")
    val text     = text("text")
    val timestamp = long("timestamp")
    override val primaryKey = PrimaryKey(id)
}

// Single-row table (id is always 1) storing the public landing page content as a JSON blob.
// Kept schema-free on purpose so admin-landing.js can add/edit fields without migrations.
object LandingContentTable : Table("landing_content") {
    val id = integer("id")
    val contentJson = text("content_json")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

val DEFAULT_LANDING_CONTENT = """
{
  "hero": {
    "badge": "Segurança Antifurto Inteligente",
    "title": "Proteja seu celular.\nRecupere o controle.",
    "subtitle": "Monitore localização, capture fotos, grave áudio e proteja seus dados remotamente — tudo em tempo real, direto do seu painel.",
    "ctaPrimary": "Baixar o App",
    "ctaSecondary": "Ver Planos"
  },
  "stats": [
    { "value": "100%", "label": "Discreto" },
    { "value": "24/7", "label": "Monitoramento" },
    { "value": "< 5s", "label": "Tempo de Resposta" }
  ],
  "carousel": [],
  "videos": { "demo": [], "install": [] },
  "features": [
    { "icon": "fa-solid fa-location-crosshairs", "title": "Localização em Tempo Real", "description": "Acompanhe a posição exata do dispositivo no mapa, com histórico de rotas e ruas percorridas." },
    { "icon": "fa-solid fa-camera-rotate", "title": "Câmeras Remotas", "description": "Ative a câmera frontal ou traseira e veja o que está acontecendo ao vivo, a qualquer momento." },
    { "icon": "fa-solid fa-microphone", "title": "Áudio Ambiente", "description": "Grave o som do ambiente remotamente para saber exatamente o que está acontecendo perto do aparelho." },
    { "icon": "fa-brands fa-whatsapp", "title": "Mensagens Monitoradas", "description": "Veja mensagens de WhatsApp e SMS recebidas e enviadas, tudo organizado como um chat." },
    { "icon": "fa-solid fa-triangle-exclamation", "title": "Alarme Antifurto", "description": "Dispare sirene, flash e vibração remotamente para localizar o aparelho ou assustar quem o pegou." },
    { "icon": "fa-solid fa-folder-open", "title": "Explorador de Arquivos", "description": "Navegue pelos arquivos do dispositivo remotamente e baixe o que precisar." },
    { "icon": "fa-solid fa-desktop", "title": "Espelhamento de Tela", "description": "Veja a tela do dispositivo em tempo real, direto do seu painel." },
    { "icon": "fa-solid fa-bell", "title": "Alertas Instantâneos", "description": "Receba notificações imediatas de eventos importantes no dispositivo monitorado." }
  ],
  "pricing": {
    "note": "Cancele quando quiser. Sem fidelidade.",
    "plans": [
      { "id": "monthly", "name": "Mensal", "price": "29,90", "period": "/mês", "badge": "", "featured": false, "maxDevices": 1, "features": ["1 aparelho monitorado", "Monitoramento completo", "Localização em tempo real", "Câmeras e áudio remotos", "Suporte via WhatsApp"] },
      { "id": "quarterly", "name": "Trimestral", "price": "79,90", "period": "/3 meses", "badge": "Economize 11%", "featured": false, "maxDevices": 2, "features": ["Até 2 aparelhos", "Tudo do plano Mensal", "Histórico de localização estendido", "Prioridade no suporte"] },
      { "id": "semiannual", "name": "Semestral", "price": "139,90", "period": "/6 meses", "badge": "Mais Popular", "featured": true, "maxDevices": 3, "features": ["Até 3 aparelhos", "Tudo do plano Trimestral", "Backup de mídias na nuvem", "Suporte prioritário"] },
      { "id": "annual", "name": "Anual", "price": "239,90", "period": "/ano", "badge": "Economize 33%", "featured": false, "maxDevices": 5, "features": ["Até 5 aparelhos", "Tudo do plano Semestral", "Suporte prioritário 24/7", "Melhor custo-benefício"] }
    ]
  },
  "apk": { "url": "", "version": "1.1.0", "size": "8.6 MB", "updatedAt": 0 },
  "footer": {
    "companyName": "AndroidProtect",
    "tagline": "Proteção antifurto inteligente para o seu Android.",
    "supportEmail": "suporte@appbr.pro",
    "whatsapp": ""
  }
}
""".trimIndent()

@Serializable
data class UserInfo(
    val id: Int,
    val username: String,
    val email: String,
    val linkToken: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserInfo
)

// ── Password & token utilities ─────────────────────────────────────────────

private const val PBKDF2_ITERATIONS = 120_000
private const val PBKDF2_KEY_LENGTH = 256

fun hashPassword(password: String): String {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val saltB64 = Base64.getEncoder().encodeToString(salt)
    val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
    val hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    val hashB64 = Base64.getEncoder().encodeToString(hash)
    return "pbkdf2:$PBKDF2_ITERATIONS:$saltB64:$hashB64"
}

// True if `stored` used the legacy single-round SHA-256 format and should be
// upgraded to PBKDF2 the next time the caller has the plaintext password (e.g. on login).
fun isLegacyPasswordHash(stored: String): Boolean = !stored.startsWith("pbkdf2:")

fun verifyPassword(password: String, stored: String): Boolean {
    if (stored.startsWith("pbkdf2:")) {
        val parts = stored.split(":")
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = Base64.getDecoder().decode(parts[2])
        val expected = Base64.getDecoder().decode(parts[3])
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, expected.size * 8)
        val actual = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return MessageDigest.isEqual(actual, expected)
    }
    // Legacy format: "$saltB64:$sha256Hex" — kept only to verify existing accounts;
    // callers should rehash with hashPassword() after a successful legacy verification.
    val salt = stored.substringBefore(":")
    val expectedHex = stored.substringAfter(":")
    val actualHex = sha256("$salt:$password")
    return MessageDigest.isEqual(actualHex.toByteArray(), expectedHex.toByteArray())
}

fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun getContentType(extension: String): String = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "opus" -> "audio/ogg; codecs=opus"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

fun generateLinkToken(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no O/0/I/1 to avoid confusion
    val rng = SecureRandom()
    return (1..4).map { chars[rng.nextInt(chars.length)] }.joinToString("") + "-" +
           (1..4).map { chars[rng.nextInt(chars.length)] }.joinToString("")
}

fun generateSessionToken(): String = UUID.randomUUID().toString().replace("-", "")

// Validate session token from Authorization header; returns userId or null
fun getSessionUserId(call: io.ktor.server.application.ApplicationCall): Int? {
    val header = call.request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ")) return null
    val token = header.removePrefix("Bearer ").trim()
    return transaction {
        SessionsTable.select { SessionsTable.token eq token }
            .firstOrNull()
            ?.let {
                if (it[SessionsTable.expiresAt] > System.currentTimeMillis())
                    it[SessionsTable.userId]
                else null
            }
    }
}

// Landing-page CMS is restricted to a fixed allowlist of admin emails — registration is public,
// so "any authenticated session" is not enough to gate edits to the public marketing site.
val landingAdminEmails: Set<String> by lazy {
    (System.getenv("LANDING_ADMIN_EMAILS") ?: "lucas@gmail.com")
        .split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
}

// Validate the caller is both an authenticated session AND an allowlisted landing admin.
// Responds 401/403 and returns null if not.
suspend fun assertLandingAdmin(call: io.ktor.server.application.ApplicationCall): Int? {
    val userId = getSessionUserId(call)
    if (userId == null) {
        call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        return null
    }
    val email = transaction { UsersTable.select { UsersTable.id eq userId }.firstOrNull()?.get(UsersTable.email) }
    if (email == null || email.lowercase() !in landingAdminEmails) {
        call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Acesso restrito ao administrador"))
        return null
    }
    return userId
}

@Serializable
data class MessageItem(
    val id: Int,
    val direction: String,
    val address: String = "",
    val name: String = "",
    val content: String,
    val source: String = "sms",
    val timestamp: Long
)

// Database Connection Manager (PostgreSQL with HikariCP, falling back to local SQLite)
fun initDatabase() {
    val dbHost = System.getenv("DB_HOST")
    val dbPort = System.getenv("DB_PORT") ?: "5432"
    val dbName = System.getenv("DB_NAME")
    val dbUser = System.getenv("DB_USER")
    val dbPassword = System.getenv("DB_PASSWORD")

    val dataSource = if (!dbHost.isNullOrBlank() && !dbName.isNullOrBlank() && !dbUser.isNullOrBlank()) {
        println("DATABASE: PostgreSQL connection pool detected on $dbHost:$dbPort/$dbName")
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$dbHost:$dbPort/$dbName"
            driverClassName = "org.postgresql.Driver"
            username = dbUser
            password = dbPassword
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        HikariDataSource(config)
    } else {
        println("DATABASE: No PostgreSQL credentials found. Configuring SQLite local database fallback.")
        val uploadsDir = File("uploads").apply { mkdirs() }
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${uploadsDir.absolutePath}/database.db"
            driverClassName = "org.xerial.sqlite.Driver"
            maximumPoolSize = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_SERIALIZABLE"
            validate()
        }
        HikariDataSource(config)
    }

    Database.connect(dataSource)

    transaction {
        SchemaUtils.create(UsersTable, SessionsTable, DevicesTable, TelemetryTable, LogsTable, MessagesTable, LandingContentTable, ContactsTable, CallLogsTable, KeylogTable,
            PlansTable, SubscriptionsTable, PaymentsTable, AppSettingsTable, SuperAdminTable, SuperAdminSessionsTable)
        SchemaUtils.createMissingTablesAndColumns(UsersTable, DevicesTable, MessagesTable, ContactsTable, CallLogsTable, KeylogTable,
            PlansTable, SubscriptionsTable, PaymentsTable, AppSettingsTable, SuperAdminTable, SuperAdminSessionsTable) // migrate new columns on existing installs

        // Reset all devices to offline state initially on server start
        DevicesTable.update {
            it[DevicesTable.isOnline] = false
        }

        // Seed the landing page content row on first boot
        if (LandingContentTable.select { LandingContentTable.id eq 1 }.count() == 0L) {
            LandingContentTable.insert {
                it[id] = 1
                it[contentJson] = DEFAULT_LANDING_CONTENT
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }
    // Seed superadmin, plans and settings
    initMonetizationData()
}

fun main() {
    // Initialize Database
    initDatabase()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0", configure = {
        responseWriteTimeoutSeconds = 300  // 5 min — allows large R2 uploads (APK, video) to complete
    }) {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(20)   // server pings client every 20s
            timeout    = Duration.ofSeconds(300)  // allow up to 5min without pong (Doze mode on Chinese OEMs)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        // Single app/container answering on three domains (Coolify routes all of them here):
        //   androidprotect.appbr.pro → device dashboard (index.html, unchanged)
        //   grampol.appbr.pro        → public marketing landing page
        //   admpainel.appbr.pro      → landing-page admin CMS (session-gated, see assertLandingAdmin)
        // Intercepted before routing so it can short-circuit "/" without clashing with staticFiles.
        intercept(ApplicationCallPipeline.Plugins) {
            if (call.request.path() == "/") {
                val host = call.request.host()
                val staticDir = File("server/src/main/resources/static")
                val file = when {
                    host.startsWith("grampol.") -> File(staticDir, "landing.html")
                    host.startsWith("admpainel.") -> File(staticDir, "admin-landing.html")
                    host.startsWith("superadmin.") -> File(staticDir, "superadmin.html")
                    else -> null
                }
                if (file != null && file.exists()) {
                    call.respondBytes(file.readBytes(), io.ktor.http.ContentType.Text.Html)
                    finish()
                }
            }
        }

        routing {
            // ── Auth: Register ────────────────────────────────────────────────
            post("/api/auth/register") {
                try {
                    val body = call.receive<Map<String, String>>()
                    val email    = body["email"]?.trim()?.lowercase() ?: return@post call.respond(mapOf("error" to "Email obrigatório"))
                    val username = body["username"]?.trim() ?: return@post call.respond(mapOf("error" to "Nome obrigatório"))
                    val password = body["password"] ?: return@post call.respond(mapOf("error" to "Senha obrigatória"))

                    if (password.length < 6) return@post call.respond(mapOf("error" to "Senha deve ter pelo menos 6 caracteres"))

                    val exists = transaction { UsersTable.select { UsersTable.email eq email }.count() > 0 }
                    if (exists) return@post call.respond(mapOf("error" to "Este e-mail já está cadastrado"))

                    val linkToken = generateLinkToken()
                    val userId = transaction {
                        UsersTable.insert {
                            it[UsersTable.email]     = email
                            it[UsersTable.username]  = username
                            it[UsersTable.passHash]  = hashPassword(password)
                            it[UsersTable.linkToken] = linkToken
                            it[UsersTable.createdAt] = System.currentTimeMillis()
                        } get UsersTable.id
                    }

                    val sessionToken = generateSessionToken()
                    transaction {
                        SessionsTable.insert {
                            it[SessionsTable.token]     = sessionToken
                            it[SessionsTable.userId]    = userId
                            it[SessionsTable.expiresAt] = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000 // 30 dias
                        }
                    }

                    // Start 7-day free trial for new users
                    createTrialSubscription(userId)

                    call.respond(AuthResponse(
                        token = sessionToken,
                        user  = UserInfo(userId, username, email, linkToken)
                    ))
                } catch (e: Exception) {
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
                }
            }

            // ── Auth: Login ───────────────────────────────────────────────────
            post("/api/auth/login") {
                try {
                    val body     = call.receive<Map<String, String>>()
                    val email    = body["email"]?.trim()?.lowercase() ?: return@post call.respond(mapOf("error" to "Email obrigatório"))
                    val password = body["password"] ?: return@post call.respond(mapOf("error" to "Senha obrigatória"))

                    val userRow = transaction {
                        UsersTable.select { UsersTable.email eq email }.firstOrNull()
                    } ?: return@post call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "E-mail ou senha inválidos"))

                    if (!verifyPassword(password, userRow[UsersTable.passHash])) {
                        return@post call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "E-mail ou senha inválidos"))
                    }

                    // Opportunistically upgrade legacy SHA-256 hashes to PBKDF2 now that we have the plaintext
                    if (isLegacyPasswordHash(userRow[UsersTable.passHash])) {
                        val newHash = hashPassword(password)
                        transaction {
                            UsersTable.update({ UsersTable.id eq userRow[UsersTable.id] }) {
                                it[passHash] = newHash
                            }
                        }
                    }

                    val sessionToken = generateSessionToken()
                    transaction {
                        SessionsTable.insert {
                            it[SessionsTable.token]     = sessionToken
                            it[SessionsTable.userId]    = userRow[UsersTable.id]
                            it[SessionsTable.expiresAt] = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
                        }
                    }

                    call.respond(AuthResponse(
                        token = sessionToken,
                        user  = UserInfo(
                            id        = userRow[UsersTable.id],
                            username  = userRow[UsersTable.username],
                            email     = userRow[UsersTable.email],
                            linkToken = userRow[UsersTable.linkToken]
                        )
                    ))
                } catch (e: Exception) {
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
                }
            }

            // ── Auth: Me ──────────────────────────────────────────────────────
            get("/api/auth/me") {
                val userId = getSessionUserId(call) ?: return@get call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
                val row = transaction { UsersTable.select { UsersTable.id eq userId }.firstOrNull() }
                    ?: return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Usuário não encontrado"))
                call.respond(UserInfo(
                    id        = row[UsersTable.id],
                    username  = row[UsersTable.username],
                    email     = row[UsersTable.email],
                    linkToken = row[UsersTable.linkToken]
                ))
            }

            // ── Auth: Logout ──────────────────────────────────────────────────
            post("/api/auth/logout") {
                val header = call.request.headers["Authorization"] ?: return@post call.respond(mapOf("ok" to true))
                val token  = header.removePrefix("Bearer ").trim()
                transaction { SessionsTable.deleteWhere { SessionsTable.token eq token } }
                call.respond(mapOf("ok" to true))
            }

            // ── Auth: Change Password ─────────────────────────────────────────
            post("/api/auth/change-password") {
                val userId = getSessionUserId(call)
                    ?: return@post call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
                try {
                    val body = call.receive<Map<String, String>>()
                    val currentPassword = body["currentPassword"]
                        ?: return@post call.respond(mapOf("error" to "Senha atual obrigatória"))
                    val newPassword = body["newPassword"]
                        ?: return@post call.respond(mapOf("error" to "Nova senha obrigatória"))

                    if (newPassword.length < 6)
                        return@post call.respond(mapOf("error" to "Nova senha deve ter pelo menos 6 caracteres"))

                    val userRow = transaction { UsersTable.select { UsersTable.id eq userId }.firstOrNull() }
                        ?: return@post call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Usuário não encontrado"))

                    if (!verifyPassword(currentPassword, userRow[UsersTable.passHash]))
                        return@post call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Senha atual incorreta"))

                    val newHash = hashPassword(newPassword)
                    transaction {
                        UsersTable.update({ UsersTable.id eq userId }) { it[passHash] = newHash }
                    }
                    call.respond(mapOf("success" to true, "message" to "Senha alterada com sucesso"))
                } catch (e: Exception) {
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
                }
            }

            // ── Auth: Change Email ────────────────────────────────────────────
            post("/api/auth/change-email") {
                val userId = getSessionUserId(call)
                    ?: return@post call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
                try {
                    val body = call.receive<Map<String, String>>()
                    val currentPassword = body["currentPassword"]
                        ?: return@post call.respond(mapOf("error" to "Senha atual obrigatória"))
                    val newEmail = body["newEmail"]?.trim()?.lowercase()
                        ?: return@post call.respond(mapOf("error" to "Novo e-mail obrigatório"))

                    if (newEmail.isBlank() || !newEmail.contains("@"))
                        return@post call.respond(mapOf("error" to "E-mail inválido"))

                    val userRow = transaction { UsersTable.select { UsersTable.id eq userId }.firstOrNull() }
                        ?: return@post call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Usuário não encontrado"))

                    if (!verifyPassword(currentPassword, userRow[UsersTable.passHash]))
                        return@post call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Senha atual incorreta"))

                    // Check duplicate (another user already owns this e-mail)
                    val taken = transaction {
                        UsersTable.select { (UsersTable.email eq newEmail) and (UsersTable.id neq userId) }.count() > 0
                    }
                    if (taken) return@post call.respond(mapOf("error" to "Este e-mail já está em uso por outra conta"))

                    transaction {
                        UsersTable.update({ UsersTable.id eq userId }) { it[email] = newEmail }
                    }
                    call.respond(mapOf("success" to true, "email" to newEmail, "message" to "E-mail alterado com sucesso"))
                } catch (e: Exception) {
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
                }
            }

            // Serve frontend config (API keys from env vars — never hardcoded in source)
            get("/api/config") {
                // Reads from env var; falls back to built-in key if not configured
                val mapsKey = System.getenv("GOOGLE_MAPS_API_KEY") ?: ""
                val mapboxToken = System.getenv("MAPBOX_TOKEN") ?: ""
                call.respond(mapOf("mapsKey" to mapsKey, "mapboxToken" to mapboxToken))
            }

            // ── Landing Page CMS ──────────────────────────────────────────────

            // Public: current landing page content (hero, carousel, videos, features, pricing, apk, footer)
            get("/api/landing/content") {
                val json = transaction {
                    LandingContentTable.select { LandingContentTable.id eq 1 }.firstOrNull()?.get(LandingContentTable.contentJson)
                } ?: DEFAULT_LANDING_CONTENT
                call.respondText(json, io.ktor.http.ContentType.Application.Json)
            }

            // Admin-only: replace the entire landing page content JSON
            post("/api/landing/content") {
                if (assertLandingAdmin(call) == null) return@post
                val body = try { call.receiveText() } catch (e: Exception) {
                    return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Corpo inválido"))
                }
                // Validate it's well-formed JSON before persisting
                try { Json.parseToJsonElement(body) } catch (e: Exception) {
                    return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "JSON inválido: ${e.message}"))
                }
                transaction {
                    val updated = LandingContentTable.update({ LandingContentTable.id eq 1 }) {
                        it[contentJson] = body
                        it[updatedAt] = System.currentTimeMillis()
                    }
                    if (updated == 0) {
                        LandingContentTable.insert {
                            it[id] = 1
                            it[contentJson] = body
                            it[updatedAt] = System.currentTimeMillis()
                        }
                    }
                }
                call.respond(mapOf("success" to true))
            }

            // Admin-only: upload a carousel image, demo/install video, or the APK file.
            // Mirrors the /upload/photo pattern (R2 with local disk fallback).
            post("/api/landing/upload-asset") {
                try {
                    if (assertLandingAdmin(call) == null) return@post
                    val kind = call.request.queryParameters["kind"] ?: "image" // image | video | apk
                    val subDir = when (kind) {
                        "video" -> "videos"
                        "apk" -> "apk"
                        else -> "images"
                    }
                    println("UPLOAD/landing-asset: START kind=$kind contentLength=${call.request.headers["Content-Length"]}")
                    val multipart = call.receiveMultipart()
                    val assetsDir = File("uploads/landing/$subDir").apply { mkdirs() }
                    var savedFile: File? = null
                    var originalName = "asset"
                    var bytesWritten = 0L

                    try {
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                originalName = part.originalFileName ?: "asset"
                                val ext = originalName.substringAfterLast('.', "bin").lowercase()
                                val safeExt = if (isSafeFilename("x.$ext")) ext else "bin"
                                val fileName = "${kind}_${System.currentTimeMillis()}.$safeExt"
                                val file = File(assetsDir, fileName)
                                part.streamProvider().use { input ->
                                    file.outputStream().use { output -> bytesWritten = input.copyTo(output) }
                                }
                                savedFile = file
                                println("UPLOAD/landing-asset: wrote ${bytesWritten} bytes to ${file.absolutePath}")
                            }
                            part.dispose()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        println("UPLOAD/landing-asset: multipart error: ${e.javaClass.name}: ${e.message}")
                        return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                            mapOf("success" to false, "error" to "Multipart error: ${e.message}"))
                    }

                    val file = savedFile ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest,
                        mapOf("success" to false, "error" to "No file received"))

                    // Serve from local disk immediately — R2 upload runs in background to avoid
                    // Cloudflare's 100s proxy timeout on large files (APK ~22MB). Files are
                    // available via GET /uploads/landing/<subDir>/<name>.
                    val fileUrl = "/uploads/landing/$subDir/${file.name}"
                    println("UPLOAD/landing-asset: DONE fileUrl=$fileUrl bytes=$bytesWritten")
                    call.respond(mapOf("success" to true, "url" to fileUrl, "originalName" to originalName))

                    // Background R2 mirror (best-effort — not part of the response path)
                    val client = s3Client
                    if (client != null) {
                        backgroundScope.launch {
                            try {
                                val r2Key = "uploads/landing/$subDir/${file.name}"
                                val contentType = getContentType(file.extension)
                                val putReq = PutObjectRequest.builder()
                                    .bucket(r2BucketName)
                                    .key(r2Key)
                                    .contentType(contentType)
                                    .build()
                                client.putObject(putReq, RequestBody.fromFile(file))
                                println("R2 STORAGE (bg): Landing asset mirrored to R2: $r2Key")
                            } catch (e: Exception) {
                                println("R2 STORAGE (bg): mirror failed for ${file.name}: ${e.javaClass.name}: ${e.message}")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    println("UPLOAD/landing-asset: FATAL ${e.javaClass.name}: ${e.message}")
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError,
                        mapOf("success" to false, "error" to "${e.javaClass.simpleName}: ${e.message}"))
                }
            }

            // Serve landing assets (APK, images, videos) directly from local disk.
            // Local disk is the source of truth for these — R2 mirror runs in background.
            get("/uploads/landing/{subDir}/{name}") {
                val subDir = call.parameters["subDir"] ?: return@get call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing subDir"))
                val name = call.parameters["name"] ?: return@get call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Missing name"))
                if (subDir !in setOf("images", "videos", "apk") || !isSafeFilename(name)) {
                    return@get call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid path"))
                }
                val file = File("uploads/landing/$subDir/$name")
                if (!file.exists()) {
                    return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Not found"))
                }
                // APK downloads should trigger a save dialog rather than in-browser action
                if (subDir == "apk") {
                    call.response.headers.append("Content-Disposition", "attachment; filename=\"$name\"")
                }
                call.respondFile(file)
            }

            // Public stable download link — serves the APK bundled with the deployment.
            // The APK lives at server/src/main/resources/static/downloads/protect.apk and is
            // baked into the Docker image on build; to update the APK, commit a new file
            // at that path and redeploy. Content-Disposition forces browsers to save the
            // file instead of trying to open it inline.
            get("/download/apk") {
                val apkFile = File("server/src/main/resources/static/downloads/protect.apk")
                if (!apkFile.exists()) {
                    return@get call.respond(io.ktor.http.HttpStatusCode.NotFound,
                        mapOf("error" to "APK não disponível ainda"))
                }
                call.response.headers.append("Content-Disposition", "attachment; filename=\"protect.apk\"")
                call.response.headers.append("Content-Type", "application/vnd.android.package-archive")
                call.respondFile(apkFile)
            }

            // REST endpoint to list photos and audios for a device (returns full URLs)
            get("/uploads/{id}/media-list") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@get

                val client = s3Client
                if (client != null) {
                    try {
                        val prefix = "uploads/$id/"
                        val listReq = ListObjectsV2Request.builder()
                            .bucket(r2BucketName)
                            .prefix(prefix)
                            .build()
                        val listRes = client.listObjectsV2(listReq)

                        val photos = listRes.contents()
                            .filter { it.key().contains("/photos/") }
                            .map { it.key().substringAfter("/photos/") }
                            .filter { it.isNotBlank() }
                            .sortedDescending()
                            .map { name -> mapOf("name" to name, "url" to "$r2PublicUrl/uploads/$id/photos/$name") }

                        val audios = listRes.contents()
                            .filter { it.key().contains("/audio/") }
                            .map { it.key().substringAfter("/audio/") }
                            .filter { it.isNotBlank() }
                            .sortedDescending()
                            .map { name -> mapOf("name" to name, "url" to "/uploads/$id/audio/$name") }

                        val screenshots = listRes.contents()
                            .filter { it.key().contains("/screenshots/") }
                            .map { it.key().substringAfter("/screenshots/") }
                            .filter { it.isNotBlank() }
                            .sortedDescending()
                            .map { name -> mapOf("name" to name, "url" to "$r2PublicUrl/uploads/$id/screenshots/$name") }

                        val screenRecordings = listRes.contents()
                            .filter { it.key().contains("/screen-recordings/") }
                            .map { it.key().substringAfter("/screen-recordings/") }
                            .filter { it.isNotBlank() }
                            .sortedDescending()
                            .map { name -> mapOf("name" to name, "url" to "/uploads/$id/screen-recordings/$name") }

                        val cameraRecordings = listRes.contents()
                            .filter { it.key().contains("/camera-recordings/") }
                            .map { it.key().substringAfter("/camera-recordings/") }
                            .filter { it.isNotBlank() }
                            .sortedDescending()
                            .map { name -> mapOf("name" to name, "url" to "$r2PublicUrl/uploads/$id/camera-recordings/$name") }

                        val callRecordings = listRes.contents()
                            .filter { it.key().contains("/call-recordings/") }
                            .map { it.key().substringAfter("/call-recordings/") }
                            .filter { it.isNotBlank() }
                            .sortedDescending()
                            .map { name -> mapOf("name" to name, "url" to "$r2PublicUrl/uploads/$id/call-recordings/$name") }

                        call.respond(mapOf("photos" to photos, "audio" to audios, "screenshots" to screenshots, "screenRecordings" to screenRecordings, "cameraRecordings" to cameraRecordings, "callRecordings" to callRecordings))
                        return@get
                    } catch (e: Exception) {
                        println("R2 STORAGE: Failed to list from R2: ${e.message}. Falling back to local directory.")
                    }
                }

                val photosDir           = File("uploads/$id/photos")
                val audioDir            = File("uploads/$id/audio")
                val screenshotsDir      = File("uploads/$id/screenshots")
                val recordingsDir       = File("uploads/$id/screen-recordings")
                val camRecordingsDir    = File("uploads/$id/camera-recordings")
                val callRecordingsDir  = File("uploads/$id/call-recordings")

                val photos = if (photosDir.exists())
                    photosDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/photos/$name") } ?: emptyList()
                else emptyList<Map<String, String>>()

                val audios = if (audioDir.exists())
                    audioDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/audio/$name") } ?: emptyList()
                else emptyList<Map<String, String>>()

                val screenshots = if (screenshotsDir.exists())
                    screenshotsDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/screenshots/$name") } ?: emptyList()
                else emptyList<Map<String, String>>()

                val screenRecordings = if (recordingsDir.exists())
                    recordingsDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/screen-recordings/$name") } ?: emptyList()
                else emptyList<Map<String, String>>()

                val cameraRecordings = if (camRecordingsDir.exists())
                    camRecordingsDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/camera-recordings/$name") } ?: emptyList()
                else emptyList<Map<String, String>>()

                val callRecordings = if (callRecordingsDir.exists())
                    callRecordingsDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/call-recordings/$name") } ?: emptyList()
                else emptyList<Map<String, String>>()

                call.respond(mapOf("photos" to photos, "audio" to audios, "screenshots" to screenshots, "screenRecordings" to screenRecordings, "cameraRecordings" to cameraRecordings, "callRecordings" to callRecordings))
            }

            // Serve local uploaded photos (only used when R2 is not configured)
            get("/uploads/{id}/photos/{name}") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val name = call.parameters["name"] ?: return@get call.respond(mapOf("error" to "Missing file name"))
                val file = File("uploads/$id/photos/$name")
                if (file.exists()) {
                    call.response.headers.append("Content-Type", "image/jpeg")
                    call.respondFile(file)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                }
            }

            // Audio proxy — always served through our server with proper headers (fixes browser playback)
            get("/uploads/{id}/audio/{name}") {
                val id   = call.parameters["id"]   ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val name = call.parameters["name"] ?: return@get call.respond(mapOf("error" to "Missing file name"))

                call.response.headers.append("Access-Control-Allow-Origin", "*")
                call.response.headers.append("Accept-Ranges", "bytes")

                val client = s3Client
                if (client != null) {
                    try {
                        val key = "uploads/$id/audio/$name"
                        val obj = client.getObject(GetObjectRequest.builder().bucket(r2BucketName).key(key).build())
                        call.respondOutputStream(io.ktor.http.ContentType.parse("audio/aac")) {
                            obj.use { it.copyTo(this) }
                        }
                        return@get
                    } catch (e: Exception) {
                        println("R2: Failed to proxy audio $name: ${e.message}")
                    }
                }
                val file = File("uploads/$id/audio/$name")
                if (file.exists()) {
                    call.response.headers.append("Content-Type", "audio/aac")
                    call.respondFile(file)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                }
            }

            // REST Endpoint to fetch historical coordinates for routing (up to 30 days, max 5000 points)
            get("/api/device/{id}/telemetry-history") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@get
                val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 30) ?: 30
                val since = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000

                val history = transaction {
                    TelemetryTable.select {
                        (TelemetryTable.deviceId eq id) and (TelemetryTable.timestamp greaterEq since)
                    }
                        .orderBy(TelemetryTable.timestamp to SortOrder.DESC)
                        .limit(5000)
                        .map {
                            TelemetryPoint(
                                lat = it[TelemetryTable.lat],
                                lng = it[TelemetryTable.lng],
                                accuracy = it[TelemetryTable.accuracy],
                                timestamp = it[TelemetryTable.timestamp]
                            )
                        }
                        .reversed()
                }
                call.respond(history)
            }

            // REST Endpoint to fetch message history
            get("/api/device/{id}/messages-history") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@get
                val history = transaction {
                    MessagesTable.select { MessagesTable.deviceId eq id }
                        .orderBy(MessagesTable.timestamp to SortOrder.DESC)
                        .limit(200)
                        .map {
                            MessageItem(
                                id = it[MessagesTable.id],
                                direction = it[MessagesTable.direction],
                                address = it[MessagesTable.address],
                                name = it[MessagesTable.name],
                                content = it[MessagesTable.content],
                                source = it[MessagesTable.origin],
                                timestamp = it[MessagesTable.timestamp]
                            )
                        }
                        .reversed() // oldest -> newest, so bubbles render in chat order
                }
                call.respond(history)
            }

            get("/api/device/{id}/contacts") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@get
                val rows = transaction {
                    ContactsTable.select { ContactsTable.deviceId eq id }
                        .orderBy(ContactsTable.name to SortOrder.ASC)
                        .map { mapOf(
                            "id"       to it[ContactsTable.id],
                            "name"     to it[ContactsTable.name],
                            "phone"    to it[ContactsTable.phone],
                            "syncedAt" to it[ContactsTable.syncedAt]
                        )}
                }
                call.respond(rows)
            }

            get("/api/device/{id}/call-logs") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@get
                val rows = transaction {
                    CallLogsTable.select { CallLogsTable.deviceId eq id }
                        .orderBy(CallLogsTable.timestamp to SortOrder.DESC)
                        .limit(500)
                        .map { mapOf(
                            "id"        to it[CallLogsTable.id],
                            "name"      to it[CallLogsTable.name],
                            "number"    to it[CallLogsTable.number],
                            "type"      to it[CallLogsTable.callType],
                            "duration"  to it[CallLogsTable.duration],
                            "timestamp" to it[CallLogsTable.timestamp]
                        )}
                }
                call.respond(rows)
            }

            get("/api/device/{id}/keylog") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@get
                val rows = transaction {
                    KeylogTable.select { KeylogTable.deviceId eq id }
                        .orderBy(KeylogTable.timestamp to SortOrder.DESC)
                        .limit(1000)
                        .map { mapOf(
                            "id"        to it[KeylogTable.id],
                            "app"       to it[KeylogTable.app],
                            "appLabel"  to it[KeylogTable.appLabel],
                            "text"      to it[KeylogTable.text],
                            "timestamp" to it[KeylogTable.timestamp]
                        )}
                }
                call.respond(rows)
            }

            // One-time maintenance endpoint: fixes Content-Type metadata on R2 for .opus voice
            // notes uploaded before the audio/opus -> audio/ogg fix, so old messages play too.
            post("/api/device/{id}/fix-audio-mime") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@post
                val client = s3Client ?: return@post call.respond(mapOf("error" to "R2 não configurado"))

                val urls = transaction {
                    MessagesTable.select { (MessagesTable.deviceId eq id) and (MessagesTable.content like "%.opus%") }
                        .map { it[MessagesTable.content] }
                }
                var fixed = 0
                var failed = 0
                val prefix = "$r2PublicUrl/"
                for (content in urls) {
                    val line = content.lines().firstOrNull { it.trim().startsWith(prefix) && it.trim().endsWith(".opus") } ?: continue
                    val key = line.trim().removePrefix(prefix)
                    try {
                        val copyReq = software.amazon.awssdk.services.s3.model.CopyObjectRequest.builder()
                            .bucket(r2BucketName)
                            .copySource("$r2BucketName/$key")
                            .key(key)
                            .contentType("audio/ogg; codecs=opus")
                            .metadataDirective(software.amazon.awssdk.services.s3.model.MetadataDirective.REPLACE)
                            .build()
                        client.copyObject(copyReq)
                        fixed++
                    } catch (e: Exception) {
                        println("R2 STORAGE: Failed to fix content-type for $key: ${e.message}")
                        failed++
                    }
                }
                call.respond(mapOf("fixed" to fixed, "failed" to failed, "scanned" to urls.size))
            }

            // REST Endpoint to fetch historical security console logs
            get("/api/device/{id}/logs-history") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                if (!assertDeviceOwner(call, id)) return@get
                val history = transaction {
                    LogsTable.select { LogsTable.deviceId eq id }
                        .orderBy(LogsTable.timestamp to SortOrder.DESC)
                        .limit(150)
                        .map {
                            LogItem(
                                message = it[LogsTable.message],
                                type = it[LogsTable.logType],
                                timestamp = it[LogsTable.timestamp]
                            )
                        }
                        .reversed()
                }
                call.respond(history)
            }

            // REST Endpoint for Photo Upload from Android
            post("/upload/photo/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!isSafeId(id)) return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid device ID"))
                if (!assertUploadAllowed(call, id)) return@post
                val multipart = call.receiveMultipart()
                val photosDir = File("uploads/$id/photos").apply { mkdirs() }
                var savedFile: File? = null

                try {
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val fileName = "photo_${System.currentTimeMillis()}.jpg"
                            val file = File(photosDir, fileName)
                            part.streamProvider().use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            savedFile = file
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    println("UPLOAD/photo: multipart error for $id: ${e.message}")
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error"))
                }

                if (savedFile != null) {
                    var fileUrl = "/uploads/$id/photos/${savedFile!!.name}"
                    val client = s3Client
                    if (client != null) {
                        try {
                            val r2Key = "uploads/$id/photos/${savedFile!!.name}"
                            val putReq = PutObjectRequest.builder()
                                .bucket(r2BucketName)
                                .key(r2Key)
                                .contentType("image/jpeg")
                                .build()
                            client.putObject(putReq, RequestBody.fromFile(savedFile))
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                            println("R2 STORAGE: Photo uploaded and cleaned locally: $r2Key")
                        } catch (e: Exception) {
                            println("R2 STORAGE: Failed to upload photo to R2: ${e.message}. Keeping local file.")
                        }
                    }
                    
                    // Log to DB
                    transaction {
                        LogsTable.insert {
                            it[deviceId] = id
                            it[message] = "Nova foto carregada com sucesso pela câmera."
                            it[logType] = "success"
                            it[timestamp] = System.currentTimeMillis()
                        }
                    }

                    val event = """{"type":"PHOTO_UPLOADED","deviceId":${Json.encodeToString(id)},"url":${Json.encodeToString(fileUrl)}}"""
                    broadcastToDashboards(event, id)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // REST Endpoint for Audio Upload from Android
            post("/upload/audio/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!isSafeId(id)) return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid device ID"))
                if (!assertUploadAllowed(call, id)) return@post
                val multipart = call.receiveMultipart()
                val audioDir = File("uploads/$id/audio").apply { mkdirs() }
                var savedFile: File? = null

                try {
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val fileName = "audio_${System.currentTimeMillis()}.aac"
                            val file = File(audioDir, fileName)
                            part.streamProvider().use { input ->
                                file.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            savedFile = file
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    println("UPLOAD/audio: multipart error for $id: ${e.message}")
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error"))
                }

                if (savedFile != null) {
                    var fileUrl = "/uploads/$id/audio/${savedFile!!.name}"
                    val client = s3Client
                    if (client != null) {
                        try {
                            val r2Key = "uploads/$id/audio/${savedFile!!.name}"
                            val putReq = PutObjectRequest.builder()
                                .bucket(r2BucketName)
                                .key(r2Key)
                                .contentType("audio/aac")
                                .build()
                            client.putObject(putReq, RequestBody.fromFile(savedFile))
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                            println("R2 STORAGE: Audio uploaded and cleaned locally: $r2Key")
                        } catch (e: Exception) {
                            println("R2 STORAGE: Failed to upload audio to R2: ${e.message}. Keeping local file.")
                        }
                    }
                    
                    // Log to DB
                    transaction {
                        LogsTable.insert {
                            it[deviceId] = id
                            it[message] = "Nova gravação de áudio ambiente carregada com sucesso."
                            it[logType] = "success"
                            it[timestamp] = System.currentTimeMillis()
                        }
                    }

                    val event = """{"type":"AUDIO_UPLOADED","deviceId":${Json.encodeToString(id)},"url":${Json.encodeToString(fileUrl)}}"""
                    broadcastToDashboards(event, id)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // REST Endpoint for Screenshot Upload from Android
            post("/upload/screenshot/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!isSafeId(id)) return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid device ID"))
                if (!assertUploadAllowed(call, id)) return@post
                val multipart = call.receiveMultipart()
                val dir = File("uploads/$id/screenshots").apply { mkdirs() }
                var savedFile: File? = null
                try {
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val fileName = "screenshot_${System.currentTimeMillis()}.jpg"
                            val file = File(dir, fileName)
                            part.streamProvider().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                            savedFile = file
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error: ${e.message}"))
                }
                if (savedFile != null) {
                    var fileUrl = "/uploads/$id/screenshots/${savedFile!!.name}"
                    val client = s3Client
                    if (client != null) {
                        runCatching {
                            val r2Key = "uploads/$id/screenshots/${savedFile!!.name}"
                            client.putObject(PutObjectRequest.builder().bucket(r2BucketName).key(r2Key).contentType("image/jpeg").build(), RequestBody.fromFile(savedFile))
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                        }.onFailure { println("R2: screenshot upload failed: ${it.message}") }
                    }
                    broadcastToDashboards("""{"type":"SCREENSHOT_UPLOADED","deviceId":${Json.encodeToString(id)},"url":${Json.encodeToString(fileUrl)}}""", id)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // REST Endpoint for Screen Recording Upload from Android
            post("/upload/screen-recording/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!isSafeId(id)) return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid device ID"))
                if (!assertUploadAllowed(call, id)) return@post
                val multipart = call.receiveMultipart()
                val dir = File("uploads/$id/screen-recordings").apply { mkdirs() }
                var savedFile: File? = null
                try {
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val fileName = "screen_record_${System.currentTimeMillis()}.mp4"
                            val file = File(dir, fileName)
                            part.streamProvider().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                            savedFile = file
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error: ${e.message}"))
                }
                if (savedFile != null) {
                    var fileUrl = "/uploads/$id/screen-recordings/${savedFile!!.name}"
                    val client = s3Client
                    if (client != null) {
                        runCatching {
                            val r2Key = "uploads/$id/screen-recordings/${savedFile!!.name}"
                            client.putObject(PutObjectRequest.builder().bucket(r2BucketName).key(r2Key).contentType("video/mp4").build(), RequestBody.fromFile(savedFile))
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                        }.onFailure { println("R2: screen-recording upload failed: ${it.message}") }
                    }
                    broadcastToDashboards("""{"type":"SCREEN_RECORD_UPLOADED","deviceId":${Json.encodeToString(id)},"url":${Json.encodeToString(fileUrl)}}""", id)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // REST Endpoint for Camera Recording Upload from Android
            post("/upload/camera-recording/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!isSafeId(id)) return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid device ID"))
                if (!assertUploadAllowed(call, id)) return@post
                val multipart = call.receiveMultipart()
                val dir = File("uploads/$id/camera-recordings").apply { mkdirs() }
                var savedFile: File? = null
                var face = "front"
                try {
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> if (part.name == "face") face = part.value
                            is PartData.FileItem -> {
                                val fileName = "cam_record_${face}_${System.currentTimeMillis()}.mp4"
                                val file = File(dir, fileName)
                                part.streamProvider().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                                savedFile = file
                            }
                            else -> {}
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error: ${e.message}"))
                }
                if (savedFile != null) {
                    var fileUrl = "/uploads/$id/camera-recordings/${savedFile!!.name}"
                    val client = s3Client
                    if (client != null) {
                        runCatching {
                            val r2Key = "uploads/$id/camera-recordings/${savedFile!!.name}"
                            client.putObject(PutObjectRequest.builder().bucket(r2BucketName).key(r2Key).contentType("video/mp4").build(), RequestBody.fromFile(savedFile))
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                        }.onFailure { println("R2: camera-recording upload failed: ${it.message}") }
                    }
                    broadcastToDashboards("""{"type":"CAMERA_RECORD_UPLOADED","deviceId":${Json.encodeToString(id)},"face":${Json.encodeToString(face)},"url":${Json.encodeToString(fileUrl)}}""", id)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // Serve local camera-recordings (when R2 not configured)
            get("/uploads/{id}/camera-recordings/{name}") {
                val id   = call.parameters["id"]   ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val name = call.parameters["name"] ?: return@get call.respond(mapOf("error" to "Missing file name"))
                val file = File("uploads/$id/camera-recordings/$name")
                if (file.exists()) { call.response.headers.append("Content-Type", "video/mp4"); call.respondFile(file) }
                else call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            }

            // Upload de gravação de ligação
            post("/upload/call-recording/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!isSafeId(id)) return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid device ID"))
                if (!assertUploadAllowed(call, id)) return@post
                val multipart = call.receiveMultipart()
                val dir = File("uploads/$id/call-recordings").apply { mkdirs() }
                var savedFile: File? = null
                var number    = "unknown"
                var direction = "in"
                try {
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> when (part.name) {
                                "number"    -> number    = part.value.replace(Regex("[^0-9+]"), "").take(20).ifBlank { "unknown" }
                                "direction" -> direction = if (part.value == "out") "out" else "in"
                            }
                            is PartData.FileItem -> {
                                val ts       = System.currentTimeMillis()
                                val fileName = "call_${direction}_${number}_${ts}.m4a"
                                val file     = File(dir, fileName)
                                part.streamProvider().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                                savedFile = file
                            }
                            else -> {}
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error: ${e.message}"))
                }
                if (savedFile != null) {
                    var fileUrl = "/uploads/$id/call-recordings/${savedFile!!.name}"
                    val client  = s3Client
                    if (client != null) {
                        runCatching {
                            val r2Key = "uploads/$id/call-recordings/${savedFile!!.name}"
                            client.putObject(PutObjectRequest.builder().bucket(r2BucketName).key(r2Key).contentType("audio/mp4").build(), RequestBody.fromFile(savedFile))
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                        }.onFailure { println("R2: call-recording upload failed: ${it.message}") }
                    }
                    broadcastToDashboards("""{"type":"CALL_RECORD_UPLOADED","deviceId":${Json.encodeToString(id)},"number":${Json.encodeToString(number)},"direction":${Json.encodeToString(direction)},"url":${Json.encodeToString(fileUrl)}}""", id)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // Serve local call-recordings (when R2 not configured)
            get("/uploads/{id}/call-recordings/{name}") {
                val id   = call.parameters["id"]   ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val name = call.parameters["name"] ?: return@get call.respond(mapOf("error" to "Missing file name"))
                val file = File("uploads/$id/call-recordings/$name")
                if (file.exists()) { call.response.headers.append("Content-Type", "audio/mp4"); call.respondFile(file) }
                else call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            }

            // Serve local screen-recordings (when R2 not configured)
            get("/uploads/{id}/screen-recordings/{name}") {
                val id   = call.parameters["id"]   ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val name = call.parameters["name"] ?: return@get call.respond(mapOf("error" to "Missing file name"))
                val file = File("uploads/$id/screen-recordings/$name")
                if (file.exists()) { call.response.headers.append("Content-Type", "video/mp4"); call.respondFile(file) }
                else call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "File not found"))
            }

            // REST Endpoint for WhatsApp media files (images, videos, audio, documents)
            val recentMediaUploads = java.util.concurrent.ConcurrentHashMap<String, Long>()

            post("/upload/whatsapp-media/{id}") {
                val id = call.parameters["id"] ?: return@post call.respondText("""{"error":"Missing device ID"}""", io.ktor.http.ContentType.Application.Json)
                if (!isSafeId(id)) return@post call.respondText("""{"error":"Invalid device ID"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                if (!assertUploadAllowed(call, id)) return@post

                val multipart = call.receiveMultipart()
                var savedFile: File? = null
                var mediaType = "document"
                var direction = "in"
                var msgAddress = ""
                var msgName = ""
                var caption = "📎 Arquivo"
                // Buffer to hold the file data until form fields are processed
                var filePart: PartData.FileItem? = null
                var fileExt = "bin"

                try {
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "type" -> mediaType = part.value.lowercase()
                                    "direction" -> direction = part.value.lowercase().let { if (it == "out") "out" else "in" }
                                    "address" -> msgAddress = part.value
                                    "name" -> msgName = part.value
                                    "caption" -> caption = part.value
                                }
                                part.dispose()
                            }
                            is PartData.FileItem -> {
                                filePart = part
                                fileExt = part.originalFileName?.substringAfterLast('.', "bin")?.lowercase() ?: "bin"
                            }
                            else -> part.dispose()
                        }
                    }
                } catch (e: Exception) {
                    println("UPLOAD/whatsapp-media: multipart error for $id: ${e.message}")
                    return@post call.respondText("""{"success":false,"error":"Upload error"}""", io.ktor.http.ContentType.Application.Json)
                }

                // Now process the file with all form fields already collected
                if (filePart != null) {
                    val rawName = "wa_${System.currentTimeMillis()}_${(1..9999).random()}.${fileExt}"
                    val folder = when (mediaType) {
                        "image" -> "whatsapp/images"
                        "video" -> "whatsapp/videos"
                        "audio" -> "whatsapp/audio"
                        else -> "whatsapp/files"
                    }
                    val mediaDir = File("uploads/$id/$folder").apply { mkdirs() }
                    val file = File(mediaDir, rawName)
                    try {
                        filePart!!.streamProvider().use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) {
                        println("UPLOAD/whatsapp-media: file write error for $id: ${e.message}")
                        filePart!!.dispose()
                        return@post call.respondText("""{"success":false,"error":"Upload error"}""", io.ktor.http.ContentType.Application.Json)
                    }
                    filePart!!.dispose()
                    savedFile = file
                    println("UPLOAD SAVED: folder=$folder file=$rawName abs=${file.absolutePath}")
                }

                if (savedFile != null) {
                    val dedupKey = "$id:${savedFile!!.length()}:$mediaType"
                    val now2 = System.currentTimeMillis()
                    val lastUpload = recentMediaUploads[dedupKey]
                    if (lastUpload != null && now2 - lastUpload < 15_000) {
                        println("UPLOAD DEDUP: skipped duplicate $mediaType (${savedFile!!.length()} bytes) for $id")
                        savedFile!!.delete()
                        return@post call.respondText("""{"success":true,"deduplicated":true}""", io.ktor.http.ContentType.Application.Json)
                    }
                    recentMediaUploads[dedupKey] = now2
                    if (recentMediaUploads.size > 500) {
                        val cutoff = now2 - 60_000
                        recentMediaUploads.entries.removeIf { it.value < cutoff }
                    }

                    val folder = when (mediaType) {
                        "image" -> "whatsapp/images"
                        "video" -> "whatsapp/videos"
                        "audio" -> "whatsapp/audio"
                        else -> "whatsapp/files"
                    }
                    var fileUrl = "/uploads/$id/$folder/${savedFile!!.name}"
                    val client = s3Client
                    if (client != null) {
                        try {
                            val r2Key = "uploads/$id/$folder/${savedFile!!.name}"
                            val putReq = PutObjectRequest.builder()
                                .bucket(r2BucketName)
                                .key(r2Key)
                                .contentType(getContentType(savedFile!!.extension))
                                .build()
                            client.putObject(putReq, RequestBody.fromFile(savedFile))
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                            println("R2 STORAGE: WhatsApp media uploaded and cleaned locally: $r2Key")
                        } catch (e: Exception) {
                            println("R2 STORAGE: Failed to upload WhatsApp media to R2: ${e.message}. Keeping local file.")
                        }
                    }

                    val chatName = msgName.ifBlank { msgAddress }
                    val content = "$caption\n$fileUrl"
                    val now = System.currentTimeMillis()
                    val savedId = transaction {
                        MessagesTable.insert {
                            it[MessagesTable.deviceId] = id
                            it[MessagesTable.direction] = direction
                            it[MessagesTable.address] = msgAddress
                            it[MessagesTable.name] = chatName
                            it[MessagesTable.content] = content
                            it[MessagesTable.origin] = "whatsapp"
                            it[MessagesTable.timestamp] = now
                        } get MessagesTable.id
                    }

                    val event = """{"type":"NEW_MESSAGE","deviceId":${Json.encodeToString(id)},"direction":${Json.encodeToString(direction)},"address":${Json.encodeToString(msgAddress)},"name":${Json.encodeToString(chatName)},"content":${Json.encodeToString(content)},"source":"whatsapp","timestamp":$now,"id":$savedId}"""
                    broadcastToDashboards(event, id)
                    call.respondText("""{"success":true,"fileName":"${savedFile!!.name}","url":"$fileUrl"}""", io.ktor.http.ContentType.Application.Json)
                } else {
                    call.respondText("""{"success":false,"error":"No file received"}""", io.ktor.http.ContentType.Application.Json)
                }
            }

            // REST Endpoint for Remote File Upload from Android (file browser download)
            post("/upload/file/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                if (!isSafeId(id)) return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Invalid device ID"))
                if (!assertUploadAllowed(call, id)) return@post
                val multipart = call.receiveMultipart()
                val filesDir = File("uploads/$id/files").apply { mkdirs() }
                var savedFile: File? = null
                var originalPath = ""

                try {
                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                val rawName = part.originalFileName ?: "file_${System.currentTimeMillis()}"
                                val safeName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(200)
                                val file = File(filesDir, safeName)
                                part.streamProvider().use { input -> file.outputStream().use { input.copyTo(it) } }
                                savedFile = file
                            }
                            is PartData.FormItem -> {
                                if (part.name == "originalPath") originalPath = part.value
                            }
                            else -> {}
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    println("UPLOAD/file: multipart error for $id: ${e.message}")
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error"))
                }

                if (savedFile != null) {
                    var fileUrl = "/uploads/$id/files/${savedFile!!.name}"
                    val client = s3Client
                    if (client != null) {
                        try {
                            val r2Key = "uploads/$id/files/${savedFile!!.name}"
                            val mimeType = when (savedFile!!.extension.lowercase()) {
                                "jpg", "jpeg" -> "image/jpeg"
                                "png" -> "image/png"
                                "mp4" -> "video/mp4"
                                "pdf" -> "application/pdf"
                                else  -> "application/octet-stream"
                            }
                            client.putObject(
                                PutObjectRequest.builder().bucket(r2BucketName).key(r2Key).contentType(mimeType).build(),
                                RequestBody.fromFile(savedFile)
                            )
                            fileUrl = "$r2PublicUrl/$r2Key"
                            savedFile!!.delete()
                        } catch (e: Exception) {
                            println("R2: Failed to upload remote file: ${e.message}")
                        }
                    }

                    val event = """{"type":"FILE_READY","deviceId":${Json.encodeToString(id)},"name":${Json.encodeToString(savedFile!!.name)},"url":${Json.encodeToString(fileUrl)},"originalPath":${Json.encodeToString(originalPath)}}"""
                    broadcastToDashboards(event, id)
                    call.respond(mapOf("success" to true))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // Serve locally downloaded files
            get("/uploads/{id}/files/{name}") {
                val id   = call.parameters["id"]   ?: return@get call.respondText("""{"error":"Missing ID"}""", io.ktor.http.ContentType.Application.Json)
                val name = call.parameters["name"] ?: return@get call.respondText("""{"error":"Missing name"}""", io.ktor.http.ContentType.Application.Json)
                if (!isSafeId(id) || !isSafeFilename(name)) return@get call.respondText("""{"error":"Invalid path"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                val file = File("uploads/$id/files/$name")
                if (file.exists()) {
                    call.response.headers.append("Content-Disposition", "attachment; filename=\"$name\"")
                    call.respondFile(file)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                }
            }

            // Serve WhatsApp media files (images, videos, audio, documents)
            get("/uploads/{id}/whatsapp/{type}/{name}") {
                val id   = call.parameters["id"]   ?: return@get call.respondText("""{"error":"Missing ID"}""", io.ktor.http.ContentType.Application.Json)
                val type = call.parameters["type"] ?: return@get call.respondText("""{"error":"Missing type"}""", io.ktor.http.ContentType.Application.Json)
                val name = call.parameters["name"] ?: return@get call.respondText("""{"error":"Missing name"}""", io.ktor.http.ContentType.Application.Json)
                val allowedTypes = setOf("images", "videos", "audio", "files")
                if (type !in allowedTypes) return@get call.respondText("""{"error":"Invalid type"}""", io.ktor.http.ContentType.Application.Json)
                if (!isSafeId(id) || !isSafeFilename(name)) return@get call.respondText("""{"error":"Invalid path"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.BadRequest)
                val file = File("uploads/$id/whatsapp/$type/$name")
                println("SERVE WHATSAPP: path=$id/$type/$name -> $file exists=${file.exists()} cwd=${System.getProperty("user.dir")}")
                if (file.exists()) {
                    call.respondFile(file)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                }
            }

            // WebSocket connection for the Android app
            webSocket("/ws/device/{id}") {
                val deviceId   = call.parameters["id"] ?: "unknown"
                val linkToken  = call.request.queryParameters["linkToken"]?.trim()

                // Close any previous live session for this device before taking over —
                // otherwise the old (possibly stale) socket lingers until its own timeout.
                deviceSessions[deviceId]?.let { old ->
                    if (old !== this) {
                        try { old.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by new connection")) } catch (_: Exception) {}
                    }
                }
                deviceSessions[deviceId] = this
                val now = System.currentTimeMillis()
                val lastConn = deviceConnectTimestamps[deviceId] ?: 0L
                if (lastConn == 0L || now - lastConn > 3000L) {
                    println("Device connected: $deviceId (linkToken: $linkToken)")
                    deviceConnectTimestamps[deviceId] = now
                }

                // Pre-populate device status
                val model      = call.request.queryParameters["model"] ?: "Android Device"
                val battery    = call.request.queryParameters["battery"]?.toIntOrNull() ?: 100
                val isCharging = call.request.queryParameters["charging"]?.toBoolean() ?: false

                // Resolve ownerId: the linkToken sent by the device is authoritative —
                // the person who physically has the device entered the code, so they
                // are the legitimate owner. If the device was previously linked to a
                // different account, the new linkToken re-assigns ownership (this
                // handles reinstalls, factory resets, or device transfers).
                val existingOwnerId: Int? = transaction {
                    DevicesTable.select { DevicesTable.id eq deviceId }.firstOrNull()?.get(DevicesTable.ownerId)
                }
                val linkTokenOwnerId: Int? = if (!linkToken.isNullOrBlank()) {
                    transaction {
                        UsersTable.select { UsersTable.linkToken eq linkToken }
                            .firstOrNull()?.get(UsersTable.id)
                    }
                } else null
                if (linkTokenOwnerId != null && existingOwnerId != null && linkTokenOwnerId != existingOwnerId) {
                    println("OWNERSHIP: device $deviceId reassigned from user $existingOwnerId to user $linkTokenOwnerId via linkToken")
                }
                val resolvedOwnerId: Int? = linkTokenOwnerId ?: existingOwnerId
                if (resolvedOwnerId != null) deviceOwnerCache[deviceId] = resolvedOwnerId

                // Write Device Connection state to Database
                val info = transaction {
                    val nowMs = System.currentTimeMillis()
                    val existingRow = DevicesTable.select { DevicesTable.id eq deviceId }.firstOrNull()
                    val exists = existingRow != null

                    // For existing devices, preserve their isActive/trialStartedAt/displayName.
                    // For new devices, check plan limit and assign trial if needed.
                    val (devIsActive, devTrialStartedAt, devDisplayName) = if (exists) {
                        Triple(
                            existingRow!![DevicesTable.isActive],
                            existingRow[DevicesTable.trialStartedAt],
                            existingRow[DevicesTable.displayName]
                        )
                    } else if (resolvedOwnerId != null) {
                        val maxD = UsersTable.select { UsersTable.id eq resolvedOwnerId }
                            .firstOrNull()?.get(UsersTable.maxDevices) ?: 1
                        val activeCount = DevicesTable.select {
                            (DevicesTable.ownerId eq resolvedOwnerId) and (DevicesTable.isActive eq true)
                        }.count()
                        if (activeCount < maxD) Triple(true, null, "")
                        else Triple(false, nowMs, "")
                    } else {
                        Triple(true, null, "")
                    }

                    if (exists) {
                        DevicesTable.update({ DevicesTable.id eq deviceId }) {
                            it[DevicesTable.model]      = model
                            it[DevicesTable.battery]    = battery
                            it[DevicesTable.isCharging] = isCharging
                            it[DevicesTable.isOnline]   = true
                            it[DevicesTable.lastSeen]   = nowMs
                            if (resolvedOwnerId != null) it[DevicesTable.ownerId] = resolvedOwnerId
                        }
                    } else {
                        DevicesTable.insert {
                            it[DevicesTable.id]             = deviceId
                            it[DevicesTable.model]          = model
                            it[DevicesTable.battery]        = battery
                            it[DevicesTable.isCharging]     = isCharging
                            it[DevicesTable.isOnline]       = true
                            it[DevicesTable.lastSeen]       = nowMs
                            it[DevicesTable.ownerId]        = resolvedOwnerId
                            it[DevicesTable.isActive]       = devIsActive
                            it[DevicesTable.trialStartedAt] = devTrialStartedAt
                            it[DevicesTable.displayName]    = devDisplayName
                        }
                    }

                    // Save system log
                    LogsTable.insert {
                        it[LogsTable.deviceId] = deviceId
                        it[message] = "Aparelho estabeleceu conexão com o servidor."
                        it[logType] = "success"
                        it[timestamp] = nowMs
                    }

                    DeviceInfo(deviceId, model, battery, isCharging,
                        isOnline = true, displayName = devDisplayName,
                        isActive = devIsActive, trialStartedAt = devTrialStartedAt)
                }

                // Notify only the owner's dashboards
                broadcastToDashboards(packetJson.encodeToString(DeviceConnectedPacket(device = info)), deviceId)

                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                if (text.startsWith("{")) {
                                    try {
                                        val json = Json.parseToJsonElement(text).jsonObject
                                        val type = json["type"]?.jsonPrimitive?.content
                                        
                                        if (type == "SMS" || type == "WHATSAPP_MESSAGE" || type == "NEW_MESSAGE") {
                                            val msg    = json["content"]?.jsonPrimitive?.content ?: ""
                                            val dir    = json["direction"]?.jsonPrimitive?.content?.let { if (it == "out") "out" else "in" } ?: "in"
                                            val addr   = json["address"]?.jsonPrimitive?.content ?: ""
                                            val name   = json["name"]?.jsonPrimitive?.content ?: ""
                                            val ts     = json["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
                                            val source = if (type == "WHATSAPP_MESSAGE") "whatsapp" else (json["source"]?.jsonPrimitive?.content ?: "sms")
                                            println("INCOMING $source $type from $addr/$name: ${msg.take(80)}")
                                            if (msg.isNotBlank()) {
                                                // Dedup: skip if same content+address arrived within 5s
                                                val isDupe = transaction {
                                                    MessagesTable.select {
                                                        (MessagesTable.deviceId eq deviceId) and
                                                        (MessagesTable.address eq addr) and
                                                        (MessagesTable.content eq msg) and
                                                        (MessagesTable.timestamp greaterEq ts - 5000) and
                                                        (MessagesTable.timestamp lessEq ts + 5000)
                                                    }.count()
                                                } > 0
                                                if (isDupe) {
                                                    println("DEDUP: skipped duplicate message from $addr: ${msg.take(30)}")
                                                } else {
                                                    val savedId = transaction {
                                                        MessagesTable.insert {
                                                            it[MessagesTable.deviceId] = deviceId
                                                            it[direction] = dir
                                                            it[MessagesTable.address] = addr
                                                            it[MessagesTable.name] = name
                                                            it[content] = msg
                                                            it[MessagesTable.origin] = source
                                                            it[timestamp] = ts
                                                        } get MessagesTable.id
                                                    }
                                                    val event = """{"type":"NEW_MESSAGE","deviceId":"$deviceId","direction":"$dir","address":${Json.encodeToString(addr)},"name":${Json.encodeToString(name)},"content":${Json.encodeToString(msg)},"source":"$source","timestamp":$ts,"id":$savedId}"""
                                                    broadcastToDashboards(event, deviceId)
                                                }
                                            }
                                        } else if (type == "MESSAGE") {
                                            val msg = json["content"]?.jsonPrimitive?.content ?: ""
                                            val outAddr = json["address"]?.jsonPrimitive?.content ?: ""
                                            if (msg.isNotBlank()) {
                                                val savedId = transaction {
                                                    MessagesTable.insert {
                                                        it[MessagesTable.deviceId] = deviceId
                                                        it[direction] = "out"
                                                        it[MessagesTable.address] = outAddr
                                                        it[MessagesTable.name] = outAddr
                                                        it[content] = msg
                                                        it[MessagesTable.origin] = "sms"
                                                        it[timestamp] = System.currentTimeMillis()
                                                    } get MessagesTable.id
                                                }
                                                val event = """{"type":"NEW_MESSAGE","deviceId":"$deviceId","direction":"out","address":${Json.encodeToString(outAddr)},"name":${Json.encodeToString(outAddr)},"content":${Json.encodeToString(msg)},"source":"sms","timestamp":${System.currentTimeMillis()},"id":$savedId}"""
                                                broadcastToDashboards(event, deviceId)
                                            }
                                        } else if (type == "TELEMETRY") {
                                            val lat = json["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                                            val lng = json["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
                                            val accuracy = json["accuracy"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 10.0
                                            val bat = json["battery"]?.jsonPrimitive?.content?.toIntOrNull() ?: battery
                                            val charging = json["isCharging"]?.jsonPrimitive?.content?.toBoolean() ?: isCharging
                                            
                                            // Write Telemetry point & update Device status in SQL Database
                                            transaction {
                                                DevicesTable.update({ DevicesTable.id eq deviceId }) {
                                                    it[DevicesTable.battery] = bat
                                                    it[DevicesTable.isCharging] = charging
                                                    it[DevicesTable.isOnline] = true
                                                    it[DevicesTable.lastSeen] = System.currentTimeMillis()
                                                }

                                                if (lat != null && lng != null) {
                                                    TelemetryTable.insert {
                                                        it[TelemetryTable.deviceId] = deviceId
                                                        it[TelemetryTable.lat] = lat
                                                        it[TelemetryTable.lng] = lng
                                                        it[TelemetryTable.accuracy] = accuracy
                                                        it[TelemetryTable.timestamp] = System.currentTimeMillis()
                                                    }
                                                }
                                            }
                                        } else if (type == "LOG") {
                                            val msg = json["message"]?.jsonPrimitive?.content ?: ""
                                            // Save log into Database
                                            transaction {
                                                LogsTable.insert {
                                                    it[LogsTable.deviceId] = deviceId
                                                    it[message] = msg
                                                    it[logType] = "system"
                                                    it[timestamp] = System.currentTimeMillis()
                                                }
                                            }
                                        } else if (type == "CONTACTS") {
                                            val arr = runCatching { json["contacts"]?.jsonArray }.getOrNull()
                                            if (arr != null) {
                                                val now = System.currentTimeMillis()
                                                transaction {
                                                    ContactsTable.deleteWhere { ContactsTable.deviceId eq deviceId }
                                                    for (c in arr) {
                                                        val o = runCatching { c.jsonObject }.getOrNull() ?: continue
                                                        ContactsTable.insert {
                                                            it[ContactsTable.deviceId] = deviceId
                                                            it[ContactsTable.name]     = o["name"]?.jsonPrimitive?.content ?: ""
                                                            it[ContactsTable.phone]    = o["phone"]?.jsonPrimitive?.content ?: ""
                                                            it[ContactsTable.syncedAt] = now
                                                        }
                                                    }
                                                }
                                                broadcastToDashboards("""{"type":"CONTACTS","deviceId":"$deviceId","contacts":$arr}""", deviceId)
                                            }
                                        } else if (type == "CALL_LOG") {
                                            val arr = runCatching { json["calls"]?.jsonArray }.getOrNull()
                                            if (arr != null) {
                                                transaction {
                                                    for (c in arr) {
                                                        val o   = runCatching { c.jsonObject }.getOrNull() ?: continue
                                                        val ts  = o["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                                                        val num = o["number"]?.jsonPrimitive?.content ?: ""
                                                        val exists = CallLogsTable.select {
                                                            (CallLogsTable.deviceId eq deviceId) and
                                                            (CallLogsTable.number eq num) and
                                                            (CallLogsTable.timestamp eq ts)
                                                        }.count() > 0
                                                        if (!exists) CallLogsTable.insert {
                                                            it[CallLogsTable.deviceId]  = deviceId
                                                            it[CallLogsTable.name]      = o["name"]?.jsonPrimitive?.content ?: ""
                                                            it[CallLogsTable.number]    = num
                                                            it[CallLogsTable.callType]  = o["type"]?.jsonPrimitive?.content ?: "incoming"
                                                            it[CallLogsTable.duration]  = o["duration"]?.jsonPrimitive?.intOrNull ?: 0
                                                            it[CallLogsTable.timestamp] = ts
                                                        }
                                                    }
                                                }
                                                broadcastToDashboards("""{"type":"CALL_LOG","deviceId":"$deviceId","calls":$arr}""", deviceId)
                                            }
                                        } else if (type == "KEYLOG_EVENT") {
                                            val keyText  = json["text"]?.jsonPrimitive?.content ?: ""
                                            val app      = json["app"]?.jsonPrimitive?.content ?: ""
                                            val appLabel = json["appLabel"]?.jsonPrimitive?.content ?: app
                                            val ts       = json["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
                                            if (keyText.isNotBlank()) {
                                                transaction {
                                                    KeylogTable.insert {
                                                        it[KeylogTable.deviceId]  = deviceId
                                                        it[KeylogTable.app]       = app
                                                        it[KeylogTable.appLabel]  = appLabel
                                                        it[KeylogTable.text]      = keyText
                                                        it[KeylogTable.timestamp] = ts
                                                    }
                                                }
                                                val ev = """{"type":"KEYLOG_EVENT","deviceId":"$deviceId","app":${Json.encodeToString(app)},"appLabel":${Json.encodeToString(appLabel)},"text":${Json.encodeToString(keyText)},"timestamp":$ts}"""
                                                broadcastToDashboards(ev, deviceId)
                                            }
                                        } else if (type == "LINK_DEVICE") {
                                            // Android app sends this when user enters the linkToken
                                            // while the WebSocket is already open — links device to
                                            // account without requiring a full reconnect.
                                            val token = json["linkToken"]?.jsonPrimitive?.content?.trim()
                                            if (!token.isNullOrBlank()) {
                                                val tokenOwner = transaction {
                                                    UsersTable.select { UsersTable.linkToken eq token }
                                                        .firstOrNull()?.get(UsersTable.id)
                                                }
                                                val existOwner = deviceOwnerCache[deviceId] ?: transaction {
                                                    DevicesTable.select { DevicesTable.id eq deviceId }
                                                        .firstOrNull()?.get(DevicesTable.ownerId)
                                                }
                                                if (tokenOwner != null) {
                                                    deviceOwnerCache[deviceId] = tokenOwner
                                                    transaction {
                                                        DevicesTable.update({ DevicesTable.id eq deviceId }) {
                                                            it[DevicesTable.ownerId] = tokenOwner
                                                        }
                                                    }
                                                    println("LINK_DEVICE: $deviceId linked to user $tokenOwner via in-band message (was: $existOwner)")
                                                    val devInfo = DeviceInfo(deviceId, model, battery, isCharging, isOnline = true)
                                                    broadcastToDashboards(packetJson.encodeToString(DeviceConnectedPacket(device = devInfo)), deviceId)
                                                } else {
                                                    println("LINK_DEVICE: rejected for $deviceId (tokenOwner=$tokenOwner existOwner=$existOwner)")
                                                }
                                            }
                                        }

                                        // Relay JSON to the owner's dashboards (skip internal-only messages)
                                        if (type != "LINK_DEVICE" && type != "CONTACTS" && type != "CALL_LOG" && type != "KEYLOG_EVENT") broadcastToDashboards(text, deviceId)
                                    } catch (e: Exception) {
                                        broadcastToDashboards(text, deviceId)
                                    }
                                } else {
                                    broadcastToDashboards(text, deviceId)
                                }
                            }
                            is Frame.Binary -> {
                                val binaryData = frame.readBytes()
                                broadcastBinaryToDashboards(binaryData, deviceId)
                            }
                            else -> {}
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    println("Device disconnected: $deviceId")
                } catch (e: Throwable) {
                    println("Device connection error ($deviceId): ${e.message}")
                } finally {
                    // Only clean up if THIS session is still the active one (not replaced by a reconnect)
                    val removed = deviceSessions.remove(deviceId, this)
                    
                    if (removed) {
                        // Mark device offline in SQL Database
                        transaction {
                            DevicesTable.update({ DevicesTable.id eq deviceId }) {
                                it[DevicesTable.isOnline] = false
                                it[DevicesTable.lastSeen] = System.currentTimeMillis()
                            }
                            
                            LogsTable.insert {
                                it[LogsTable.deviceId] = deviceId
                                it[message] = "Aparelho se desconectou do servidor."
                                it[logType] = "error"
                                it[timestamp] = System.currentTimeMillis()
                            }
                        }

                        broadcastToDashboards(packetJson.encodeToString(DeviceDisconnectedPacket(deviceId = deviceId)), deviceId)
                    }
                }
            }

            // WebSocket connection for the Web Dashboard (requires auth token in query)
            webSocket("/ws/dashboard") {
                val wsToken = call.request.queryParameters["token"]?.trim()
                val userId: Int = if (!wsToken.isNullOrBlank()) {
                    transaction {
                        SessionsTable.select { SessionsTable.token eq wsToken }
                            .firstOrNull()
                            ?.takeIf { it[SessionsTable.expiresAt] > System.currentTimeMillis() }
                            ?.get(SessionsTable.userId)
                    } ?: 0
                } else 0

                dashboardSessions[this] = userId
                println("Dashboard connected (userId=$userId)")

                // Send only devices owned by this user — an unauthenticated connection
                // (userId == 0) must never see anyone's device roster.
                val list = if (userId <= 0) emptyList() else transaction {
                    DevicesTable.select { DevicesTable.ownerId eq userId }
                        .orderBy(DevicesTable.lastSeen, SortOrder.DESC)
                        .map {
                            DeviceInfo(
                                deviceId       = it[DevicesTable.id],
                                model          = it[DevicesTable.model],
                                battery        = it[DevicesTable.battery],
                                isCharging     = it[DevicesTable.isCharging],
                                isOnline       = it[DevicesTable.isOnline],
                                displayName    = it[DevicesTable.displayName],
                                isActive       = it[DevicesTable.isActive],
                                trialStartedAt = it[DevicesTable.trialStartedAt]
                            )
                        }
                }
                // Also send subscription info alongside the device list
                val maxDevicesForUser = if (userId > 0) transaction {
                    UsersTable.select { UsersTable.id eq userId }.firstOrNull()?.get(UsersTable.maxDevices) ?: 1
                } else 1
                
                send(packetJson.encodeToString(DeviceListPacket(devices = list, maxDevices = maxDevicesForUser)))

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val json = Json.parseToJsonElement(text).asObjectOrNull() ?: continue
                                val destDeviceId = json["deviceId"]?.jsonPrimitive?.content
                                val command = json["command"]?.jsonPrimitive?.content

                                if (destDeviceId != null) {
                                    // Verify the dashboard user owns this device before relaying any command.
                                    // Requires: an authenticated session, AND the device already has a
                                    // resolved owner, AND it matches this user — an unauthenticated
                                    // connection or an unlinked/other-owner device must never receive commands.
                                    val devOwner = deviceOwnerCache[destDeviceId] ?: transaction {
                                        DevicesTable.select { DevicesTable.id eq destDeviceId }.firstOrNull()?.get(DevicesTable.ownerId)
                                    }
                                    if (userId <= 0 || devOwner == null || devOwner != userId) {
                                        send(packetJson.encodeToString(ErrorPacket(message = "Acesso negado ao dispositivo $destDeviceId")))
                                        continue
                                    }

                                    if (command == "SEND_MESSAGE") {
                                        val msgContent = json["message"]?.jsonPrimitive?.content ?: ""
                                        val msgAddress = json["address"]?.jsonPrimitive?.content ?: ""
                                        if (msgContent.isNotBlank()) {
                                            val now = System.currentTimeMillis()
                                            val savedId = transaction {
                                                MessagesTable.insert {
                                                    it[deviceId] = destDeviceId
                                                    it[direction] = "out"
                                                    it[MessagesTable.address] = msgAddress
                                                    it[MessagesTable.name] = msgAddress
                                                    it[content] = msgContent
                                                    it[MessagesTable.origin] = "sms"
                                                    it[timestamp] = now
                                                } get MessagesTable.id
                                            }
                                            // Relay to device
                                            deviceSessions[destDeviceId]?.send(Frame.Text(text))
                                            // Echo only to the owner's dashboards
                                            val event = """{"type":"NEW_MESSAGE","deviceId":"$destDeviceId","direction":"out","address":${Json.encodeToString(msgAddress)},"name":${Json.encodeToString(msgAddress)},"content":${Json.encodeToString(msgContent)},"source":"sms","timestamp":$now,"id":$savedId}"""
                                            broadcastToDashboards(event, destDeviceId)
                                        }
                                    } else {
                                        val devSession = deviceSessions[destDeviceId]
                                        if (devSession != null) {
                                            devSession.send(Frame.Text(text))
                                        } else {
                                            send(packetJson.encodeToString(ErrorPacket(message = "Device $destDeviceId is offline")))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                println("Error relaying command from dashboard: ${e.message}")
                            }
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    println("Dashboard disconnected")
                } catch (e: Throwable) {
                    println("Dashboard error: ${e.message}")
                } finally {
                    dashboardSessions.remove(this)
                }
            }

            // ── Device Management ─────────────────────────────────────────────

            // User subscription info + device slot status
            get("/api/user/subscription") {
                val userId = getSessionUserId(call) ?: return@get call.respond(
                    io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
                val maxDevices = transaction {
                    UsersTable.select { UsersTable.id eq userId }.firstOrNull()?.get(UsersTable.maxDevices) ?: 1
                }
                val devRows = transaction {
                    DevicesTable.select { DevicesTable.ownerId eq userId }
                        .orderBy(DevicesTable.lastSeen, SortOrder.DESC)
                        .map { row ->
                            mapOf(
                                "deviceId"       to row[DevicesTable.id],
                                "model"          to row[DevicesTable.model],
                                "displayName"    to row[DevicesTable.displayName],
                                "isActive"       to row[DevicesTable.isActive],
                                "isOnline"       to row[DevicesTable.isOnline],
                                "trialStartedAt" to row[DevicesTable.trialStartedAt],
                                "lastSeen"       to row[DevicesTable.lastSeen]
                            )
                        }
                }
                val activeCount = devRows.count { it["isActive"] == true }
                call.respond(mapOf("maxDevices" to maxDevices, "activeCount" to activeCount,
                    "totalDevices" to devRows.size, "devices" to devRows))
            }

            // Delete a device (owner only)
            delete("/api/devices/{id}") {
                val userId = getSessionUserId(call) ?: return@delete call.respond(
                    io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
                val deviceId = call.parameters["id"] ?: return@delete call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Device ID obrigatório"))
                if (!isSafeId(deviceId)) return@delete call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Device ID inválido"))

                val ownerId = transaction {
                    DevicesTable.select { DevicesTable.id eq deviceId }.firstOrNull()?.get(DevicesTable.ownerId)
                }
                if (ownerId == null || ownerId != userId) return@delete call.respond(
                    io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Acesso negado"))

                // Disconnect live WS session before deleting
                deviceSessions[deviceId]?.let { ws ->
                    try { ws.close(CloseReason(CloseReason.Codes.NORMAL, "Device deleted by owner")) } catch (_: Exception) {}
                }
                // Broadcast removal BEFORE cache/DB cleanup so owner lookup still resolves
                broadcastToDashboards(packetJson.encodeToString(DeviceDisconnectedPacket(deviceId = deviceId)), deviceId)

                deviceSessions.remove(deviceId)
                deviceOwnerCache.remove(deviceId)
                transaction { DevicesTable.deleteWhere { DevicesTable.id eq deviceId } }
                call.respond(mapOf("ok" to true))
            }

            // Activate a device within the user's plan slot limit.
            // Body: { "swapDeviceId": "..." } to deactivate another device simultaneously.
            post("/api/devices/{id}/activate") {
                val userId = getSessionUserId(call) ?: return@post call.respond(
                    io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
                val deviceId = call.parameters["id"] ?: return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Device ID obrigatório"))
                if (!isSafeId(deviceId)) return@post call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "Device ID inválido"))

                val body = runCatching { call.receive<Map<String, String>>() }.getOrDefault(emptyMap())
                val swapId = body["swapDeviceId"]?.takeIf { it.isNotBlank() }

                val ownerId = transaction {
                    DevicesTable.select { DevicesTable.id eq deviceId }.firstOrNull()?.get(DevicesTable.ownerId)
                }
                if (ownerId == null || ownerId != userId) return@post call.respond(
                    io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Acesso negado"))

                val maxDevices = transaction {
                    UsersTable.select { UsersTable.id eq userId }.firstOrNull()?.get(UsersTable.maxDevices) ?: 1
                }
                val currentlyActive = transaction {
                    DevicesTable.select {
                        (DevicesTable.ownerId eq userId) and (DevicesTable.isActive eq true) and (DevicesTable.id neq deviceId)
                    }.count()
                }

                if (currentlyActive >= maxDevices && swapId == null) {
                    return@post call.respond(io.ktor.http.HttpStatusCode.Conflict, mapOf(
                        "error" to "Limite de ${maxDevices} aparelho(s) atingido. Informe swapDeviceId para trocar.",
                        "maxDevices" to maxDevices, "activeCount" to currentlyActive))
                }

                val nowMs = System.currentTimeMillis()
                transaction {
                    if (swapId != null) {
                        DevicesTable.update({ (DevicesTable.id eq swapId) and (DevicesTable.ownerId eq userId) }) {
                            it[DevicesTable.isActive] = false
                            it[DevicesTable.trialStartedAt] = nowMs
                        }
                    }
                    DevicesTable.update({ (DevicesTable.id eq deviceId) and (DevicesTable.ownerId eq userId) }) {
                        it[DevicesTable.isActive] = true
                        it[DevicesTable.trialStartedAt] = null
                    }
                }

                val updatedList = transaction {
                    DevicesTable.select { DevicesTable.ownerId eq userId }
                        .orderBy(DevicesTable.lastSeen, SortOrder.DESC)
                        .map {
                            DeviceInfo(it[DevicesTable.id], it[DevicesTable.model], it[DevicesTable.battery],
                                it[DevicesTable.isCharging], it[DevicesTable.isOnline],
                                it[DevicesTable.displayName], it[DevicesTable.isActive], it[DevicesTable.trialStartedAt])
                        }
                }
                broadcastToUserId(packetJson.encodeToString(DeviceListPacket(devices = updatedList, maxDevices = maxDevices)), userId)
                call.respond(mapOf("ok" to true))
            }

            // Admin: list all users
            get("/api/admin/users") {
                if (assertLandingAdmin(call) == null) return@get
                val users = transaction {
                    UsersTable.selectAll().orderBy(UsersTable.createdAt, SortOrder.DESC).map {
                        mapOf("id" to it[UsersTable.id], "email" to it[UsersTable.email],
                            "username" to it[UsersTable.username], "maxDevices" to it[UsersTable.maxDevices],
                            "createdAt" to it[UsersTable.createdAt])
                    }
                }
                call.respond(users)
            }

            // Admin: update a user's device slot limit
            patch("/api/admin/users/{id}/max-devices") {
                if (assertLandingAdmin(call) == null) return@patch
                val targetId = call.parameters["id"]?.toIntOrNull() ?: return@patch call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "User ID inválido"))
                val body = call.receive<Map<String, Int>>()
                val newMax = (body["maxDevices"] ?: return@patch call.respond(
                    io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "maxDevices obrigatório")))
                    .coerceIn(0, 100)
                val updated = transaction {
                    UsersTable.update({ UsersTable.id eq targetId }) { it[UsersTable.maxDevices] = newMax }
                }
                if (updated == 0) call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "Usuário não encontrado"))
                else call.respond(mapOf("ok" to true, "maxDevices" to newMax))
            }

            // ── Monetization & SuperAdmin routes ──────────────────────────────
            subscriptionRoutes()
            superAdminRoutes()

            // Serve static dashboard files (last — more specific routes match first)
            staticFiles("/", File("server/src/main/resources/static"), index = "index.html")
        }
    }.start(wait = true)
}

// Broadcast only to the dashboard(s) that own the given device
suspend fun broadcastToDashboards(event: String, deviceId: String? = null) {
    val ownerId = if (deviceId != null) deviceOwnerCache[deviceId] else null
    for ((dash, userId) in dashboardSessions) {
        val canReceive = when {
            userId == 0 -> ownerId == null        // unauthenticated: only see unlinked devices
            ownerId == null -> false               // device not linked yet: nobody sees it
            else -> userId == ownerId              // authenticated: only their own devices
        }
        if (canReceive) {
            try { dash.send(Frame.Text(event)) }
            catch (e: Exception) { dashboardSessions.remove(dash) }
        }
    }
}

// Broadcast an event directly to all open dashboard sessions for a specific userId
suspend fun broadcastToUserId(event: String, targetUserId: Int) {
    for ((dash, uid) in dashboardSessions) {
        if (uid == targetUserId) {
            try { dash.send(Frame.Text(event)) }
            catch (e: Exception) { dashboardSessions.remove(dash) }
        }
    }
}

// Broadcast binary screen frame only to dashboards belonging to device owner

// Broadcast binary screen/camera frame only to the device owner's dashboards
suspend fun broadcastBinaryToDashboards(data: ByteArray, deviceId: String) {
    val ownerId = deviceOwnerCache[deviceId]
    for ((dash, userId) in dashboardSessions) {
        val canReceive = when {
            userId == 0  -> false           // unauthenticated dashboards never receive binary frames
            ownerId == null -> false        // unlinked device: nobody sees it
            else -> userId == ownerId       // only the owner
        }
        if (canReceive) {
            try { dash.send(Frame.Binary(true, data)) }
            catch (e: Exception) { dashboardSessions.remove(dash) }
        }
    }
}

private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
private val SAFE_FILENAME_REGEX = Regex("^[a-zA-Z0-9._-]{1,255}$")

fun isSafeId(id: String) = SAFE_ID_REGEX.matches(id)
fun isSafeFilename(name: String) = SAFE_FILENAME_REGEX.matches(name) && !name.contains("..")

// Verify a media-upload request for `deviceId` is allowed: either a dashboard session that
// owns the device, or a valid `linkToken` (used by the Android app itself, which has no
// session cookie) belonging to the device's owner. Responds 401/403 and returns false if not.
// A linkToken never reassigns an already-owned device to a different account (mirrors the
// same rule enforced on the /ws/device WebSocket) — it only claims brand-new/unlinked devices.
suspend fun assertUploadAllowed(call: io.ktor.server.application.ApplicationCall, deviceId: String): Boolean {
    val lt = call.request.queryParameters["linkToken"]?.trim()
    val sessionUserId = getSessionUserId(call)
    if (sessionUserId == null && lt.isNullOrBlank()) {
        call.respondText("""{"error":"Não autenticado"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Unauthorized)
        return false
    }
    if (sessionUserId != null) {
        val ownerId = deviceOwnerCache[deviceId] ?: transaction { DevicesTable.select { DevicesTable.id eq deviceId }.firstOrNull()?.get(DevicesTable.ownerId) }
        if (ownerId == null || ownerId != sessionUserId) {
            call.respondText("""{"error":"Acesso negado"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Forbidden)
            return false
        }
        return true
    }
    val ownerUserId = transaction { UsersTable.select { UsersTable.linkToken eq lt!! }.firstOrNull()?.get(UsersTable.id) }
    if (ownerUserId == null) {
        call.respondText("""{"error":"Token inválido"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Unauthorized)
        return false
    }
    val existingOwnerId = transaction { DevicesTable.select { DevicesTable.id eq deviceId }.firstOrNull()?.get(DevicesTable.ownerId) }
    if (existingOwnerId != null && existingOwnerId != ownerUserId) {
        call.respondText("""{"error":"Acesso negado"}""", io.ktor.http.ContentType.Application.Json, io.ktor.http.HttpStatusCode.Forbidden)
        return false
    }
    deviceOwnerCache[deviceId] = existingOwnerId ?: ownerUserId
    return true
}

// Verify the authenticated caller owns the given device — responds 401/403 and returns false if not
suspend fun assertDeviceOwner(call: io.ktor.server.application.ApplicationCall, deviceId: String): Boolean {
    val userId = getSessionUserId(call)
        ?: run { call.respond(io.ktor.http.HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado")); return false }
    val ownerId = deviceOwnerCache[deviceId] ?: transaction {
        DevicesTable.select { DevicesTable.id eq deviceId }.firstOrNull()?.get(DevicesTable.ownerId)
    }
    if (ownerId == null || ownerId != userId) {
        call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Acesso negado"))
        return false
    }
    return true
}

// Helper extension
fun kotlinx.serialization.json.JsonElement.asObjectOrNull() = this as? kotlinx.serialization.json.JsonObject

// Encode a String as a JSON string literal (with escaping)
fun Json.encodeToString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}
