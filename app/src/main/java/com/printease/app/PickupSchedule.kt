package com.printease.app

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PickupSchedule(
    val days: List<Int>,
    val open: Int,
    val close: Int,
    val cutoff: Int? = null,
    val closed: String = "",
) {
    init {
        require(days.isNotEmpty() && days.distinct().size == days.size && days.all { it in 1..7 })
        require(open in 0..1439 && close in 0..1439 && close > open)
        require(cutoff == null || cutoff in open..close)
        if (closed.isNotEmpty()) {
            val dates = closed.split(",")
            require(
                dates.size <= 14 &&
                    dates.all {
                        runCatching { LocalDate.parse(it).toString() == it }.getOrDefault(false)
                    }
            )
        }
    }

    fun toMap(): Map<String, Any?> =
        mapOf(
            "days" to days,
            "open" to open,
            "close" to close,
            "cutoff" to cutoff,
            "closed" to closed,
        )

    companion object {
        fun fromMap(map: Map<*, *>): PickupSchedule =
            PickupSchedule(
                (map["days"] as List<*>).map { (it as Number).toInt() },
                (map["open"] as Number).toInt(),
                (map["close"] as Number).toInt(),
                (map["cutoff"] as? Number)?.toInt(),
                map["closed"] as String,
            )
    }
}

data class PickupWindow(val range: String, val cutoff: String?)

fun clockMinutes(text: String): Int? {
    if (!Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(text)) return null
    return text.substringBefore(':').toInt() * 60 + text.substringAfter(':').toInt()
}

fun inputTime(minutes: Int?): String =
    minutes?.let { "%02d:%02d".format(Locale.ROOT, it / 60, it % 60) }.orEmpty()

fun nextPickupWindow(
    schedule: PickupSchedule,
    now: ZonedDateTime = ZonedDateTime.now(),
): PickupWindow? {
    val here = now.withZoneSameInstant(ZoneId.of("Asia/Manila"))
    val minute = here.hour * 60 + here.minute
    val closed = schedule.closed.split(',').toSet()
    fun time(value: Int): String =
        LocalTime.of(value / 60, value % 60)
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    for (offset in 0L..35L) {
        val date = here.toLocalDate().plusDays(offset)
        if (date.dayOfWeek.value !in schedule.days || date.toString() in closed) continue
        if (offset == 0L && minute >= (schedule.cutoff ?: schedule.close)) continue
        val label =
            if (offset == 0L) "Today"
            else date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
        return PickupWindow(
            "$label, ${time(schedule.open)}–${time(schedule.close)}",
            schedule.cutoff?.let(::time),
        )
    }
    return null
}
