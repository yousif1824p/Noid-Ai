package ai.noid.app

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Reads APK package metadata only; it never installs or executes the APK. */
class ApkManifestInspector(private val packageManager: PackageManager) {
    companion object {
        const val MAX_APK_BYTES: Long = 200L * 1024L * 1024L
        private val archiveFlags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS
    }

    fun inspect(apkFile: File): ApkInspectionReport {
        require(apkFile.isFile) { "ملف APK غير موجود." }
        require(apkFile.extension.equals("apk", ignoreCase = true)) { "الملف المحدد ليس APK." }
        require(apkFile.length() in 1..MAX_APK_BYTES) { "حجم APK غير صالح أو يتجاوز الحد المسموح (200 ميغابايت)." }

        val info = readPackageInfo(apkFile)
            ?: throw IllegalArgumentException("تعذر قراءة بيانات الحزمة. قد يكون الملف تالفاً أو غير صالح.")
        val appInfo = info.applicationInfo
        val components = buildList {
            info.activities.orEmpty().forEach {
                add(ApkExportedComponent(it.name, ApkComponentType.ACTIVITY, it.exported, it.permission))
            }
            info.services.orEmpty().forEach {
                add(ApkExportedComponent(it.name, ApkComponentType.SERVICE, it.exported, it.permission))
            }
            info.receivers.orEmpty().forEach {
                add(ApkExportedComponent(it.name, ApkComponentType.RECEIVER, it.exported, it.permission))
            }
            info.providers.orEmpty().forEach {
                add(ApkExportedComponent(it.name, ApkComponentType.PROVIDER, it.exported, it.readPermission ?: it.writePermission))
            }
        }

        return ApkManifestAnalyzer.analyze(
            ApkManifestSnapshot(
                packageName = info.packageName,
                versionName = info.versionName,
                debuggable = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                allowBackup = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0,
                requestedPermissions = info.requestedPermissions?.toList().orEmpty(),
                components = components,
                minSdkVersion = appInfo?.minSdkVersion,
                targetSdkVersion = appInfo?.targetSdkVersion,
                usesCleartextTraffic = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun readPackageInfo(file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(archiveFlags.toLong())
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, archiveFlags)
        }
}
