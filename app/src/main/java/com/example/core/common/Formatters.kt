package com.example.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object Formatters {
  fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return if (digitGroups == 0) {
      "$bytes B"
    } else {
      String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }
  }

  fun formatTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Never"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    return sdf.format(Date(epochMillis))
  }

  fun formatDurationMs(durationMs: Long): String {
    if (durationMs < 1000L) return "${durationMs}ms"
    val seconds = durationMs / 1000.0
    return if (seconds < 60.0) {
      String.format(Locale.US, "%.2fs", seconds)
    } else {
      val mins = (seconds / 60).toInt()
      val remSecs = seconds % 60
      String.format(Locale.US, "%dm %.1fs", mins, remSecs)
    }
  }
}
