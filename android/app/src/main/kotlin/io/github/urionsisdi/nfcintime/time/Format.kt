package io.github.urionsisdi.nfcintime.time

/** A year is 365.25 days here, the same constant the landing counter uses. */
const val YEAR_SECONDS = 31_557_600L
const val DAY_SECONDS = 86_400L

data class Span(
    val years: Long,
    val days: Long,
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
)

fun span(seconds: Long): Span {
    var rest = seconds.coerceAtLeast(0)
    val years = rest / YEAR_SECONDS
    rest -= years * YEAR_SECONDS
    val days = rest / DAY_SECONDS
    rest -= days * DAY_SECONDS
    val hours = rest / 3600
    rest -= hours * 3600
    val minutes = rest / 60
    return Span(years, days, hours, minutes, rest - minutes * 60)
}

/** Grouped the way the landing groups: `1,234`. */
fun group(value: Long): String {
    val digits = value.toString()
    if (digits.length <= 3) return digits
    val out = StringBuilder(digits.length + digits.length / 3)
    val lead = digits.length % 3
    if (lead > 0) out.append(digits, 0, lead)
    var i = lead
    while (i < digits.length) {
        if (out.isNotEmpty()) out.append(',')
        out.append(digits, i, i + 3)
        i += 3
    }
    return out.toString()
}

/** The two units that matter, short: `73 y 119 d`, `4 h 09 m`. */
fun coarse(seconds: Long, units: Units): String {
    val s = span(seconds)
    return when {
        s.years > 0 -> "${group(s.years)} ${units.year} ${s.days} ${units.day}"
        s.days > 0 -> "${s.days} ${units.day} ${s.hours} ${units.hour}"
        s.hours > 0 -> "${s.hours} ${units.hour} ${pad(s.minutes)} ${units.minute}"
        else -> "${s.minutes} ${units.minute} ${pad(s.seconds)} ${units.second}"
    }
}

/** Single-letter units, in whichever language the interface is set to. */
data class Units(
    val year: String,
    val day: String,
    val hour: String,
    val minute: String,
    val second: String,
)

private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()
