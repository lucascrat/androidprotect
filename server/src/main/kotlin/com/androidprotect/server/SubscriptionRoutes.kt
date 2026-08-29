package com.androidprotect.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction

// Shared JSON helper — kotlinx.serialization cannot handle Map<String,Any?> at runtime
internal fun Any?.toJsonEl(): JsonElement = when (this) {
    null         -> JsonNull
    is Boolean   -> JsonPrimitive(this)
    is Number    -> JsonPrimitive(this)
    is String    -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonEl() })
    is List<*>   -> JsonArray(map { it.toJsonEl() })
    else         -> JsonPrimitive(toString())
}
internal suspend fun ApplicationCall.respondJson2(data: Any?) =
    respondText(data.toJsonEl().toString(), ContentType.Application.Json)

internal suspend fun ApplicationCall.respondJson2(status: HttpStatusCode, data: Any?) =
    respondText(data.toJsonEl().toString(), ContentType.Application.Json, status)

fun Routing.subscriptionRoutes() {

    // ── GET /api/plans — list active plans (public) ─────────────────────────────
    get("/api/plans") {
        val plans = transaction {
            PlansTable.select { PlansTable.isActive eq true }
                .orderBy(PlansTable.sortOrder to SortOrder.ASC)
                .map {
                    mapOf(
                        "id"          to it[PlansTable.id],
                        "name"        to it[PlansTable.name],
                        "description" to it[PlansTable.description],
                        "priceCents"  to it[PlansTable.priceCents],
                        "priceStr"    to centsToBrl(it[PlansTable.priceCents]),
                        "durationDays" to it[PlansTable.durationDays],
                        "maxDevices"  to it[PlansTable.maxDevices],
                        "imageUrl"    to it[PlansTable.imageUrl]
                    )
                }
        }
        call.respondJson2(plans)
    }

    // ── GET /api/subscription/status ──────────────────────────────────────────
    get("/api/subscription/status") {
        val userId = getSessionUserId(call)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        call.respondJson2(getUserSubscriptionStatus(userId))
    }

    // ── POST /api/subscription/pay — create PIX payment ────────────────────────
    post("/api/subscription/pay") {
        val userId = getSessionUserId(call)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))

        try {
            val body   = call.receive<Map<String, String>>()
            val planId = body["planId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "planId obrigatório"))

            val plan = transaction { PlansTable.select { (PlansTable.id eq planId) and (PlansTable.isActive eq true) }.firstOrNull() }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Plano não encontrado"))

            val amountCents = plan[PlansTable.priceCents]
            val planName    = plan[PlansTable.name]
            val now         = System.currentTimeMillis()

            // Create pending payment record first
            val paymentId = transaction {
                PaymentsTable.insert {
                    it[PaymentsTable.userId]      = userId
                    it[PaymentsTable.planId]      = planId
                    it[PaymentsTable.amountCents] = amountCents
                    it[PaymentsTable.method]      = "pix"
                    it[PaymentsTable.status]      = "pending"
                    it[PaymentsTable.createdAt]   = now
                } get PaymentsTable.id
            }

            // Call Efi API if configured
            val pixResult = if (EfiClient.isConfigured()) {
                EfiClient.createPixCharge(amountCents, "AndroidProtect – Plano $planName")
            } else null

            if (pixResult != null) {
                transaction {
                    PaymentsTable.update({ PaymentsTable.id eq paymentId }) {
                        it[PaymentsTable.efiTxid]         = pixResult.txid
                        it[PaymentsTable.efiLocId]        = pixResult.locId
                        it[PaymentsTable.pixCopiaECola]   = pixResult.pixCopiaECola
                        it[PaymentsTable.pixQrcodeBase64] = pixResult.imagemQrcode
                    }
                }
                call.respondJson2(mapOf(
                    "paymentId"    to paymentId,
                    "method"       to "pix",
                    "status"       to "pending",
                    "autoConfirm"  to true,
                    "pixCode"      to pixResult.pixCopiaECola,
                    "pixQrcode"    to pixResult.imagemQrcode,
                    "amountCents"  to amountCents,
                    "amountStr"    to centsToBrl(amountCents),
                    "planName"     to planName
                ))
            } else {
                // API Efi não configurada: gera PIX estático a partir da chave.
                // O cliente consegue pagar; a confirmação é manual no painel superadmin
                // (sem a API não há webhook de confirmação automática).
                val txid    = "AP%08d".format(paymentId)
                val payload = PixBrCode.buildPayload(amountCents, txid)
                val qrcode  = PixBrCode.qrCodeSvgDataUri(payload)

                transaction {
                    PaymentsTable.update({ PaymentsTable.id eq paymentId }) {
                        it[PaymentsTable.efiTxid]         = txid
                        it[PaymentsTable.pixCopiaECola]   = payload
                        it[PaymentsTable.pixQrcodeBase64] = qrcode
                    }
                }
                call.respondJson2(mapOf(
                    "paymentId"   to paymentId,
                    "method"      to "pix",
                    "status"      to "pending",
                    "autoConfirm" to false,
                    "pixCode"     to payload,
                    "pixQrcode"   to qrcode,
                    "pixKey"      to PixBrCode.pixKey(),
                    "amountCents" to amountCents,
                    "amountStr"   to centsToBrl(amountCents),
                    "planName"    to planName
                ))
            }
        } catch (e: Exception) {
            println("PAYMENT: Error creating payment: ${e.message}")
            call.respondJson2(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
        }
    }

    // ── GET /api/payment/{id} — check payment status ────────────────────────────
    get("/api/payment/{id}") {
        val userId    = getSessionUserId(call)
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        val paymentId = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido"))

        val payment = transaction {
            PaymentsTable.select { (PaymentsTable.id eq paymentId) and (PaymentsTable.userId eq userId) }.firstOrNull()
        } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pagamento não encontrado"))

        var currentStatus = payment[PaymentsTable.status]

        // If still pending, check with Efi
        if (currentStatus == "pending") {
            val txid = payment[PaymentsTable.efiTxid]
            if (txid != null && EfiClient.isConfigured()) {
                val efiStatus = EfiClient.checkPixStatus(txid)
                if (efiStatus == "paid") {
                    val planId = payment[PaymentsTable.planId]
                    val subId  = activateSubscription(userId, planId)
                    transaction {
                        PaymentsTable.update({ PaymentsTable.id eq paymentId }) {
                            it[PaymentsTable.status]         = "paid"
                            it[PaymentsTable.subscriptionId] = subId
                            it[PaymentsTable.paidAt]         = System.currentTimeMillis()
                        }
                    }
                    currentStatus = "paid"
                }
            }
        }

        call.respondJson2(mapOf(
            "paymentId"   to paymentId,
            "status"      to currentStatus,
            "method"      to payment[PaymentsTable.method],
            "amountCents" to payment[PaymentsTable.amountCents],
            "amountStr"   to centsToBrl(payment[PaymentsTable.amountCents]),
            "pixCode"     to payment[PaymentsTable.pixCopiaECola],
            "pixQrcode"   to payment[PaymentsTable.pixQrcodeBase64],
            "paidAt"      to payment[PaymentsTable.paidAt]
        ))
    }

    // ── POST /api/efi/webhook/pix — Efi payment notification ─────────────────
    post("/api/efi/webhook/pix") {
        try {
            val body = call.receiveText()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body).jsonObject

            val pixArray = root["pix"]?.jsonArray ?: run {
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
                return@post
            }

            for (pixEl in pixArray) {
                val pix  = pixEl.jsonObject
                val txid = pix["txid"]?.jsonPrimitive?.content ?: continue

                // Find and update the payment
                val payment = transaction {
                    PaymentsTable.select { PaymentsTable.efiTxid eq txid }.firstOrNull()
                } ?: continue

                if (payment[PaymentsTable.status] == "paid") continue

                val paymentId = payment[PaymentsTable.id]
                val userId    = payment[PaymentsTable.userId]
                val planId    = payment[PaymentsTable.planId]

                val subId = activateSubscription(userId, planId)
                transaction {
                    PaymentsTable.update({ PaymentsTable.id eq paymentId }) {
                        it[PaymentsTable.status]         = "paid"
                        it[PaymentsTable.subscriptionId] = subId
                        it[PaymentsTable.paidAt]         = System.currentTimeMillis()
                    }
                }
                println("EFI WEBHOOK: Payment $paymentId confirmed for user $userId plan $planId")
            }

            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        } catch (e: Exception) {
            println("EFI WEBHOOK: Error processing: ${e.message}")
            call.respond(HttpStatusCode.OK, mapOf("ok" to true)) // always 200 to Efi
        }
    }
}
