package com.androidprotect.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

// kotlinx.serialization cannot handle Map<String, Any?> at runtime.
// This helper converts arbitrary data to JsonElement so we can respond
// with respondText(json, ContentType.Application.Json) safely.
private fun Any?.toJson(): JsonElement = when (this) {
    null        -> JsonNull
    is Boolean  -> JsonPrimitive(this)
    is Number   -> JsonPrimitive(this)
    is String   -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
    is List<*>  -> JsonArray(map { it.toJson() })
    else        -> JsonPrimitive(toString())
}

private suspend fun ApplicationCall.respondJson(data: Any?) {
    // JsonElement.toString() produces valid JSON — avoids needing explicit serializer
    respondText(data.toJson().toString(), ContentType.Application.Json)
}

fun Routing.superAdminRoutes() {

    // ── POST /api/superadmin/login ───────────────────────────────────────
    post("/api/superadmin/login") {
        try {
            val body     = call.receive<Map<String, String>>()
            val email    = body["email"]?.trim()?.lowercase() ?: return@post call.respond(mapOf("error" to "Email obrigatório"))
            val password = body["password"] ?: return@post call.respond(mapOf("error" to "Senha obrigatória"))

            val admin = transaction {
                SuperAdminTable.select { SuperAdminTable.email eq email }.firstOrNull()
            } ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciais inválidas"))

            if (!verifyPassword(password, admin[SuperAdminTable.passHash]))
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciais inválidas"))

            val token = generateSessionToken()
            transaction {
                SuperAdminSessionsTable.insert {
                    it[SuperAdminSessionsTable.token]     = token
                    it[SuperAdminSessionsTable.expiresAt] = System.currentTimeMillis() + 8L * 60 * 60 * 1000 // 8h
                }
            }
            call.respond(mapOf("token" to token, "email" to email))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
        }
    }

    // ── POST /api/superadmin/logout ────────────────────────────────────────
    post("/api/superadmin/logout") {
        val header = call.request.headers["Authorization"] ?: return@post call.respond(mapOf("ok" to true))
        val token  = header.removePrefix("Bearer ").trim()
        transaction { SuperAdminSessionsTable.deleteWhere { SuperAdminSessionsTable.token eq token } }
        call.respond(mapOf("ok" to true))
    }

    // ── GET /api/superadmin/dashboard ───────────────────────────────────────
    get("/api/superadmin/dashboard") {
        if (getSuperAdminToken(call) == null)
            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {

        val now = System.currentTimeMillis()
        val monthStart = now - 30L * 24 * 60 * 60 * 1000

        val stats = transaction {
            val totalUsers  = UsersTable.selectAll().count()
            val trialUsers  = SubscriptionsTable.select {
                (SubscriptionsTable.status eq "trial") and (SubscriptionsTable.endDate greaterEq now)
            }.count()
            val activeUsers = SubscriptionsTable.select {
                (SubscriptionsTable.status eq "active") and (SubscriptionsTable.endDate greaterEq now)
            }.count()
            val expiredUsers = SubscriptionsTable.select {
                SubscriptionsTable.status eq "expired"
            }.count()

            // Monthly revenue (paid payments in last 30d)
            val monthlyRevenueCents = PaymentsTable.select {
                (PaymentsTable.status eq "paid") and (PaymentsTable.paidAt greaterEq monthStart)
            }.sumOf { it[PaymentsTable.amountCents] }

            // Total revenue all time
            val totalRevenueCents = PaymentsTable.select {
                PaymentsTable.status eq "paid"
            }.sumOf { it[PaymentsTable.amountCents] }

            // Users per plan
            val planStats = PlansTable.selectAll().map { plan ->
                val planId   = plan[PlansTable.id]
                val planName = plan[PlansTable.name]
                val activeCount = SubscriptionsTable.select {
                    (SubscriptionsTable.planId eq planId) and
                    (SubscriptionsTable.status eq "active") and
                    (SubscriptionsTable.endDate greaterEq now)
                }.count()
                mapOf("planId" to planId, "planName" to planName, "activeCount" to activeCount)
            }

            // Monthly revenue by day (last 30 days)
            val dailyRevenue = mutableMapOf<String, Int>()
            PaymentsTable.select {
                (PaymentsTable.status eq "paid") and (PaymentsTable.paidAt greaterEq monthStart)
            }.forEach { row ->
                val paidAt = row[PaymentsTable.paidAt] ?: return@forEach
                val day = java.time.Instant.ofEpochMilli(paidAt)
                    .atZone(java.time.ZoneId.of("America/Sao_Paulo"))
                    .toLocalDate().toString()
                dailyRevenue[day] = (dailyRevenue[day] ?: 0) + row[PaymentsTable.amountCents]
            }

            mapOf(
                "totalUsers"          to totalUsers,
                "trialUsers"          to trialUsers,
                "activeUsers"         to activeUsers,
                "expiredUsers"        to expiredUsers,
                "monthlyRevenueCents" to monthlyRevenueCents,
                "monthlyRevenueStr"   to centsToBrl(monthlyRevenueCents),
                "totalRevenueCents"   to totalRevenueCents,
                "totalRevenueStr"     to centsToBrl(totalRevenueCents),
                "planStats"           to planStats,
                "dailyRevenue"        to dailyRevenue.entries.sortedBy { it.key }
                    .map { mapOf("date" to it.key, "cents" to it.value, "str" to centsToBrl(it.value)) }
            )
        }
        call.respondJson(stats)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno no dashboard")))
        }
    }

    // ── GET /api/superadmin/plans ──────────────────────────────────────────
    get("/api/superadmin/plans") {
        if (getSuperAdminToken(call) == null)
            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {
        val plans = transaction {
            PlansTable.selectAll().orderBy(PlansTable.sortOrder to SortOrder.ASC).map {
                mapOf(
                    "id"          to it[PlansTable.id],
                    "name"        to it[PlansTable.name],
                    "description" to it[PlansTable.description],
                    "priceCents"  to it[PlansTable.priceCents],
                    "priceStr"    to centsToBrl(it[PlansTable.priceCents]),
                    "durationDays" to it[PlansTable.durationDays],
                    "maxDevices"  to it[PlansTable.maxDevices],
                    "imageUrl"    to it[PlansTable.imageUrl],
                    "isActive"    to it[PlansTable.isActive],
                    "sortOrder"   to it[PlansTable.sortOrder],
                    "createdAt"   to it[PlansTable.createdAt]
                )
            }
        }
        call.respondJson(plans)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro ao listar planos")))
        }
    }

    // ── POST /api/superadmin/plans — create plan ─────────────────────────────────
    post("/api/superadmin/plans") {
        if (getSuperAdminToken(call) == null)
            return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {
            val body = call.receive<Map<String, String>>()
            val name     = body["name"]?.trim() ?: return@post call.respond(mapOf("error" to "Nome obrigatório"))
            val price    = body["priceCents"]?.toIntOrNull() ?: return@post call.respond(mapOf("error" to "Preço inválido"))
            val duration = body["durationDays"]?.toIntOrNull() ?: return@post call.respond(mapOf("error" to "Duração inválida"))
            val maxDev   = body["maxDevices"]?.toIntOrNull() ?: 1
            val desc     = body["description"] ?: ""
            val imageUrl = body["imageUrl"] ?: ""
            val order    = body["sortOrder"]?.toIntOrNull() ?: 99

            val newId = transaction {
                PlansTable.insert {
                    it[PlansTable.name]        = name
                    it[PlansTable.description] = desc
                    it[PlansTable.priceCents]  = price
                    it[PlansTable.durationDays]= duration
                    it[PlansTable.maxDevices]  = maxDev
                    it[PlansTable.imageUrl]    = imageUrl
                    it[PlansTable.isActive]    = true
                    it[PlansTable.sortOrder]   = order
                    it[PlansTable.createdAt]   = System.currentTimeMillis()
                } get PlansTable.id
            }
            call.respond(mapOf("ok" to true, "id" to newId))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
        }
    }

    // ── PUT /api/superadmin/plans/{id} — update plan ────────────────────────────
    put("/api/superadmin/plans/{id}") {
        if (getSuperAdminToken(call) == null)
            return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        val planId = call.parameters["id"]?.toIntOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido"))
        try {
            val body = call.receive<Map<String, String>>()
            val updated = transaction {
                PlansTable.update({ PlansTable.id eq planId }) {
                    body["name"]?.trim()?.let { v -> it[PlansTable.name] = v }
                    body["description"]?.let { v -> it[PlansTable.description] = v }
                    body["priceCents"]?.toIntOrNull()?.let { v -> it[PlansTable.priceCents] = v }
                    body["durationDays"]?.toIntOrNull()?.let { v -> it[PlansTable.durationDays] = v }
                    body["maxDevices"]?.toIntOrNull()?.let { v -> it[PlansTable.maxDevices] = v }
                    body["imageUrl"]?.let { v -> it[PlansTable.imageUrl] = v }
                    body["isActive"]?.toBooleanStrictOrNull()?.let { v -> it[PlansTable.isActive] = v }
                    body["sortOrder"]?.toIntOrNull()?.let { v -> it[PlansTable.sortOrder] = v }
                }
            }
            if (updated == 0) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Plano não encontrado"))
            else call.respond(mapOf("ok" to true))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
        }
    }

    // ── DELETE /api/superadmin/plans/{id} ─────────────────────────────────────────
    delete("/api/superadmin/plans/{id}") {
        if (getSuperAdminToken(call) == null)
            return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        val planId = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido"))
        // Soft-delete: deactivate instead of physical delete
        val updated = transaction {
            PlansTable.update({ PlansTable.id eq planId }) { it[PlansTable.isActive] = false }
        }
        if (updated == 0) call.respond(HttpStatusCode.NotFound, mapOf("error" to "Plano não encontrado"))
        else call.respond(mapOf("ok" to true))
    }

    // ── POST /api/superadmin/plans/{id}/image — upload plan image ─────────────────
    post("/api/superadmin/plans/{id}/image") {
        if (getSuperAdminToken(call) == null)
            return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        val planId = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido"))
        val multipart = call.receiveMultipart()
        val dir = File("uploads/plans").apply { mkdirs() }
        var fileUrl = ""
        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                val ext  = (part.originalFileName ?: "img").substringAfterLast('.', "jpg").lowercase()
                val name = "plan_${planId}_${System.currentTimeMillis()}.$ext"
                val file = File(dir, name)
                part.streamProvider().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                fileUrl = "/uploads/plans/$name"
                transaction {
                    PlansTable.update({ PlansTable.id eq planId }) { it[PlansTable.imageUrl] = fileUrl }
                }
            }
            part.dispose()
        }
        if (fileUrl.isBlank()) call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Nenhum arquivo recebido"))
        else call.respond(mapOf("ok" to true, "url" to fileUrl))
    }

    // Serve plan images
    get("/uploads/plans/{name}") {
        val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val file = File("uploads/plans/$name")
        if (file.exists()) call.respondFile(file)
        else call.respond(HttpStatusCode.NotFound)
    }

    // ── GET /api/superadmin/users ──────────────────────────────────────────────
    get("/api/superadmin/users") {
        if (getSuperAdminToken(call) == null)
            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {

        val now  = System.currentTimeMillis()
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val size = 50
        val offset = ((page - 1) * size).toLong()

        // Pre-load all plans to avoid nested transactions (SQLite pool=1 deadlocks)
        val planNamesById: Map<Int, String> = transaction {
            PlansTable.selectAll().associate { it[PlansTable.id] to it[PlansTable.name] }
        }

        val (users, total) = transaction {
            val rows = UsersTable.selectAll()
                .orderBy(UsersTable.createdAt to SortOrder.DESC)
                .limit(size, offset)
                .map { u ->
                    val uid = u[UsersTable.id]
                    val sub = SubscriptionsTable.select {
                        (SubscriptionsTable.userId eq uid) and (SubscriptionsTable.status neq "cancelled")
                    }.orderBy(SubscriptionsTable.id to SortOrder.DESC).firstOrNull()

                    val subStatus = sub?.let {
                        val s = it[SubscriptionsTable.status]
                        val e = it[SubscriptionsTable.endDate]
                        if (e < now && s in listOf("trial", "active")) "expired" else s
                    } ?: "none"

                    // Use pre-loaded plan names — no nested transaction needed
                    val planName = sub?.get(SubscriptionsTable.planId)?.let { pid ->
                        planNamesById[pid]
                    }

                    val deviceCount = DevicesTable.select {
                        DevicesTable.ownerId eq uid
                    }.count()

                    mapOf(
                        "id"        to uid,
                        "email"     to u[UsersTable.email],
                        "username"  to u[UsersTable.username],
                        "createdAt" to u[UsersTable.createdAt],
                        "subStatus" to subStatus,
                        "planName"  to (planName ?: if (subStatus == "trial") "Trial" else "-"),
                        "subEnd"    to (sub?.get(SubscriptionsTable.endDate) ?: 0L), // never null
                        "devices"   to deviceCount
                    )
                }
            val count = UsersTable.selectAll().count()
            Pair(rows, count)
        }
        call.respondJson(mapOf("users" to users, "total" to total, "page" to page, "size" to size))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno ao listar usuários")))
        }
    }

    // ── GET /api/superadmin/settings ──────────────────────────────────────────
    get("/api/superadmin/settings") {
        if (getSuperAdminToken(call) == null)
            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {
        val settings = transaction {
            AppSettingsTable.selectAll().associate { it[AppSettingsTable.key] to it[AppSettingsTable.value] }
        }
        val admin = transaction { SuperAdminTable.selectAll().firstOrNull()?.get(SuperAdminTable.email) }
        call.respondJson(settings + mapOf("adminEmail" to (admin ?: "")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro ao carregar configurações")))
        }
    }

    // ── PUT /api/superadmin/settings ──────────────────────────────────────────
    put("/api/superadmin/settings") {
        if (getSuperAdminToken(call) == null)
            return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {
            val body = call.receive<Map<String, String>>()
            body["trial_days"]?.toIntOrNull()?.let { v ->
                setSetting("trial_days", v.coerceIn(1, 365).toString())
            }
            call.respond(mapOf("ok" to true))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
        }
    }

    // ── PUT /api/superadmin/password ──────────────────────────────────────────
    put("/api/superadmin/password") {
        if (getSuperAdminToken(call) == null)
            return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {
            val body        = call.receive<Map<String, String>>()
            val currentPass = body["currentPassword"] ?: return@put call.respond(mapOf("error" to "Senha atual obrigatória"))
            val newPass     = body["newPassword"] ?: return@put call.respond(mapOf("error" to "Nova senha obrigatória"))
            if (newPass.length < 8) return@put call.respond(mapOf("error" to "Senha deve ter pelo menos 8 caracteres"))

            val admin = transaction { SuperAdminTable.selectAll().firstOrNull() }
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Admin não encontrado"))

            if (!verifyPassword(currentPass, admin[SuperAdminTable.passHash]))
                return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Senha atual incorreta"))

            transaction {
                SuperAdminTable.update({ SuperAdminTable.id eq admin[SuperAdminTable.id] }) {
                    it[passHash] = hashPassword(newPass)
                }
            }
            call.respond(mapOf("ok" to true))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro interno")))
        }
    }

    // ── DELETE /api/superadmin/users/{id} — delete user ──────────────────
    delete("/api/superadmin/users/{id}") {
        if (getSuperAdminToken(call) == null)
            return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        val userId = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido"))
        try {
            transaction {
                PaymentsTable.deleteWhere      { PaymentsTable.userId eq userId }
                SubscriptionsTable.deleteWhere { SubscriptionsTable.userId eq userId }
                SessionsTable.deleteWhere      { SessionsTable.userId eq userId }
                DevicesTable.update({ DevicesTable.ownerId eq userId }) { it[DevicesTable.ownerId] = null }
                UsersTable.deleteWhere         { UsersTable.id eq userId }
            }
            call.respond(mapOf("ok" to true))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro ao excluir usuário")))
        }
    }

    // ── PUT /api/superadmin/users/{id}/plan — force-assign plan ──────────
    put("/api/superadmin/users/{id}/plan") {
        if (getSuperAdminToken(call) == null)
            return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        val userId = call.parameters["id"]?.toIntOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID inválido"))
        try {
            val body   = call.receive<Map<String, String>>()
            val action = body["action"] ?: "assign"

            when (action) {
                "cancel" -> {
                    transaction {
                        SubscriptionsTable.update({
                            (SubscriptionsTable.userId eq userId) and
                            (SubscriptionsTable.status.inList(listOf("trial","active")))
                        }) { it[SubscriptionsTable.status] = "cancelled" }
                    }
                    call.respond(mapOf("ok" to true, "message" to "Assinatura cancelada"))
                }
                "trial" -> {
                    val trialDays = body["days"]?.toIntOrNull() ?: 7
                    val now = System.currentTimeMillis()
                    transaction {
                        SubscriptionsTable.update({
                            (SubscriptionsTable.userId eq userId) and
                            (SubscriptionsTable.status.inList(listOf("trial","active")))
                        }) { it[SubscriptionsTable.status] = "cancelled" }
                        SubscriptionsTable.insert {
                            it[SubscriptionsTable.userId]    = userId
                            it[SubscriptionsTable.planId]    = null
                            it[SubscriptionsTable.status]    = "trial"
                            it[SubscriptionsTable.startDate] = now
                            it[SubscriptionsTable.endDate]   = now + trialDays * 24L * 60 * 60 * 1000
                            it[SubscriptionsTable.createdAt] = now
                        }
                    }
                    call.respond(mapOf("ok" to true, "message" to "Trial de $trialDays dias ativado"))
                }
                else -> {
                    val planId = body["planId"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "planId obrigatório"))
                    val plan = transaction { PlansTable.select { PlansTable.id eq planId }.firstOrNull() }
                        ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Plano não encontrado"))
                    val days = body["days"]?.toIntOrNull() ?: plan[PlansTable.durationDays]
                    val now  = System.currentTimeMillis()
                    val subId = transaction {
                        SubscriptionsTable.update({
                            (SubscriptionsTable.userId eq userId) and
                            (SubscriptionsTable.status.inList(listOf("trial","active")))
                        }) { it[SubscriptionsTable.status] = "cancelled" }
                        SubscriptionsTable.insert {
                            it[SubscriptionsTable.userId]    = userId
                            it[SubscriptionsTable.planId]    = planId
                            it[SubscriptionsTable.status]    = "active"
                            it[SubscriptionsTable.startDate] = now
                            it[SubscriptionsTable.endDate]   = now + days * 24L * 60 * 60 * 1000
                            it[SubscriptionsTable.createdAt] = now
                        } get SubscriptionsTable.id
                    }
                    transaction {
                        UsersTable.update({ UsersTable.id eq userId }) {
                            it[UsersTable.maxDevices] = plan[PlansTable.maxDevices]
                        }
                    }
                    call.respond(mapOf("ok" to true, "subscriptionId" to subId,
                        "message" to "Plano ${plan[PlansTable.name]} ativado por $days dias"))
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro ao alterar plano")))
        }
    }

    // ── GET /api/superadmin/payments ──────────────────────────────────────────
    get("/api/superadmin/payments") {
        if (getSuperAdminToken(call) == null)
            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Não autenticado"))
        try {
        val page   = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val offset = ((page - 1) * 50).toLong()

        val payments = transaction {
            PaymentsTable.selectAll()
                .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
                .limit(50, offset)
                .map { p ->
                    val email = UsersTable.select { UsersTable.id eq p[PaymentsTable.userId] }
                        .firstOrNull()?.get(UsersTable.email) ?: "?"
                    val planName = PlansTable.select { PlansTable.id eq p[PaymentsTable.planId] }
                        .firstOrNull()?.get(PlansTable.name) ?: "?"
                    mapOf(
                        "id"          to p[PaymentsTable.id],
                        "userEmail"   to email,
                        "planName"    to planName,
                        "amountStr"   to centsToBrl(p[PaymentsTable.amountCents]),
                        "method"      to p[PaymentsTable.method],
                        "status"      to p[PaymentsTable.status],
                        "createdAt"   to p[PaymentsTable.createdAt],
                        "paidAt"      to p[PaymentsTable.paidAt]
                    )
                }
        }
        call.respondJson(mapOf("payments" to payments))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Erro ao listar pagamentos")))
        }
    }
}
