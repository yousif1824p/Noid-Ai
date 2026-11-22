package ai.noid.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

private data class DeviceMetrics(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val appPssBytes: Long,
    val batteryTemperatureC: Float?,
    val thermalStatus: Int?,
    val sampledAtMillis: Long
)

@Composable
fun DeviceMonitorScreen() {
    val context = LocalContext.current
    var metrics by remember { mutableStateOf(readDeviceMetrics(context)) }

    LaunchedEffect(context) {
        while (true) {
            metrics = readDeviceMetrics(context)
            delay(3_000)
        }
    }

    val total = metrics.totalRamBytes.coerceAtLeast(1L)
    val used = (total - metrics.availableRamBytes).coerceIn(0L, total)
    val usedRatio = used.toFloat() / total.toFloat()
    val appPssRatio = (metrics.appPssBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("مراقبة الجهاز", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "تُحدّث القراءات تلقائياً كل 3 ثوانٍ. القيم خاصة بالجهاز والتطبيق.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            MetricCard(title = "استخدام ذاكرة RAM", value = "${formatBytes(used)} / ${formatBytes(total)}",
                detail = "المتاح: ${formatBytes(metrics.availableRamBytes)} · المستخدم: ${(usedRatio * 100).toInt()}%") {
                LinearProgressIndicator(progress = { usedRatio }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            MetricCard(title = "ذاكرة NOID AI", value = formatBytes(metrics.appPssBytes),
                detail = "حجم الذاكرة الفعلي التقريبي للعملية (PSS)") {
                LinearProgressIndicator(progress = { appPssRatio }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            MetricCard(
                title = "حرارة البطارية",
                value = metrics.batteryTemperatureC?.let { String.format(Locale.ROOT, "%.1f °C", it) } ?: "غير متاحة",
                detail = "قراءة حساس البطارية من Android؛ ليست درجة حرارة المعالج."
            )
        }
        item {
            val status = metrics.thermalStatus?.let(::thermalStatusLabel) ?: "غير مدعوم على هذا الإصدار"
            MetricCard(
                title = "الحالة الحرارية للنظام",
                value = status,
                detail = "مؤشر Android للحمل الحراري، وليس قياساً بالدرجات المئوية."
            )
        }
        item {
            Text(
                "تنبيه: قد لا يوفر الهاتف حساس حرارة البطارية أو حالة حرارية مفصلة. لا يستنتج NOID حرارة المعالج عند غياب القراءة.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    detail: String,
    content: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

private fun readDeviceMetrics(context: Context): DeviceMetrics {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val processMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val rawTemperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?: Int.MIN_VALUE
    val batteryTemperature = if (rawTemperature != Int.MIN_VALUE) rawTemperature / 10f else null
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        powerManager.currentThermalStatus
    } else {
        null
    }
    return DeviceMetrics(
        totalRamBytes = memoryInfo.totalMem,
        availableRamBytes = memoryInfo.availMem,
        appPssBytes = processMemory.totalPss.toLong() * 1024L,
        batteryTemperatureC = batteryTemperature,
        thermalStatus = thermalStatus,
        sampledAtMillis = System.currentTimeMillis()
    )
}


private fun thermalStatusLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "طبيعي"
    PowerManager.THERMAL_STATUS_LIGHT -> "ارتفاع طفيف"
    PowerManager.THERMAL_STATUS_MODERATE -> "متوسط"
    PowerManager.THERMAL_STATUS_SEVERE -> "مرتفع"
    PowerManager.THERMAL_STATUS_CRITICAL -> "حرج"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "طارئ"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "إيقاف حراري"
    else -> "غير معروف"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
}
