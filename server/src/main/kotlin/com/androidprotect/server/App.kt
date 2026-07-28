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
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
    val isOnline: Boolean = true
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
    val devices: List<DeviceInfo>
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
    val id        = integer("id").autoIncrement()
    val email     = varchar("email", 255).uniqueIndex()
    val username  = varchar("username", 100)
    val passHash  = varchar("pass_hash", 255)
    val linkToken = varchar("link_token", 20).uniqueIndex() // code entered in Android app
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object SessionsTable : Table("sessions") {
    val token     = varchar("token", 100)
    val userId    = integer("user_id")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(token)
}

object DevicesTable : Table("devices") {
    val id        = varchar("id", 50)
    val model     = varchar("model", 100)
    val battery   = integer("battery")
    val isCharging= bool("is_charging")
    val isOnline  = bool("is_online")
    val lastSeen  = long("last_seen")
    val ownerId   = integer("owner_id").nullable() // null = not yet linked

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
      { "id": "monthly", "name": "Mensal", "price": "29,90", "period": "/mês", "badge": "", "featured": false, "features": ["Monitoramento completo", "Localização em tempo real", "Câmeras e áudio remotos", "Suporte via WhatsApp"] },
      { "id": "quarterly", "name": "Trimestral", "price": "79,90", "period": "/3 meses", "badge": "Economize 11%", "featured": false, "features": ["Tudo do plano Mensal", "Histórico de localização estendido", "Prioridade no suporte"] },
      { "id": "semiannual", "name": "Semestral", "price": "139,90", "period": "/6 meses", "badge": "Mais Popular", "featured": true, "features": ["Tudo do plano Trimestral", "Backup de mídias na nuvem", "Múltiplos dispositivos"] },
      { "id": "annual", "name": "Anual", "price": "239,90", "period": "/ano", "badge": "Economize 33%", "featured": false, "features": ["Tudo do plano Semestral", "Suporte prioritário 24/7", "Melhor custo-benefício"] }
    ]
  },
  "apk": { "url": "", "version": "1.0.0", "size": "", "updatedAt": 0 },
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
    (System.getenv("LANDING_ADMIN_EMAILS") ?: "lrlucasrafael11@gmail.com")
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
        SchemaUtils.create(UsersTable, SessionsTable, DevicesTable, TelemetryTable, LogsTable, MessagesTable, LandingContentTable)
        SchemaUtils.createMissingTablesAndColumns(DevicesTable, MessagesTable) // migrate new columns on existing installs

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
}

