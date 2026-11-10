package com.lonerxone.halo

/**
 * Reading meaning out of ordinary notifications: one-time codes, and rides
 * or deliveries on their way. Halo's own rules, written from scratch (ideas
 * from LiveBridge and HyperBridge's feature lists, no code from them).
 */
object Otp {
    private val I = RegexOption.IGNORE_CASE

    /** Words that clearly name a code. */
    private val KEY = Regex(
        "\\b(otp|one[- ]?time (?:password|passcode|pin|code)|verification (?:code|pin|number)|security code|login code|log[- ]?in code|sign[- ]?in code|confirmation code|auth(?:entication|orization)? code|access code|passcode|2fa code|2-step code|token|activation code|reset code|transaction pin|c[oó]digo|kode|to verify|to confirm|to log ?in|to sign ?in)\\b",
        I,
    )

    /** "code" or "pin" on their own: only right next to the number, in a message that reads like a service. */
    private val WEAK = Regex("\\b(code|pin)\\b", I)
    private val SERVICE = Regex("\\b(your|use|enter|expires?|valid|do not share|don'?t share|never share|verify|verification|log ?in|sign[- ]?in|requested)\\b", I)

    /** Codes that aren't one-time codes. */
    private val NOT_CODE = Regex(
        "\\b(promo|discount|referral|coupon|voucher|invite|gift|postal|zip|post|area|country|dress|source|qr|bar|morse|colou?r|error|status|dial|ussd|shortcode|short code|reference|ref|order|tracking|booking)\\s*(code|number|no\\.?)?\\b",
        I,
    )

    /** A code: 4 to 8 digits (also 123-456, 123 456, G-123456), not part of an amount, time, unit or longer number. */
    private val CANDIDATE = Regex(
        "(?<![\\w*#.,/:+-])(?:G-)?(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\w,/:%-]|[.]\\d|\\s?(?:%|min|mins|minutes|hrs?|hours?|secs?|seconds?|days?|weeks?|km|kg|m\\b|steps|naira|ngn|usd|dollars?|k\\b|gb|mb|followers|likes|views|people|times|am\\b|pm\\b|units|pcs|items))",
        I,
    )

    /** Money, account, phone, address and time words right before a number mean it isn't a code. */
    private val NOT_BEFORE = Regex(
        "(₦|ngn|naira|\\$|usd|€|eur|£|gbp|rs\\.?|amount|bal(ance)?|acct|account|a/c|ending( in)?|card|no\\.?|number|tel|phone|call|whatsapp|ref(erence)?|order|room|flat|house|plot|block|bus|route|year|in|since|by|at)\\s*[:#]?\\s*$",
        I,
    )

    /**
     * The one-time code in this message, or null. Only the message itself
     * is read (never the chat's name). The number has to sit right after a
     * code word (within 40 characters) or right before it (within 30); a
     * plain "code" or "pin" counts only within 15 characters and in a
     * message that reads like a service ("your", "don't share", "expires").
     */
    fun find(@Suppress("UNUSED_PARAMETER") title: String, text: String): String? {
        val msg = text.trim()
        if (msg.isEmpty()) return null
        val hits = KEY.findAll(msg).map { it.range.first to it.range.last + 1 }.toList()
        val weak = if (SERVICE.containsMatchIn(msg)) {
            WEAK.findAll(msg).filter { m ->
                val before = msg.substring(maxOf(0, m.range.first - 25), m.range.first)
                !NOT_CODE.containsMatchIn(before + m.value)
            }.map { it.range.first to it.range.last + 1 }.toList()
        } else emptyList()
        if (hits.isEmpty() && weak.isEmpty()) return null
        val all = hits + weak
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (m in CANDIDATE.findAll(msg)) {
            val raw = m.groupValues[1]
            val digits = raw.filter { it.isDigit() }
            if (digits.length !in 4..8) continue
            val start = m.range.first
            val end = m.range.last + 1
            if (NOT_BEFORE.containsMatchIn(msg.substring(0, start).takeLast(16))) continue
            if (Regex("(19|20)\\d\\d").matches(digits) && !KEY.containsMatchIn(msg)) continue
            var ok = false
            for ((s, e) in weak) {
                if (start >= e && start - e <= 15) ok = true
                if (end <= s && s - end <= 15) ok = true
            }
            for ((s, e) in hits) {
                if (start >= e && start - e <= 40) ok = true
                if (end <= s && s - end <= 30) ok = true
            }
            if (!ok) continue
            // 482 913 style (could be part of a phone number): only right beside the word.
            if ((' ' in raw || '-' in raw) && all.none { (s, e) -> (start >= e && start - e <= 20) || (end <= s && s - end <= 4) }) continue
            val dist = all.minOf { (s, e) -> if (start >= e) start - e else s - end + 10 }
            if (dist < bestDist) {
                bestDist = dist
                best = digits
            }
        }
        return best
    }
}

