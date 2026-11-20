package ai.noid.app

/** Stable action definitions for user-started project, APK, and vision workflows. */
object NoidUserToolActions {
    val PROJECT_DIRECTORY_LIST = ToolAction("project-directory-list", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "عرض محتويات مجلد مشروع اختاره المستخدم")
    val PROJECT_FILE_READ = ToolAction("project-file-read", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة ملف نصي من مجلد مشروع اختاره المستخدم")
    val PROJECT_FILE_CREATE = ToolAction("project-file-create", ToolCapability.WRITE_FILES, ToolRisk.REVERSIBLE, "إنشاء ملف نصي فارغ داخل مجلد المشروع المختار")
    val PROJECT_FILE_SAVE = ToolAction("project-file-save", ToolCapability.WRITE_FILES, ToolRisk.SENSITIVE, "حفظ تعديل ملف مشروع اختاره المستخدم")
    val PROJECT_FILE_RESTORE = ToolAction("project-file-restore", ToolCapability.WRITE_FILES, ToolRisk.SENSITIVE, "استعادة نسخة احتياطية لملف المشروع")
    val PROJECT_FILE_RENAME = ToolAction("project-file-rename", ToolCapability.WRITE_FILES, ToolRisk.REVERSIBLE, "إعادة تسمية ملف نصي في مجلد اختاره المستخدم")
    val PROJECT_SOURCE_STATIC_SCAN = ToolAction("project-source-static-scan", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "فحص ساكن لملفات مشروع اختاره المستخدم")
    val APK_MANIFEST_INSPECTION = ToolAction("apk-manifest-inspection", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "قراءة Manifest لملف APK اختاره المستخدم")
    val APK_REPORT_EXPORT = ToolAction("apk-report-export", ToolCapability.WRITE_FILES, ToolRisk.REVERSIBLE, "تصدير تقرير فحص APK إلى وجهة اختارها المستخدم")
    val TEXT_MODEL_IMPORT = ToolAction("text-model-import", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "استيراد نموذج المحادثة المحلي الذي اختاره المستخدم")
    val VISION_MODEL_IMPORT = ToolAction("vision-model-import", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "استيراد نموذج رؤية اختاره المستخدم")
    val VISION_IMAGE_ANALYSIS = ToolAction("vision-image-analysis", ToolCapability.READ_FILES, ToolRisk.READ_ONLY, "تحليل محلي لصورة اختارها المستخدم")

    val all: List<ToolAction> = listOf(
        PROJECT_DIRECTORY_LIST, PROJECT_FILE_READ, PROJECT_FILE_CREATE,
        PROJECT_FILE_SAVE, PROJECT_FILE_RESTORE, PROJECT_FILE_RENAME,
        PROJECT_SOURCE_STATIC_SCAN, APK_MANIFEST_INSPECTION, APK_REPORT_EXPORT,
        TEXT_MODEL_IMPORT, VISION_MODEL_IMPORT, VISION_IMAGE_ANALYSIS
    )
}
