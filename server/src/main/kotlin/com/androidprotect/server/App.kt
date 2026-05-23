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

// Active device connections: deviceId -> WebSocketSession
val deviceSessions = ConcurrentHashMap<String, WebSocketSession>()

// Active dashboard connections: session -> true
val dashboardSessions = ConcurrentHashMap<WebSocketSession, Boolean>()

// Structure for tracking device info in memory relays
@Serializable
data class DeviceInfo(
    val deviceId: String,
    val model: String = "Android Device",
    val battery: Int = 100,
    val isCharging: Boolean = false,
    val isOnline: Boolean = true
)

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
        SchemaUtils.create(DevicesTable, TelemetryTable, LogsTable)
        
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

            // Serve uploaded files
            staticFiles("/uploads", File("uploads"))

            // REST endpoint to list photos and audios for a device
            get("/uploads/{id}/media-list") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val photosDir = File("uploads/$id/photos")
                val audioDir = File("uploads/$id/audio")

                val photos = if (photosDir.exists()) {
                    photosDir.listFiles()?.map { it.name }?.sortedDescending() ?: emptyList()
                } else emptyList()

                val audios = if (audioDir.exists()) {
                    audioDir.listFiles()?.map { it.name }?.sortedDescending() ?: emptyList()
                } else emptyList()

                call.respond(mapOf("photos" to photos, "audio" to audios))
            }

            // REST Endpoint to fetch historical coordinates (up to 100 points) for routing
            get("/api/device/{id}/telemetry-history") {
                val id = call.parameters["id"] ?: return@get call.respond(mapOf("error" to "Missing device ID"))
                val history = transaction {
                    TelemetryTable.select { TelemetryTable.deviceId eq id }
                        .orderBy(TelemetryTable.timestamp to SortOrder.DESC)
                        .limit(100)
                        .map {
                            mapOf(
                                "lat" to it[TelemetryTable.lat],
                                "lng" to it[TelemetryTable.lng],
                                "accuracy" to it[TelemetryTable.accuracy],
                                "timestamp" to it[TelemetryTable.timestamp]
                            )
                        }
                        .reversed()
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
                            mapOf(
                                "message" to it[LogsTable.message],
                                "type" to it[LogsTable.logType],
                                "timestamp" to it[LogsTable.timestamp]
                            )
                        }
                        .reversed()
                }
                call.respond(history)
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
                    val fileUrl = "/uploads/$id/photos/${savedFile!!.name}"
                    
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
                    val fileUrl = "/uploads/$id/audio/${savedFile!!.name}"
                    
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
                broadcastToDashboards(Json.encodeToString(mapOf(
                    "type" to "DEVICE_CONNECTED",
                    "device" to info
                )))

                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                if (text.startsWith("{")) {
                                    try {
                                        val json = Json.parseToJsonElement(text).jsonObject
                                        val type = json["type"]?.jsonPrimitive?.content
                                        
                                        if (type == "TELEMETRY") {
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

                    broadcastToDashboards(Json.encodeToString(mapOf(
                        "type" to "DEVICE_DISCONNECTED",
                        "deviceId" to deviceId
                    )))
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
                
                send(Json.encodeToString(mapOf(
                    "type" to "DEVICE_LIST",
                    "devices" to list
                )))

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val json = Json.parseToJsonElement(text)
                                val destDeviceId = json.let { it.asObjectOrNull()?.get("deviceId")?.toString()?.replace("\"", "") }
                                if (destDeviceId != null) {
                                    val devSession = deviceSessions[destDeviceId]
                                    if (devSession != null) {
                                        // Relay command over WebSocket to targeted Android device
                                        devSession.send(Frame.Text(text))
                                    } else {
                                        send(Json.encodeToString(mapOf(
                                            "type" to "ERROR",
                                            "message" to "Device $destDeviceId is offline"
                                        )))
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
