package com.androidprotect.server

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Efi (Efipay) PIX API client using mTLS authentication. */
object EfiClient {

    private val EFI_BASE = "https://pix.api.efipay.com.br"
    private val json     = Json { ignoreUnknownKeys = true }

    // Token cache
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L

    fun isConfigured(): Boolean {
        val certPath     = System.getenv("EFI_CERT_PATH")
        val clientId     = System.getenv("EFI_CLIENT_ID")
        val clientSecret = System.getenv("EFI_CLIENT_SECRET")
        val pixKey       = System.getenv("EFI_PIX_KEY")
        return !certPath.isNullOrBlank() && !clientId.isNullOrBlank() &&
               !clientSecret.isNullOrBlank() && !pixKey.isNullOrBlank()
    }

    private fun buildSslClient(): HttpClient {
        val certPath     = System.getenv("EFI_CERT_PATH") ?: return HttpClient.newHttpClient()
        val certPassword = (System.getenv("EFI_CERT_PASSWORD") ?: "").toCharArray()
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            File(certPath).inputStream().use { ks.load(it, certPassword) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, certPassword)
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(kmf.keyManagers, null, null)
            HttpClient.newBuilder().sslContext(ssl).build()
        } catch (e: Exception) {
            println("EFI: Failed to load cert: ${e.message}")
            HttpClient.newHttpClient()
        }
    }

    private fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiresAt - 60_000) return cachedToken

        val clientId     = System.getenv("EFI_CLIENT_ID")     ?: return null
        val clientSecret = System.getenv("EFI_CLIENT_SECRET") ?: return null
        val creds        = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

        val req = HttpRequest.newBuilder()
            .uri(URI.create("$EFI_BASE/oauth/token"))
            .header("Authorization", "Basic $creds")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"grant_type":"client_credentials"}"""))
            .build()

        return try {
            val res = buildSslClient().send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                println("EFI: Auth failed ${res.statusCode()}: ${res.body()}")
                return null
            }
            val body = json.parseToJsonElement(res.body()).jsonObject
            val token      = body["access_token"]?.jsonPrimitive?.content ?: return null
            val expiresIn  = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            cachedToken    = token
            tokenExpiresAt = now + expiresIn * 1000
            token
        } catch (e: Exception) {
            println("EFI: Auth error: ${e.message}")
            null
        }
    }

    data class PixResult(
        val txid: String,
        val locId: String,
        val pixCopiaECola: String,
        val imagemQrcode: String   // base64 PNG
    )

    fun createPixCharge(amountCents: Int, description: String): PixResult? {
        val token  = getAccessToken() ?: return null
        val pixKey = System.getenv("EFI_PIX_KEY") ?: return null
        val client = buildSslClient()

        val txid    = UUID.randomUUID().toString().replace("-", "").take(35)
        val valor   = BigDecimal(amountCents).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
        val safeDesc= description.take(140).replace("\"", "'")

        val body = """{"calendario":{"expiracao":3600},"valor":{"original":"$valor"},"chave":"$pixKey","solicitacaoPagador":"$safeDesc"}"""

        val cobReq = HttpRequest.newBuilder()
            .uri(URI.create("$EFI_BASE/v2/cob/$txid"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return try {
            val cobRes = client.send(cobReq, HttpResponse.BodyHandlers.ofString())
            if (cobRes.statusCode() !in 200..201) {
                println("EFI: Create charge failed ${cobRes.statusCode()}: ${cobRes.body()}")
                return null
            }
            val cobJson = json.parseToJsonElement(cobRes.body()).jsonObject
            val locId   = cobJson["loc"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return null

            // Get QR code
            val qrReq = HttpRequest.newBuilder()
                .uri(URI.create("$EFI_BASE/v2/loc/$locId/qrcode"))
                .header("Authorization", "Bearer $token")
                .GET().build()
            val qrRes  = client.send(qrReq, HttpResponse.BodyHandlers.ofString())
            val qrJson = json.parseToJsonElement(qrRes.body()).jsonObject
            val pixCode = qrJson["qrcode"]?.jsonPrimitive?.content ?: ""
            val qrImg   = qrJson["imagemQrcode"]?.jsonPrimitive?.content ?: ""

            PixResult(txid, locId, pixCode, qrImg)
        } catch (e: Exception) {
            println("EFI: createPixCharge error: ${e.message}")
            null
        }
    }

    /** Returns "active" or "pending" for a given txid. */
    fun checkPixStatus(txid: String): String {
        val token  = getAccessToken() ?: return "pending"
        val client = buildSslClient()
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$EFI_BASE/v2/cob/$txid"))
                .header("Authorization", "Bearer $token")
                .GET().build()
            val res  = client.send(req, HttpResponse.BodyHandlers.ofString())
            val body = json.parseToJsonElement(res.body()).jsonObject
            val st   = body["status"]?.jsonPrimitive?.content ?: "ATIVA"
            if (st == "CONCLUIDA") "paid" else "pending"
        } catch (e: Exception) {
            "pending"
        }
    }
}
