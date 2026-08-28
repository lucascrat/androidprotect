package com.androidprotect.server

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction

// ── Monetization Tables ────────────────────────────────────────────────────

object PlansTable : Table("plans") {
    val id          = integer("id").autoIncrement()
    val name        = varchar("name", 100)
    val description = text("description").default("")
    val priceCents  = integer("price_cents")       // e.g. 2990 = R$ 29,90
    val durationDays= integer("duration_days")
    val maxDevices  = integer("max_devices").default(1)
    val imageUrl    = varchar("image_url", 500).default("")
    val isActive    = bool("is_active").default(true)
    val sortOrder   = integer("sort_order").default(0)
    val createdAt   = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object SubscriptionsTable : Table("subscriptions") {
    val id        = integer("id").autoIncrement()
    val userId    = integer("user_id")
    val planId    = integer("plan_id").nullable()  // null = trial
    val status    = varchar("status", 20)           // trial | active | expired | cancelled
    val startDate = long("start_date")
    val endDate   = long("end_date")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PaymentsTable : Table("payments") {
    val id              = integer("id").autoIncrement()
    val userId          = integer("user_id")
    val planId          = integer("plan_id")
    val subscriptionId  = integer("subscription_id").nullable()
    val amountCents     = integer("amount_cents")
    val method          = varchar("method", 20)         // pix | card
    val status          = varchar("status", 20)         // pending | paid | failed | expired
    val efiTxid         = varchar("efi_txid", 100).nullable()
    val efiLocId        = varchar("efi_loc_id", 100).nullable()
    val pixCopiaECola   = text("pix_copia_e_cola").nullable()
    val pixQrcodeBase64 = text("pix_qrcode_base64").nullable()
    val createdAt       = long("created_at")
    val paidAt          = long("paid_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AppSettingsTable : Table("app_settings") {
    val key   = varchar("key", 100)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}

object SuperAdminTable : Table("superadmin") {
    val id       = integer("id").autoIncrement()
    val email    = varchar("email", 255).uniqueIndex()
    val passHash = varchar("pass_hash", 255)
    override val primaryKey = PrimaryKey(id)
}

object SuperAdminSessionsTable : Table("superadmin_sessions") {
    val token     = varchar("token", 100)
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(token)
}

// ── Helpers ────────────────────────────────────────────────────────────────

fun getSetting(key: String, default: String = ""): String =
    transaction {
        AppSettingsTable.select { AppSettingsTable.key eq key }
            .firstOrNull()?.get(AppSettingsTable.value) ?: default
    }

fun setSetting(key: String, value: String) = transaction {
    val updated = AppSettingsTable.update({ AppSettingsTable.key eq key }) { it[AppSettingsTable.value] = value }
    if (updated == 0) AppSettingsTable.insert { it[AppSettingsTable.key] = key; it[AppSettingsTable.value] = value }
}

fun getTrialDays(): Long = getSetting("trial_days", "7").toLongOrNull() ?: 7L

/** Returns subscription status map for a userId. Marks expired subscriptions in DB. */
fun getUserSubscriptionStatus(userId: Int): Map<String, Any?> {
    val now = System.currentTimeMillis()
    return transaction {
        val sub = SubscriptionsTable
            .select { (SubscriptionsTable.userId eq userId) and (SubscriptionsTable.status neq "cancelled") }
            .orderBy(SubscriptionsTable.id to SortOrder.DESC)
            .firstOrNull()
            ?: return@transaction mapOf("status" to "none", "daysRemaining" to 0L, "plan" to null, "endDate" to null)

        val status    = sub[SubscriptionsTable.status]
        val endDate   = sub[SubscriptionsTable.endDate]
        val msLeft    = (endDate - now).coerceAtLeast(0L)
        val daysLeft  = msLeft / (1000L * 60 * 60 * 24)

        val effectiveStatus = if (endDate < now && status in listOf("trial", "active")) "expired" else status
        if (effectiveStatus == "expired" && status != "expired") {
            SubscriptionsTable.update({ SubscriptionsTable.id eq sub[SubscriptionsTable.id] }) {
                it[SubscriptionsTable.status] = "expired"
            }
        }

        val plan = sub[SubscriptionsTable.planId]?.let { pid ->
            PlansTable.select { PlansTable.id eq pid }.firstOrNull()?.let { p ->
                mapOf(
                    "id"          to p[PlansTable.id],
                    "name"        to p[PlansTable.name],
                    "priceCents"  to p[PlansTable.priceCents],
                    "durationDays" to p[PlansTable.durationDays],
                    "maxDevices"  to p[PlansTable.maxDevices]
                )
            }
        }

        mapOf(
            "status"         to effectiveStatus,
            "daysRemaining"  to daysLeft,
            "plan"           to plan,
            "endDate"        to endDate,
            "subscriptionId" to sub[SubscriptionsTable.id]
        )
    }
}

/** Creates trial subscription for a newly registered user. */
fun createTrialSubscription(userId: Int) {
    val trialMs = getTrialDays() * 24L * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    transaction {
        SubscriptionsTable.insert {
            it[SubscriptionsTable.userId]    = userId
            it[SubscriptionsTable.planId]    = null
            it[SubscriptionsTable.status]    = "trial"
            it[SubscriptionsTable.startDate] = now
            it[SubscriptionsTable.endDate]   = now + trialMs
            it[SubscriptionsTable.createdAt] = now
        }
    }
}

/** Activates a subscription for userId on planId from now. */
fun activateSubscription(userId: Int, planId: Int): Int {
    val now = System.currentTimeMillis()
    val plan = transaction { PlansTable.select { PlansTable.id eq planId }.firstOrNull() }
        ?: error("Plan $planId not found")
    val durationMs = plan[PlansTable.durationDays].toLong() * 24 * 60 * 60 * 1000

    // Cancel any active/trial subs
    transaction {
        SubscriptionsTable.update({
            (SubscriptionsTable.userId eq userId) and
            (SubscriptionsTable.status.inList(listOf("trial", "active")))
        }) { it[SubscriptionsTable.status] = "cancelled" }
    }

    return transaction {
        SubscriptionsTable.insert {
            it[SubscriptionsTable.userId]    = userId
            it[SubscriptionsTable.planId]    = planId
            it[SubscriptionsTable.status]    = "active"
            it[SubscriptionsTable.startDate] = now
            it[SubscriptionsTable.endDate]   = now + durationMs
            it[SubscriptionsTable.createdAt] = now
        } get SubscriptionsTable.id
    }
}

/** Seeds superadmin account, default plans and settings if they don't exist. */
fun initMonetizationData() {
    val now = System.currentTimeMillis()

    // SuperAdmin default account
    if (transaction { SuperAdminTable.selectAll().count() } == 0L) {
        transaction {
            SuperAdminTable.insert {
                it[email]    = "hlsistemas2025@gmail.com"
                it[passHash] = hashPassword("Admin2026@@@@")
            }
        }
        println("SUPERADMIN: Default account created")
    }

    // Default settings
    fun seedSetting(k: String, v: String) = transaction {
        if (AppSettingsTable.select { AppSettingsTable.key eq k }.count() == 0L) {
            AppSettingsTable.insert { it[key] = k; it[value] = v }
        }
    }
    seedSetting("trial_days", "7")

    // Default plans (match landing page content)
    if (transaction { PlansTable.selectAll().count() } == 0L) {
        data class Plan(val name: String, val desc: String, val cents: Int, val days: Int, val devices: Int, val order: Int)
        val defaults = listOf(
            Plan("Mensal",    "Monitoramento completo por 30 dias",     2990, 30,  1, 1),
            Plan("Trimestral","Tudo do Mensal por 3 meses",             7990, 90,  2, 2),
            Plan("Semestral", "Tudo do Trimestral por 6 meses",        13990, 180, 3, 3),
            Plan("Anual",     "Melhor custo-benefício — 12 meses",     23990, 365, 5, 4)
        )
        transaction {
            defaults.forEach { p ->
                PlansTable.insert {
                    it[name]        = p.name
                    it[description] = p.desc
                    it[priceCents]  = p.cents
                    it[durationDays]= p.days
                    it[maxDevices]  = p.devices
                    it[isActive]    = true
                    it[sortOrder]   = p.order
                    it[createdAt]   = now
                }
            }
        }
        println("MONETIZATION: Default plans seeded")
    }
}

/** Validates a superadmin session token. Returns true if valid. */
fun isSuperAdminSession(token: String): Boolean =
    transaction {
        SuperAdminSessionsTable
            .select { SuperAdminSessionsTable.token eq token }
            .firstOrNull()
            ?.let { it[SuperAdminSessionsTable.expiresAt] > System.currentTimeMillis() }
            ?: false
    }

/** Extracts and validates superadmin token from Authorization header. */
fun getSuperAdminToken(call: io.ktor.server.application.ApplicationCall): String? {
    val header = call.request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ")) return null
    val token = header.removePrefix("Bearer ").trim()
    return if (isSuperAdminSession(token)) token else null
}

/** Cents to BRL decimal string: 2990 → "29.90" */
fun centsToBrl(cents: Int): String = "%.2f".format(cents / 100.0)
