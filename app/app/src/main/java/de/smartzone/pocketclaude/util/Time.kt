package de.smartzone.pocketclaude.util

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMM", Locale.getDefault())

fun formatRelative(isoUtc: String): String {
    return try {
        val instant = OffsetDateTime.parse(isoUtc).toInstant()
        val now = Instant.now()
        val diff = Duration.between(instant, now)
        when {
            diff.toMinutes() < 1 -> "gerade eben"
            diff.toMinutes() < 60 -> "vor ${diff.toMinutes()} Min"
            diff.toHours() < 24 -> instant.atZone(ZoneId.systemDefault()).format(TIME_FMT)
            diff.toDays() < 1 -> "gestern"
            diff.toDays() < 7 -> "vor ${diff.toDays()} Tagen"
            else -> instant.atZone(ZoneId.systemDefault()).format(DATE_FMT)
        }
    } catch (_: Exception) {
        ""
    }
}

fun formatTime(isoUtc: String): String {
    return try {
        OffsetDateTime.parse(isoUtc)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(TIME_FMT)
    } catch (_: Exception) {
        ""
    }
}
