package kz.tungo.sdk.kotlin

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kz.tungo.sdk.kotlin.errors.TungoValidationException

/**
 * Money helpers. The API expresses amounts in tiyn (1/100 of a tenge) as 64-bit integers.
 */
public object TungoMoney {
    private val decimalPattern = Regex("^-?[0-9]+(\\.[0-9]+)?$")
    private val hundred = BigDecimal(100)

    /**
     * Converts a decimal string such as "1234.56" to tiyn, rounding half away from zero.
     * The accepted grammar is an optional minus, digits and an optional fraction; nothing else.
     *
     * @throws TungoValidationException when the text is malformed or the result does not fit 64 bits
     */
    public fun toTiyn(amount: String): Long {
        if (!decimalPattern.matches(amount)) {
            throw TungoValidationException("Not a decimal amount: expected digits with an optional fraction")
        }
        return toTiyn(BigDecimal(amount))
    }

    /**
     * Converts an exact decimal amount of tenge to tiyn, rounding half away from zero.
     *
     * @throws TungoValidationException when the result does not fit 64 bits
     */
    public fun toTiyn(amount: BigDecimal): Long {
        // Bound the exponent before any rescaling: setScale(0) of BigDecimal("1E-1000000000") would
        // compute 10^1000000000. precision - scale is the number of digits before the point: below
        // 10^-3 an amount rounds to zero tiyn, at 10^19 tenge and above it cannot fit into 64 bits.
        val integerDigits = amount.precision().toLong() - amount.scale()
        if (amount.signum() == 0 || integerDigits < -2) return 0
        if (integerDigits > 19) throw TungoValidationException("The amount does not fit into 64-bit tiyn")
        try {
            return amount.multiply(hundred).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            throw TungoValidationException("The amount does not fit into 64-bit tiyn", e)
        }
    }

    /**
     * Converts a binary floating-point amount of tenge to tiyn through its shortest decimal
     * representation, so that 1.005 gives 101. Prefer the String or BigDecimal overloads.
     *
     * @throws TungoValidationException for NaN, infinities and values that do not fit 64 bits
     */
    public fun toTiyn(amount: Double): Long {
        if (amount.isNaN() || amount.isInfinite()) {
            throw TungoValidationException("The amount must be a finite number")
        }
        return toTiyn(BigDecimal(amount.toString()))
    }

    /** Converts tiyn to an exact decimal amount of tenge with two fraction digits. */
    public fun toTenge(tiyn: Long): BigDecimal = BigDecimal.valueOf(tiyn, 2)

    /**
     * Formats tiyn for display: "45 000,00 ₸". Known currencies get their symbol (KZT, USD, EUR,
     * RUB); any other code is printed in upper case.
     */
    public fun formatTiyn(tiyn: Long, currency: String = "KZT"): String {
        val code = currency.uppercase(Locale.ROOT)
        val symbol = when (code) {
            "", "KZT" -> "₸"
            "USD" -> "$"
            "EUR" -> "€"
            "RUB" -> "₽"
            else -> code
        }
        val text = tiyn.toString()
        val negative = text.startsWith("-")
        val digits = (if (negative) text.substring(1) else text).padStart(3, '0')
        val whole = digits.substring(0, digits.length - 2)
        val fraction = digits.substring(digits.length - 2)
        val grouped = StringBuilder()
        for (i in whole.indices) {
            if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(' ')
            grouped.append(whole[i])
        }
        return (if (negative) "-" else "") + grouped + "," + fraction + " " + symbol
    }

    /**
     * Checks a Kazakhstan BIN or IIN: exactly 12 ASCII digits with a valid check digit.
     * The value is not trimmed.
     */
    public fun isValidBinIin(value: String): Boolean {
        if (value.length != 12 || value.any { it !in '0'..'9' }) return false
        val digits = IntArray(12) { value[it] - '0' }
        var sum = 0
        for (i in 0 until 11) sum += digits[i] * (i + 1)
        var control = sum % 11
        if (control == 10) {
            val weights = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2)
            sum = 0
            for (i in 0 until 11) sum += digits[i] * weights[i]
            control = sum % 11
        }
        return control < 10 && control == digits[11]
    }
}
