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
import java.time.Duration
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
import software.amazon.awssdk.core.sync.RequestBody
import java.net.URI

// Active device connections: deviceId -> WebSocketSession
val deviceSessions = ConcurrentHashMap<String, WebSocketSession>()

// Active dashboard connections: session -> true
val dashboardSessions = ConcurrentHashMap<WebSocketSession, Boolean>()

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

@Serializable
data class GeminiPart(val text: String)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
data class GeminiRequest(val contents: List<GeminiContent>)

@Serializable
data class GeminiResponsePart(val text: String? = null)

@Serializable
data class GeminiResponseContent(val parts: List<GeminiResponsePart>? = null)

@Serializable
data class GeminiCandidate(val content: GeminiResponseContent? = null)

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate>? = null)

// SQL Tables Definitions using Exposed
object DevicesTable : Table("devices") {
    val id = varchar("id", 50)
    val model = varchar("model", 100)
    val battery = integer("battery")
    val isCharging = bool("is_charging")
    val isOnline = bool("is_online")
    val lastSeen = long("last_seen")

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
    val direction = varchar("direction", 10) // "out" = dashboard→device, "in" = device→dashboard
    val content = text("content")
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

@Serializable
data class MessageItem(
    val id: Int,
    val direction: String,
    val content: String,
    val timestamp: Long
)

// Call Gemini API using Java HttpClient securely loading GEMINI_API_KEY from environment
fun callGeminiApi(prompt: String): String {
    val apiKey = System.getenv("GEMINI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        return "Erro: A chave de API do Gemini não foi configurada no servidor (variável de ambiente GEMINI_API_KEY)."
    }

    try {
        val client = java.net.http.HttpClient.newHttpClient()
        val requestBody = packetJson.encodeToString(
            GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = prompt)
                        )
                    )
                )
            )
        )

        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val resObj = packetJson.decodeFromString<GeminiResponse>(response.body())
            val replyText = resObj.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!replyText.isNullOrBlank()) {
                return replyText
            }
        }
        println("GEMINI AI ERROR: Status ${response.statusCode()} | Body: ${response.body()}")
        return "Erro: Falha ao obter resposta do Gemini AI (Status: ${response.statusCode()})."
    } catch (e: Exception) {
        println("GEMINI AI EXCEPTION: ${e.message}")
        return "Erro: Ocorreu uma exceção ao conectar com a IA: ${e.message}"
    }
}

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
        SchemaUtils.create(DevicesTable, TelemetryTable, LogsTable, MessagesTable)
        
        // Reset all devices to offline state initially on server start
        DevicesTable.update {
            it[DevicesTable.isOnline] = false
        }
    }
}

