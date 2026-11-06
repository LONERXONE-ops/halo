package com.lonerxone.halo

/**
 * Reading meaning out of ordinary notifications: one-time codes, and rides
 * or deliveries on their way. Halo's own rules, written from scratch (ideas
 * from LiveBridge and HyperBridge's feature lists, no code from them).
 */
object Otp {
    /** Words that say "this message carries a code". */
    private val KEYWORDS = Regex(
        "\\b(code|otp|one[- ]?time|verification|verify|passcode|pass code|pin|token|security|login|log in|sign[- ]?in|authenticat\\w*|2fa|confirm\\w*|kode|c[oó]digo)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Words that leave no doubt (a year like 2026 only counts as a code next to one of these). */
    private val STRONG = Regex("\\b(code|otp|one[- ]?time|verification|passcode|token|2fa)\\b", RegexOption.IGNORE_CASE)

    /** A code: 4 to 8 digits, also written as 123-456 or 123 456, or Google's G-123456. */
    private val CANDIDATE = Regex("(?<![\\d*xX#.,])(?:G-)?(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\d.,]\\d)")

    /** Money and account words right before a number mean it isn't a code. */
    private val NOT_BEFORE = Regex("(₦|ngn|naira|\\$|usd|€|eur|£|gbp|rs\\.?|amount|bal(ance)?|acct|account|a/c|ending( in)?|card|no\\.?|number|tel|call|ref(erence)?)\\s*[:#]?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * The code in this message, or null. The number nearest the code word
     * wins (so "Your code is 4821. Don't share. Ref 99231" gives 4821).
     */
    fun find(title: String, text: String): String? {
        val all = "$title\n$text"
        val kw = KEYWORDS.findAll(all).map { it.range.first }.toList()
        if (kw.isEmpty()) return null
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (m in CANDIDATE.findAll(all)) {
            val raw = m.groupValues[1]
            val digits = raw.filter { it.isDigit() }
            if (digits.length !in 4..8) continue
            val before = all.substring(0, m.range.first).takeLast(14)
            if (NOT_BEFORE.containsMatchIn(before)) continue
            // A year on its own (2026) or a clock time (12:30) isn't a code.
            if (digits.length == 4 && Regex("^(19|20)\\d\\d$").matches(digits) && !STRONG.containsMatchIn(all)) continue
            if (m.range.last + 1 < all.length && all[m.range.last + 1] == ':') continue
            if (m.range.first > 0 && all[m.range.first - 1] == ':') continue
            val dist = kw.minOf { k -> kotlin.math.abs(k - m.range.first) }
            if (dist < bestDist) {
                bestDist = dist
                best = digits
            }
        }
        // The code has to sit near the word (not a number three sentences away).
        return best?.takeIf { bestDist <= 80 }
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
