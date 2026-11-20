package ai.noid.app

enum class ApkFindingSeverity { INFO, LOW, MEDIUM, HIGH }
enum class ApkFindingConfidence { LOW, MEDIUM, HIGH }
enum class ApkComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

data class ApkExportedComponent(
    val name: String,
    val type: ApkComponentType,
    val exported: Boolean,
    val requiredPermission: String?
)

data class ApkManifestSnapshot(
    val packageName: String,
    val versionName: String?,
    val debuggable: Boolean,
    val allowBackup: Boolean,
    val requestedPermissions: List<String>,
    val components: List<ApkExportedComponent>,
    val minSdkVersion: Int? = null,
    val targetSdkVersion: Int? = null,
    val usesCleartextTraffic: Boolean = false
)

data class ApkSecurityFinding(
    val id: String,
    val severity: ApkFindingSeverity,
    val title: String,
    val description: String,
    val evidence: String,
    val recommendation: String,
    val confidence: ApkFindingConfidence = ApkFindingConfidence.MEDIUM
)

data class ApkInspectionReport(
    val packageName: String,
    val versionName: String?,
    val requestedPermissions: List<String>,
    val components: List<ApkExportedComponent>,
    val findings: List<ApkSecurityFinding>,
    val minSdkVersion: Int? = null,
    val targetSdkVersion: Int? = null
)

/** Deterministic first-pass rules. Findings are review leads, not proof of exploitability. */
object ApkManifestAnalyzer {
    fun analyze(snapshot: ApkManifestSnapshot): ApkInspectionReport {
        val findings = mutableListOf<ApkSecurityFinding>()

        if (snapshot.debuggable) findings += ApkSecurityFinding(
            "manifest.debuggable", ApkFindingSeverity.MEDIUM,
            "إعداد التصحيح مفعّل",
            "الحزمة قابلة للتصحيح؛ وهذا غير مناسب عادةً لإصدار الإنتاج.",
            "ApplicationInfo.FLAG_DEBUGGABLE=true",
            "أنشئ إصدار إنتاج مع تعطيل android:debuggable وتحقق من إعدادات البناء."
        )

        if (snapshot.usesCleartextTraffic) findings += ApkSecurityFinding(
            "manifest.cleartext_traffic", ApkFindingSeverity.MEDIUM,
            "السماح باتصالات HTTP غير المشفرة",
            "تسمح إعدادات التطبيق بحركة مرور نصية غير مشفرة لبعض الاتصالات؛ راجع Network Security Config والاستثناءات.",
            "ApplicationInfo.usesCleartextTraffic=true",
            "عطّل حركة المرور غير المشفرة واستخدم HTTPS مع تحقق صحيح من الشهادات."
        )

        if (snapshot.allowBackup) findings += ApkSecurityFinding(
            "manifest.backup_enabled", ApkFindingSeverity.LOW,
            "النسخ الاحتياطي مسموح",
            "التطبيق يسمح بالنسخ الاحتياطي. هذا لا يثبت وحده تسرب بيانات.",
            "ApplicationInfo.FLAG_ALLOW_BACKUP=true",
            "راجع بيانات التطبيق وقواعد استخراجها واضبط backup rules بما يناسب حساسيتها."
        )

        snapshot.components.filter { it.exported && it.requiredPermission.isNullOrBlank() }.forEach { component ->
            findings += ApkSecurityFinding(
                "manifest.exported.${component.type.name.lowercase()}.${component.name}",
                ApkFindingSeverity.MEDIUM,
                "مكوّن مُصدّر دون إذن مقيّد",
                "يمكن للتطبيقات الأخرى محاولة الوصول إلى هذا المكوّن. قد يكون ذلك مقصوداً؛ يلزم فحص التحقق من المدخلات والعمليات التي ينفذها.",
                "type=${component.type}; name=${component.name}; exported=true; permission=none",
                "إذا لم يكن الوصول الخارجي مطلوباً، عطّل التصدير. وإلا أضف إذناً مناسباً وتحقق من كل مدخل وكل عملية حساسة."
            )
        }

        if (snapshot.requestedPermissions.isNotEmpty()) findings += ApkSecurityFinding(
            "manifest.permission_inventory", ApkFindingSeverity.INFO,
            "جرد الصلاحيات المطلوبة",
            "هذه الصلاحيات معلنة في الحزمة؛ طلب الصلاحية ليس ثغرة بحد ذاته.",
            snapshot.requestedPermissions.distinct().sorted().joinToString(", "),
            "قارن كل صلاحية بميزة فعلية، واطلبها وقت الحاجة فقط، وأزل الصلاحيات غير المستخدمة."
        )

        if (findings.isEmpty()) findings += ApkSecurityFinding(
            "manifest.no_initial_flags", ApkFindingSeverity.INFO,
            "لم تظهر مؤشرات أولية ضمن القواعد الحالية",
            "هذه نتيجة فحص محدود للـ Manifest وليست شهادة أمان أو إثباتاً لغياب الثغرات.",
            "لم تنطبق قواعد الفحص الأولي الحالية.",
            "تابع مراجعة المصدر والتخزين والشبكة والمكونات واختبارات التشغيل المصرح بها."
        )

        return ApkInspectionReport(
            snapshot.packageName, snapshot.versionName,
            snapshot.requestedPermissions.distinct().sorted(),
            snapshot.components, findings,
            snapshot.minSdkVersion, snapshot.targetSdkVersion
        )
    }
}
