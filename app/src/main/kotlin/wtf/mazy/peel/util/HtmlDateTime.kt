package wtf.mazy.peel.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

object HtmlDateTime {

    private val WEEK_PATTERN = Regex("""(\d{4,})-W(\d{2})""")

    fun date(value: String?): LocalDate? = value?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    fun month(value: String?): LocalDate? = value?.takeIf { it.isNotEmpty() }?.let {
        runCatching { YearMonth.parse(it).atDay(1) }.getOrNull()
    }

    fun week(value: String?): LocalDate? = value?.let { WEEK_PATTERN.matchEntire(it) }?.let { m ->
        val year = m.groupValues[1].toInt()
        runCatching {
            LocalDate.of(year, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, m.groupValues[2].toLong())
                .with(DayOfWeek.MONDAY)
        }.getOrNull()?.takeIf { it.get(IsoFields.WEEK_BASED_YEAR) == year }
    }

    fun time(value: String?): LocalTime? = value?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalTime.parse(it, DateTimeFormatter.ISO_LOCAL_TIME) }.getOrNull()
    }

    fun dateTime(value: String?): LocalDateTime? = value?.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }.getOrNull()
    }

    fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun formatMonth(date: LocalDate): String = "%04d-%02d".format(date.year, date.monthValue)

    fun formatWeek(date: LocalDate): String = "%04d-W%02d".format(
        date.get(IsoFields.WEEK_BASED_YEAR),
        date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
    )

    fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)

    fun formatDateTime(date: LocalDate, time: LocalTime): String =
        "${formatDate(date)}T${formatTime(time)}"

    fun toUtcMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun toLocalDate(utcMillis: Long): LocalDate =
        Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
}
