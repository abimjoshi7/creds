package dev.creds.vault.core.domain.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * Time-based one-time passwords (RFC 6238) and the `otpauth://` URIs sites hand out.
 *
 * Pure Kotlin so the same vectors run on host and device. Defaults match what almost
 * every issuer ships: SHA-1, 6 digits, 30-second steps.
 */
object Totp {

    const val DEFAULT_DIGITS: Int = 6
    const val DEFAULT_PERIOD_SECONDS: Int = 30
    const val DEFAULT_ALGORITHM: String = "SHA1"

    data class Spec(
        val secret: ByteArray,
        val digits: Int = DEFAULT_DIGITS,
        val periodSeconds: Int = DEFAULT_PERIOD_SECONDS,
        val algorithm: String = DEFAULT_ALGORITHM,
        val issuer: String? = null,
        val account: String? = null,
    ) {
        init {
            require(secret.isNotEmpty()) { "TOTP secret must not be empty" }
            require(digits in 6..8) { "TOTP digits must be 6–8, got $digits" }
            require(periodSeconds > 0) { "TOTP period must be positive" }
            require(algorithm.uppercase() in SUPPORTED_ALGORITHMS) {
                "Unsupported TOTP algorithm: $algorithm"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Spec) return false
            return secret.contentEquals(other.secret) &&
                digits == other.digits &&
                periodSeconds == other.periodSeconds &&
                algorithm.equals(other.algorithm, ignoreCase = true) &&
                issuer == other.issuer &&
                account == other.account
        }

        override fun hashCode(): Int {
            var result = secret.contentHashCode()
            result = 31 * result + digits
            result = 31 * result + periodSeconds
            result = 31 * result + algorithm.uppercase().hashCode()
            result = 31 * result + (issuer?.hashCode() ?: 0)
            result = 31 * result + (account?.hashCode() ?: 0)
            return result
        }
    }

    data class Code(
        val value: String,
        val periodSeconds: Int,
        val remainingSeconds: Int,
        val counter: Long,
    )

    /**
     * Accepts either a raw Base32 secret or a full `otpauth://totp/...` URI.
     * Returns null when the input is blank or cannot be understood — the editor should
     * keep showing the field rather than erroring while the user is still typing.
     */
    fun parse(raw: String): Spec? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        return if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            parseUri(trimmed)
        } else {
            val secret = Base32.decode(trimmed) ?: return null
            if (secret.isEmpty()) null else Spec(secret = secret)
        }
    }

    fun generate(spec: Spec, epochMillis: Long = System.currentTimeMillis()): Code {
        val counter = epochMillis / 1000L / spec.periodSeconds
        val remaining = spec.periodSeconds - ((epochMillis / 1000L) % spec.periodSeconds).toInt()
        val code = hotp(spec.secret, counter, spec.digits, spec.algorithm)
        return Code(
            value = code,
            periodSeconds = spec.periodSeconds,
            remainingSeconds = remaining.coerceIn(1, spec.periodSeconds),
            counter = counter,
        )
    }

    /** RFC 4226 HOTP, used by [generate]. */
    fun hotp(secret: ByteArray, counter: Long, digits: Int, algorithm: String = DEFAULT_ALGORITHM): String {
        val mac = Mac.getInstance(hmacName(algorithm))
        mac.init(SecretKeySpec(secret, mac.algorithm))
        val hash = mac.doFinal(counter.toByteArray())
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 10.0.pow(digits).toInt()
        return otp.toString().padStart(digits, '0')
    }

    private fun parseUri(uri: String): Spec? {
        // otpauth://totp/Issuer:account?secret=...&issuer=...&digits=6&period=30&algorithm=SHA1
        val withoutScheme = uri.removePrefix("otpauth://").removePrefix("OTPAUTH://")
        if (!withoutScheme.startsWith("totp/", ignoreCase = true)) return null
        val rest = withoutScheme.substringAfter('/')
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")

        val params = query.split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val key = part.substringBefore('=').lowercase()
                val value = part.substringAfter('=', "")
                key to decodeUriComponent(value)
            }

        val secretRaw = params["secret"] ?: return null
        val secret = Base32.decode(secretRaw) ?: return null
        if (secret.isEmpty()) return null

        val digits = params["digits"]?.toIntOrNull() ?: DEFAULT_DIGITS
        val period = params["period"]?.toIntOrNull() ?: DEFAULT_PERIOD_SECONDS
        val algorithm = (params["algorithm"] ?: DEFAULT_ALGORITHM).uppercase()
            .removePrefix("HMAC")

        val label = decodeUriComponent(path)
        val issuerFromLabel = label.substringBefore(':', missingDelimiterValue = "").ifBlank { null }
        val account = label.substringAfter(':', missingDelimiterValue = label).ifBlank { null }
        val issuer = params["issuer"] ?: issuerFromLabel

        return runCatching {
            Spec(
                secret = secret,
                digits = digits,
                periodSeconds = period,
                algorithm = algorithm,
                issuer = issuer,
                account = account,
            )
        }.getOrNull()
    }

    private fun hmacName(algorithm: String): String = when (algorithm.uppercase()) {
        "SHA1" -> "HmacSHA1"
        "SHA256" -> "HmacSHA256"
        "SHA512" -> "HmacSHA512"
        else -> error("Unsupported TOTP algorithm: $algorithm")
    }

    private fun Long.toByteArray(): ByteArray = ByteArray(8) { i ->
        ((this ushr (56 - 8 * i)) and 0xff).toByte()
    }

    private fun decodeUriComponent(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '+' -> {
                    append(' ')
                    i++
                }
                '%' -> if (i + 2 < value.length) {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        append(hex.toChar())
                        i += 3
                    } else {
                        append(c)
                        i++
                    }
                } else {
                    append(c)
                    i++
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }

    private val SUPPORTED_ALGORITHMS = setOf("SHA1", "SHA256", "SHA512")
}

/**
 * RFC 4648 Base32 (no padding required). Returns null when the alphabet is wrong so a
 * mistyped secret does not become a zero-length key.
 */
object Base32 {
    private val DECODE = IntArray(128) { -1 }.also { table ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".forEachIndexed { index, c ->
            table[c.code] = index
            table[c.lowercaseChar().code] = index
        }
    }

    fun decode(input: String): ByteArray? {
        val cleaned = buildString(input.length) {
            for (c in input) {
                when {
                    c == '=' || c.isWhitespace() || c == '-' || c == ' ' -> Unit
                    c.code < 128 && DECODE[c.code] >= 0 -> append(c)
                    else -> return null
                }
            }
        }
        if (cleaned.isEmpty()) return ByteArray(0)

        val out = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        for (c in cleaned) {
            val value = DECODE[c.code]
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out[index++] = ((buffer shr bitsLeft) and 0xff).toByte()
            }
        }
        return if (index == out.size) out else out.copyOf(index)
    }
}