fun main() {
    // Initialize Database
    initDatabase()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
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

            // TEMPORARY one-time password reset, gated by ADMIN_RESET_SECRET env var
            // (endpoint 404s when the var is unset). Remove after use.
            post("/api/auth/_dev-reset-password") {
                val secret = System.getenv("ADMIN_RESET_SECRET")
                if (secret.isNullOrBlank()) return@post call.respond(io.ktor.http.HttpStatusCode.NotFound)
                val body = call.receive<Map<String, String>>()
                if (body["secret"] != secret) return@post call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("error" to "Invalid secret"))
                val email = body["email"]?.trim()?.lowercase() ?: return@post call.respond(mapOf("error" to "Missing email"))
                val newPassword = body["newPassword"] ?: return@post call.respond(mapOf("error" to "Missing newPassword"))
                val updated = transaction {
                    UsersTable.update({ UsersTable.email eq email }) { it[passHash] = hashPassword(newPassword) }
                }
                if (updated == 0) return@post call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                call.respond(mapOf("success" to true))
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
                if (assertLandingAdmin(call) == null) return@post
                val kind = call.request.queryParameters["kind"] ?: "image" // image | video | apk
                val subDir = when (kind) {
                    "video" -> "videos"
                    "apk" -> "apk"
                    else -> "images"
                }
                val multipart = call.receiveMultipart()
                val assetsDir = File("uploads/landing/$subDir").apply { mkdirs() }
                var savedFile: File? = null
                var originalName = "asset"

                try {
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            originalName = part.originalFileName ?: "asset"
                            val ext = originalName.substringAfterLast('.', "bin").lowercase()
                            val safeExt = if (isSafeFilename("x.$ext")) ext else "bin"
                            val fileName = "${kind}_${System.currentTimeMillis()}.$safeExt"
                            val file = File(assetsDir, fileName)
                            part.streamProvider().use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            }
                            savedFile = file
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    println("UPLOAD/landing-asset: multipart error: ${e.message}")
                    return@post call.respond(mapOf("success" to false, "error" to "Upload error"))
                }

                val file = savedFile ?: return@post call.respond(mapOf("success" to false, "error" to "No file received"))
                var fileUrl = "/uploads/landing/$subDir/${file.name}"
                val client = s3Client
                if (client != null) {
                    try {
                        val r2Key = "uploads/landing/$subDir/${file.name}"
                        val contentType = getContentType(file.extension)
                        val putReq = PutObjectRequest.builder()
                            .bucket(r2BucketName)
                            .key(r2Key)
                            .contentType(contentType)
                            .build()
                        client.putObject(putReq, RequestBody.fromFile(file))
                        fileUrl = "$r2PublicUrl/$r2Key"
                        file.delete()
                        println("R2 STORAGE: Landing asset uploaded and cleaned locally: $r2Key")
                    } catch (e: Exception) {
                        println("R2 STORAGE: Failed to upload landing asset to R2: ${e.message}. Keeping local file.")
                    }
                }
                call.respond(mapOf("success" to true, "url" to fileUrl, "originalName" to originalName))
            }

            // Public: stable download link — always points at whatever APK is currently configured,
            // so external links/buttons never break when the file is replaced.
            get("/download/apk") {
                val json = transaction {
                    LandingContentTable.select { LandingContentTable.id eq 1 }.firstOrNull()?.get(LandingContentTable.contentJson)
                } ?: DEFAULT_LANDING_CONTENT
                val apkUrl = try {
                    Json.parseToJsonElement(json).asObjectOrNull()
                        ?.get("apk")?.asObjectOrNull()
                        ?.get("url")?.jsonPrimitive?.content
                } catch (e: Exception) { null }
                if (apkUrl.isNullOrBlank()) {
                    return@get call.respond(io.ktor.http.HttpStatusCode.NotFound, mapOf("error" to "APK não disponível ainda"))
                }
                call.respondRedirect(apkUrl, permanent = false)
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
                            .map { name -> mapOf("name" to name, "url" to "/uploads/$id/audio/$name") } // always via server proxy

                        call.respond(mapOf("photos" to photos, "audio" to audios))
                        return@get
                    } catch (e: Exception) {
                        println("R2 STORAGE: Failed to list from R2: ${e.message}. Falling back to local directory.")
                    }
                }

                val photosDir = File("uploads/$id/photos")
                val audioDir = File("uploads/$id/audio")

                val photos = if (photosDir.exists()) {
                    photosDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/photos/$name") } ?: emptyList()
                } else emptyList<Map<String, String>>()

                val audios = if (audioDir.exists()) {
                    audioDir.listFiles()?.map { it.name }?.sortedDescending()
                        ?.map { name -> mapOf("name" to name, "url" to "/uploads/$id/audio/$name") } ?: emptyList()
                } else emptyList<Map<String, String>>()

                call.respond(mapOf("photos" to photos, "audio" to audios))
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
                    val exists = DevicesTable.select { DevicesTable.id eq deviceId }.count() > 0
                    if (exists) {
                        DevicesTable.update({ DevicesTable.id eq deviceId }) {
                            it[DevicesTable.model]      = model
                            it[DevicesTable.battery]    = battery
                            it[DevicesTable.isCharging] = isCharging
                            it[DevicesTable.isOnline]   = true
                            it[DevicesTable.lastSeen]   = System.currentTimeMillis()
                            if (resolvedOwnerId != null) it[DevicesTable.ownerId] = resolvedOwnerId
                        }
                    } else {
                        DevicesTable.insert {
                            it[DevicesTable.id]        = deviceId
                            it[DevicesTable.model]     = model
                            it[DevicesTable.battery]   = battery
                            it[DevicesTable.isCharging]= isCharging
                            it[DevicesTable.isOnline]  = true
                            it[DevicesTable.lastSeen]  = System.currentTimeMillis()
                            it[DevicesTable.ownerId]   = resolvedOwnerId
                        }
                    }

                    // Save system log
                    LogsTable.insert {
                        it[LogsTable.deviceId] = deviceId
                        it[message] = "Aparelho estabeleceu conexão com o servidor."
                        it[logType] = "success"
                        it[timestamp] = System.currentTimeMillis()
                    }

                    DeviceInfo(deviceId, model, battery, isCharging, isOnline = true)
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
                                        
                                        if (type == "SMS" || type == "WHATSAPP_MESSAGE") {
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
                                        if (type != "LINK_DEVICE") broadcastToDashboards(text, deviceId)
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
                    DevicesTable.select { DevicesTable.ownerId eq userId }.map {
                        DeviceInfo(
                            deviceId   = it[DevicesTable.id],
                            model      = it[DevicesTable.model],
                            battery    = it[DevicesTable.battery],
                            isCharging = it[DevicesTable.isCharging],
                            isOnline   = it[DevicesTable.isOnline]
                        )
                    }
                }
                
                send(packetJson.encodeToString(DeviceListPacket(devices = list)))

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
