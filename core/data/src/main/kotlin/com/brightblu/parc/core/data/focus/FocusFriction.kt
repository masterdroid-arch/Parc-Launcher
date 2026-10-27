package com.brightblu.parc.core.data.focus

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import com.brightblu.parc.model.Metrics

@Serializable enum class PassPeriod { Day, Week }
@Serializable enum class FocusChallenge { None, Hold, Sentence, Arithmetic, Accountability }
@Serializable data class FocusFriction(
    val passMinutes: Int = 5,
    val passLimit: Int = 2,
    val period: PassPeriod = PassPeriod.Day,
    val challenge: FocusChallenge = FocusChallenge.None,
    val accountabilitySecret: String? = null,
    val delaySeconds: List<Int> = listOf(Metrics.focusFirstDelayMs, Metrics.focusSecondDelayMs, Metrics.focusLaterDelayMs).map { (it / 1_000).toInt() },
    val unlimitedPasses: Boolean = false,
) {
    init { require(passMinutes in 1..15); require(passLimit in 1..5); require(delaySeconds.size == 3 && delaySeconds.all { it in 0..300 }) }
}
@Serializable data class FocusUse(val atMillis: Long, val pass: Boolean)
@Serializable data class FocusLedger(val uses: List<FocusUse> = emptyList())
enum class FocusExit { Pass, End }

/** A build can supply a payment implementation; no payment provider ships with Parc. */
interface UnlockMethod { suspend fun authorize(modeName: String): Boolean }
open class PaidUnlock : UnlockMethod { override suspend fun authorize(modeName: String): Boolean = false }

object FocusFrictionRules {
    fun periodStart(now: Long, period: PassPeriod, zone: ZoneId): Long {
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (period == PassPeriod.Week) date = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }
    fun uses(mode: FocusMode, now: Long, zone: ZoneId): List<FocusUse> = mode.ledger.uses.filter {
        it.atMillis >= periodStart(now, mode.friction.period, zone)
    }
    fun passCount(mode: FocusMode, now: Long, zone: ZoneId): Int = uses(mode, now, zone).count { it.pass }
    fun delayMillis(mode: FocusMode, now: Long, zone: ZoneId): Long =
        mode.friction.delaySeconds[uses(mode, now, zone).size.coerceAtMost(2)] * 1_000L
    fun cooldownUntil(mode: FocusMode): Long = (mode.ledger.uses.lastOrNull { it.pass }?.atMillis ?: -Metrics.focusPassCooldownMs) + Metrics.focusPassCooldownMs
    fun limitReached(mode: FocusMode, now: Long, zone: ZoneId) = !mode.friction.unlimitedPasses && passCount(mode, now, zone) >= mode.friction.passLimit
    fun status(mode: FocusMode, now: Long, zone: ZoneId): String = if (mode.friction.unlimitedPasses) "Passes available whenever needed" else "Pass ${passCount(mode, now, zone)} of ${mode.friction.passLimit} used"
    fun challenge(mode: FocusMode, now: Long, zone: ZoneId): FocusChallenge =
        if (limitReached(mode, now, zone)) FocusChallenge.Accountability
        else if (mode.strict && mode.friction.challenge == FocusChallenge.None) FocusChallenge.Sentence else mode.friction.challenge
}

/** RFC 6238 HMAC-SHA1, 30-second step. Six digits in the app; eight for the RFC vectors. */
object FocusTotp {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    fun newSecret(): String = encode(ByteArray(20).also { SecureRandom().nextBytes(it) })
    fun encode(bytes: ByteArray): String {
        var bits = 0; var value = 0
        return buildString { bytes.forEach { byte ->
            value = (value shl 8) or (byte.toInt() and 255); bits += 8
            while (bits >= 5) { bits -= 5; append(alphabet[(value ushr bits) and 31]) }
        }; if (bits > 0) append(alphabet[(value shl (5 - bits)) and 31]) }
    }
    private fun decode(secret: String): ByteArray {
        var bits = 0; var value = 0
        val output = java.io.ByteArrayOutputStream()
        secret.forEach { char ->
            val n = alphabet.indexOf(char); require(n >= 0)
            value = (value shl 5) or n; bits += 5
            if (bits >= 8) { bits -= 8; output.write((value ushr bits) and 255) }
        }
        return output.toByteArray()
    }
    fun code(secret: String, now: Long, digits: Int = 6): String {
        require(digits == 6 || digits == 8)
        val counter = now / 30_000
        val bytes = ByteArray(8) { i -> (counter ushr ((7 - i) * 8)).toByte() }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decode(secret), "HmacSHA1"))
        val hash = mac.doFinal(bytes); val offset = hash.last().toInt() and 15
        val number = ((hash[offset].toInt() and 127) shl 24) or ((hash[offset + 1].toInt() and 255) shl 16) or
            ((hash[offset + 2].toInt() and 255) shl 8) or (hash[offset + 3].toInt() and 255)
        return (number % if (digits == 6) 1_000_000 else 100_000_000).toString().padStart(digits, '0')
    }
    fun valid(secret: String?, input: String, now: Long): Boolean = secret != null && input.length == 6 &&
        runCatching { java.security.MessageDigest.isEqual(code(secret, now).toByteArray(), input.toByteArray()) }.getOrDefault(false)
}

data class ArithmeticProblem(val left: Int, val right: Int, val multiply: Boolean) {
    val text: String get() = "$left ${if (multiply) "×" else "+"} $right"
    val answer: Int get() = if (multiply) left * right else left + right
}
object FocusChallenges {
    private val random = SecureRandom()
    // Eighteen independent choices from 64 words: 108 bits of entropy, about 120 characters.
    private val words = ("amber ancient autumn bamboo beacon birch blossom breeze bronze canyon cedar cherry circle cloudy coral cosmic " +
        "crystal dawn desert distant dragon dream drift eagle earth ember feather fern field forest gentle golden " +
        "granite green harbor hazel hidden island ivory jasmine juniper lagoon lantern lemon lilac little lunar maple " +
        "marble meadow misty moon moss ocean olive orange orchid pebble pine quiet river silver solar willow").split(' ')
    fun sentence(): String = (1..18).joinToString(" ") { words[random.nextInt(words.size)] }.replaceFirstChar { it.uppercase() } + "."
    fun arithmetic(): List<ArithmeticProblem> = List(5) { ArithmeticProblem(2 + random.nextInt(18), 2 + random.nextInt(10), random.nextBoolean()) }
    // Used by the real field's InputFilter, even when replacing selected text.
    fun acceptsInsertion(length: Int): Boolean = length in 0..1

}

/** Opaque identity checked against the repository's current request. Never persisted. */
class FocusExitRequest internal constructor(
    val modeName: String, val exit: FocusExit, val readyAt: Long, val challenge: FocusChallenge,
    internal val paid: Boolean = false,
    val sentence: String = FocusChallenges.sentence(),
    val problems: List<ArithmeticProblem> = FocusChallenges.arithmetic(),
) {
    internal var holdStarted: Long? = null
}