object LiveStatus {
    /** Apps known to post ride or delivery updates (their plain alerts count too, not only ongoing ones). */
    val KNOWN = setOf(
        "ee.mtakso.client", // Bolt
        "com.bolt.deliveryclient", // Bolt Food
        "com.ubercab", // Uber
        "com.ubercab.eats", // Uber Eats
        "sinet.startup.inDriver", // inDrive
        "me.lyft.android",
        "com.glovo",
        "com.chowdeck.app",
        "com.chowdeck.customer",
        "com.jumia.android",
        "com.jumia.food",
        "com.deliveroo.orderapp",
        "com.dd.doordash",
        "com.grubhub.android",
        "in.swiggy.android",
        "com.application.zomato",
        "com.amazon.mShop.android.shopping",
        "com.global.foodpanda.android",
        "com.talabat",
        "com.careem.acma",
    )

    private val DONE = Regex("\\b(delivered|has been delivered|completed|trip (has )?ended|you.ve arrived|enjoy your (meal|order|food)|dropped off|order complete)\\b", RegexOption.IGNORE_CASE)
    private val ARRIVING = Regex("\\b(arriving (now|soon|shortly)|arrived|almost there|nearby|is here|is outside|outside|waiting for you|at (the )?(pickup|your door|your location)|has arrived|1 min(ute)? away)\\b", RegexOption.IGNORE_CASE)
    private val ON_WAY = Regex("\\b(on (the|its|his|her|their) way|en route|out for delivery|picked up|heading (to|your)|is coming|coming to you|minutes? away|mins? away|trip (has )?started|on (a|your) trip|in transit|dispatched|shipped)\\b", RegexOption.IGNORE_CASE)
    private val PREPARING = Regex("\\b(preparing|being prepared|cooking|packing|being packed|getting (it|your order) ready|driver (assigned|found|accepted)|rider (assigned|found|accepted)|accepted your (order|request)|confirmed by)\\b", RegexOption.IGNORE_CASE)
    private val PLACED = Regex("\\b(order (placed|received|confirmed)|we.ve received your order|looking for (a )?(driver|rider)|finding (a )?(driver|rider|courier)|booking confirmed|request(ed)? (a )?ride)\\b", RegexOption.IGNORE_CASE)
    private val RIDE = Regex("\\b(driver|ride|trip|car|pickup|pick-up|cab|taxi|plate)\\b", RegexOption.IGNORE_CASE)
    private val PROMO = Regex("(\\d+ ?%|\\boff\\b|discount|promo|voucher|coupon|deal|offer|free delivery|save |order now|try |new on |sale\\b)", RegexOption.IGNORE_CASE)
    private val ETA = Regex("\\b(\\d{1,3})\\s*(?:min|mins|minutes)\\b", RegexOption.IGNORE_CASE)

    class Read(val stage: Int, val etaMin: Int, val ride: Boolean)

    /**
     * What this notification says about a ride or delivery, or null when it
     * isn't one. Apps not in [KNOWN] count only for ongoing notifications
     * that clearly say a stage, so ordinary messages never trip it.
     */
    fun read(pkg: String, title: String, text: String, ongoing: Boolean): Read? {
        val known = pkg in KNOWN
        if (!known && !ongoing) return null
        val all = "$title $text"
        // Offers and adverts ("20% off, delivered in 30 mins!") aren't an order.
        if (!ongoing && PROMO.containsMatchIn(all)) return null
        val stage = when {
            DONE.containsMatchIn(all) -> 4
            ARRIVING.containsMatchIn(all) -> 3
            ON_WAY.containsMatchIn(all) -> 2
            PREPARING.containsMatchIn(all) -> 1
            PLACED.containsMatchIn(all) -> 0
            else -> -1
        }
        val eta = ETA.find(all)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..240 } ?: -1
        if (stage < 0 && !(known && eta >= 0)) return null
        return Read(if (stage < 0) 2 else stage, eta, RIDE.containsMatchIn(all) || pkg in setOf("ee.mtakso.client", "com.ubercab", "sinet.startup.inDriver", "me.lyft.android", "com.careem.acma"))
    }

    fun stageNames(ride: Boolean) = if (ride) listOf("Booked", "Driver found", "On the way", "Arrived", "Done") else listOf("Placed", "Preparing", "On the way", "Arriving", "Delivered")
}