fun main() {
    // Initialize Database
    initDatabase()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(30)
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

        routing {
            // Serve static dashboard files
            staticFiles("/", File("server/src/main/resources/static"), index = "index.html")

            // Serve frontend config (API keys from env vars — never hardcoded in source)
            get("/api/config") {
                val mapsKey = System.getenv("GOOGLE_MAPS_API_KEY") ?: ""
                call.respond(mapOf("mapsKey" to mapsKey))
            }

            // REST endpoint to list photos and audios for a device (returns full URLs)
            get("/uploads/{id}/media-list") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))

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
                            .map { name -> mapOf("name" to name, "url" to "$r2PublicUrl/uploads/$id/audio/$name") }

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

            // Serve local uploaded audio (only used when R2 is not configured)
            get("/uploads/{id}/audio/{name}") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val name = call.parameters["name"] ?: return@get call.respond(mapOf("error" to "Missing file name"))
                val file = File("uploads/$id/audio/$name")
                if (file.exists()) {
                    call.response.headers.append("Content-Type", "audio/aac")
                    call.response.headers.append("Accept-Ranges", "bytes")
                    call.respondFile(file)
                } else {
                    call.respond(io.ktor.http.HttpStatusCode.NotFound)
                }
            }

            // REST Endpoint to fetch historical coordinates for routing (up to 30 days, max 5000 points)
            get("/api/device/{id}/telemetry-history") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 30) ?: 30
                val since = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000

                val history = transaction {
                    TelemetryTable.select {
                        (TelemetryTable.deviceId eq id) and (TelemetryTable.timestamp greaterEq since)
                    }
                        .orderBy(TelemetryTable.timestamp to SortOrder.ASC)
                        .limit(5000)
                        .map {
                            TelemetryPoint(
                                lat = it[TelemetryTable.lat],
                                lng = it[TelemetryTable.lng],
                                accuracy = it[TelemetryTable.accuracy],
                                timestamp = it[TelemetryTable.timestamp]
                            )
                        }
                }
                call.respond(history)
            }

            // REST Endpoint to fetch message history
            get("/api/device/{id}/messages-history") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val history = transaction {
                    MessagesTable.select { MessagesTable.deviceId eq id }
                        .orderBy(MessagesTable.timestamp to SortOrder.ASC)
                        .limit(200)
                        .map {
                            MessageItem(
                                id = it[MessagesTable.id],
                                direction = it[MessagesTable.direction],
                                content = it[MessagesTable.content],
                                timestamp = it[MessagesTable.timestamp]
                            )
                        }
                }
                call.respond(history)
            }

            // REST Endpoint to fetch historical security console logs
            get("/api/device/{id}/logs-history") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
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

            // REST Endpoint for AI chat assistant contextualized with device status
            post("/api/ai/chat") {
                try {
                    val params = call.receive<Map<String, String>>()
                    val userMsg = params["message"] ?: return@post call.respond(mapOf("error" to "Missing message"))
                    val deviceId = params["deviceId"]
                    
                    // Construct contextual information about the device
                    val contextBuilder = StringBuilder()
                    contextBuilder.append("Você é o 'ProtegeAI', o Assistente de Inteligência Artificial Anti-Furto integrado ao AndroidProtect.\n")
                    contextBuilder.append("Você ajuda o dono do celular a rastreá-lo, monitorá-lo e analisar o status de segurança do dispositivo.\n")
                    contextBuilder.append("Responda sempre de forma prestativa, direta, profissional, em português brasileiro.\n")
                    contextBuilder.append("Mantenha as respostas concisas e foque na segurança física do usuário (ex: alertar para não tentar recuperar o celular pessoalmente face ao risco de agressão, orientar a registrar um Boletim de Ocorrência).\n\n")
                    
                    if (!deviceId.isNullOrBlank()) {
                        val device = transaction {
                            DevicesTable.select { DevicesTable.id eq deviceId }.map {
                                DeviceInfo(
                                    deviceId = it[DevicesTable.id],
                                    model = it[DevicesTable.model],
                                    battery = it[DevicesTable.battery],
                                    isCharging = it[DevicesTable.isCharging],
                                    isOnline = it[DevicesTable.isOnline]
                                )
                            }.firstOrNull()
                        }
                        
                        if (device != null) {
                            contextBuilder.append("DADOS DO APARELHO MONITORADO:\n")
                            contextBuilder.append("- ID: ${device.deviceId}\n")
                            contextBuilder.append("- Modelo: ${device.model}\n")
                            contextBuilder.append("- Estado: ${if (device.isOnline) "ONLINE (Conectado ao painel)" else "OFFLINE (Desconectado)"}\n")
                            contextBuilder.append("- Bateria: ${device.battery}% (${if (device.isCharging) "Carregando" else "Descarregando"})\n")
                            
                            // Get latest telemetry coordinates
                            val telemetry = transaction {
                                TelemetryTable.select { TelemetryTable.deviceId eq deviceId }
                                    .orderBy(TelemetryTable.timestamp to SortOrder.DESC)
                                    .limit(1)
                                    .map {
                                        mapOf(
                                            "lat" to it[TelemetryTable.lat],
                                            "lng" to it[TelemetryTable.lng],
                                            "accuracy" to it[TelemetryTable.accuracy]
                                        )
                                    }.firstOrNull()
                            }
                            if (telemetry != null) {
                                contextBuilder.append("- Última Localização: Latitude ${telemetry["lat"]}, Longitude ${telemetry["lng"]} (Precisão: ${telemetry["accuracy"]}m)\n")
                            } else {
                                contextBuilder.append("- Última Localização: Nenhuma coordenada recebida ainda.\n")
                            }
                            
                            // Get latest 10 security logs
                            val logs = transaction {
                                LogsTable.select { LogsTable.deviceId eq deviceId }
                                    .orderBy(LogsTable.timestamp to SortOrder.DESC)
                                    .limit(10)
                                    .map { it[LogsTable.message] }
                                    .reversed()
                            }
                            if (logs.isNotEmpty()) {
                                contextBuilder.append("\nÚLTIMOS EVENTOS DE SEGURANÇA REGISTRADOS:\n")
                                logs.forEach { logMsg ->
                                    contextBuilder.append("* $logMsg\n")
                                }
                            }
                        }
                    } else {
                        contextBuilder.append("Nenhum aparelho foi selecionado ainda pelo usuário no painel.\n")
                    }
                    
                    contextBuilder.append("\nMENSAGEM DO USUÁRIO: $userMsg\n")
                    contextBuilder.append("PROTEGEAI:")
                    
                    val aiReply = callGeminiApi(contextBuilder.toString())
                    call.respond(mapOf("reply" to aiReply))
                } catch (e: Exception) {
                    println("API AI CHAT EXCEPTION: ${e.message}")
                    call.respond(io.ktor.http.HttpStatusCode.InternalServerError, mapOf("error" to "Failed to process request: ${e.message}"))
                }
            }

            // REST Endpoint for Photo Upload from Android
            post("/upload/photo/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                val multipart = call.receiveMultipart()
                val photosDir = File("uploads/$id/photos").apply { mkdirs() }
                var savedFile: File? = null

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

                    val event = """{"type":"PHOTO_UPLOADED","deviceId":"$id","url":"$fileUrl"}"""
                    broadcastToDashboards(event)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // REST Endpoint for Audio Upload from Android
            post("/upload/audio/{id}") {
                val id = call.parameters["id"] ?: return@post call.respond(mapOf("error" to "Missing device ID"))
                val multipart = call.receiveMultipart()
                val audioDir = File("uploads/$id/audio").apply { mkdirs() }
                var savedFile: File? = null

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

                    val event = """{"type":"AUDIO_UPLOADED","deviceId":"$id","url":"$fileUrl"}"""
                    broadcastToDashboards(event)
                    call.respond(mapOf("success" to true, "fileName" to savedFile!!.name))
                } else {
                    call.respond(mapOf("success" to false, "error" to "No file received"))
                }
            }

            // WebSocket connection for the Android app
            webSocket("/ws/device/{id}") {
                val deviceId = call.parameters["id"] ?: "unknown"
                deviceSessions[deviceId] = this
                println("Device connected: $deviceId")

                // Pre-populate device status
                val model = call.request.queryParameters["model"] ?: "Android Device"
                val battery = call.request.queryParameters["battery"]?.toIntOrNull() ?: 100
                val isCharging = call.request.queryParameters["charging"]?.toBoolean() ?: false
                
                // Write Device Connection state to Database
                val info = transaction {
                    val exists = DevicesTable.select { DevicesTable.id eq deviceId }.count() > 0
                    if (exists) {
                        DevicesTable.update({ DevicesTable.id eq deviceId }) {
                            it[DevicesTable.model] = model
                            it[DevicesTable.battery] = battery
                            it[DevicesTable.isCharging] = isCharging
                            it[DevicesTable.isOnline] = true
                            it[DevicesTable.lastSeen] = System.currentTimeMillis()
                        }
                    } else {
                        DevicesTable.insert {
                            it[DevicesTable.id] = deviceId
                            it[DevicesTable.model] = model
                            it[DevicesTable.battery] = battery
                            it[DevicesTable.isCharging] = isCharging
                            it[DevicesTable.isOnline] = true
                            it[DevicesTable.lastSeen] = System.currentTimeMillis()
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

                // Notify dashboards
                broadcastToDashboards(packetJson.encodeToString(DeviceConnectedPacket(device = info)))

                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                if (text.startsWith("{")) {
                                    try {
                                        val json = Json.parseToJsonElement(text).jsonObject
                                        val type = json["type"]?.jsonPrimitive?.content
                                        
                                        if (type == "MESSAGE") {
                            val msg = json["content"]?.jsonPrimitive?.content ?: ""
                            if (msg.isNotBlank()) {
                                val savedId = transaction {
                                    MessagesTable.insert {
                                        it[MessagesTable.deviceId] = deviceId
                                        it[direction] = "in"
                                        it[content] = msg
                                        it[timestamp] = System.currentTimeMillis()
                                    } get MessagesTable.id
                                }
                                val event = """{"type":"NEW_MESSAGE","deviceId":"$deviceId","direction":"in","content":${Json.encodeToString(msg)},"timestamp":${System.currentTimeMillis()},"id":$savedId}"""
                                broadcastToDashboards(event)
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
                                        }

                                        // Relay JSON telemetry directly to dashboards
                                        broadcastToDashboards(text)
                                    } catch (e: Exception) {
                                        // Fallback relay in case of parsing errors
                                        broadcastToDashboards(text)
                                    }
                                } else {
                                    broadcastToDashboards(text)
                                }
                            }
                            is Frame.Binary -> {
                                // Screen capture streaming frame (JPEG)
                                val binaryData = frame.readBytes()
                                // Relay screen frame bytes to dashboard WebSocket
                                for (dash in dashboardSessions.keys) {
                                    dash.send(Frame.Binary(true, binaryData))
                                }
                            }
                            else -> {}
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    println("Device disconnected: $deviceId")
                } catch (e: Throwable) {
                    println("Device connection error ($deviceId): ${e.message}")
                } finally {
                    deviceSessions.remove(deviceId)
                    
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

                    broadcastToDashboards(packetJson.encodeToString(DeviceDisconnectedPacket(deviceId = deviceId)))
                }
            }

            // WebSocket connection for the Web Dashboard
            webSocket("/ws/dashboard") {
                dashboardSessions[this] = true
                println("Dashboard connected")

                // Immediately read devices from database and send device list
                val list = transaction {
                    DevicesTable.selectAll().map {
                        DeviceInfo(
                            deviceId = it[DevicesTable.id],
                            model = it[DevicesTable.model],
                            battery = it[DevicesTable.battery],
                            isCharging = it[DevicesTable.isCharging],
                            isOnline = it[DevicesTable.isOnline]
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
                                    if (command == "SEND_MESSAGE") {
                                        val msgContent = json["message"]?.jsonPrimitive?.content ?: ""
                                        if (msgContent.isNotBlank()) {
                                            val now = System.currentTimeMillis()
                                            val savedId = transaction {
                                                MessagesTable.insert {
                                                    it[deviceId] = destDeviceId
                                                    it[direction] = "out"
                                                    it[content] = msgContent
                                                    it[timestamp] = now
                                                } get MessagesTable.id
                                            }
                                            // Relay to device
                                            deviceSessions[destDeviceId]?.send(Frame.Text(text))
                                            // Echo to all dashboards so everyone sees the sent message
                                            val event = """{"type":"NEW_MESSAGE","deviceId":"$destDeviceId","direction":"out","content":${Json.encodeToString(msgContent)},"timestamp":$now,"id":$savedId}"""
                                            broadcastToDashboards(event)
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
        }
    }.start(wait = true)
}

// Utility to broadcast a text event to all dashboards
suspend fun broadcastToDashboards(event: String) {
    for (dash in dashboardSessions.keys) {
        try {
            dash.send(Frame.Text(event))
        } catch (e: Exception) {
            dashboardSessions.remove(dash)
        }
    }
}

// Helper extension
fun kotlinx.serialization.json.JsonElement.asObjectOrNull() = this as? kotlinx.serialization.json.JsonObject
