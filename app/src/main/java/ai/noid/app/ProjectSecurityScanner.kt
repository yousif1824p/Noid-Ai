package ai.noid.app

import java.nio.charset.StandardCharsets

data class ProjectSourceFile(val relativePath: String, val content: String)

object ProjectSecurityScanner {
    private val secretAssignment = Regex("""(?i)\b(api[_-]?key|client[_-]?secret|password|passwd|access[_-]?token)\b\s*[:=]\s*["']([^"']{8,})["']""")
    private val bearerCredential = Regex("""(?i)\bAuthorization\b(?:["']?\s*\]?)?\s*(?:[:,=]|,\s*)\s*["']?\s*Bearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val privateKeyHeader = Regex("""-----BEGIN (?:RSA |DSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----""")
    private val githubToken = Regex("""\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b""")
    private val slackToken = Regex("""\bxox[baprs]-[A-Za-z0-9-]{10,}\b""")
    private val googleApiKey = Regex("""\bAIza[0-9A-Za-z_-]{35}\b""")
    private val gitlabToken = Regex("""\bglpat-[A-Za-z0-9_-]{20,}\b""")
    private val npmToken = Regex("""\bnpm_[A-Za-z0-9]{36}\b""")
    private val stripeSecret = Regex("""\bsk_(?:live|test)_[A-Za-z0-9]{20,}\b""")
    private val providerCredentials = listOf(
        Triple(githubToken, "source.github_token", "رمز وصول GitHub محتمل"),
        Triple(slackToken, "source.slack_token", "رمز وصول Slack محتمل"),
        Triple(googleApiKey, "source.google_api_key", "مفتاح Google API محتمل"),
        Triple(gitlabToken, "source.gitlab_token", "رمز وصول GitLab محتمل"),
        Triple(npmToken, "source.npm_token", "رمز وصول npm محتمل"),
        Triple(stripeSecret, "source.stripe_secret", "مفتاح Stripe سري محتمل")
    )
    private val debugEnabled = Regex("""(?i)android:debuggable\s*=\s*["']true["']|\bdebuggable\s*=\s*true""")
    private val cleartextEnabled = Regex("""(?i)usesCleartextTraffic\s*=\s*["']true["']""")
    private val javascriptEnabled = Regex("""setJavaScriptEnabled\s*\(\s*true\s*\)""")
    private val javascriptBridge = Regex("""addJavascriptInterface\s*\(""")
    private val cleartextUrl = Regex("""http://[^\s"'<>]+""")

    fun scan(files: List<ProjectSourceFile>, skippedFiles: Int = 0): List<ApkSecurityFinding> {
        val findings = mutableListOf<ApkSecurityFinding>()
        if (skippedFiles > 0 && findings.size < MAX_FINDINGS) {
            findings += ApkSecurityFinding(
                id = "source.scan_skipped_files",
                severity = ApkFindingSeverity.LOW,
                title = "الفحص لم يشمل بعض الملفات",
                description = "تعذرت مراجعة بعض الملفات أو تم تجاوزها بسبب نوعها أو حدود الفحص أو تعذر قراءتها؛ لذلك لا تمثل النتائج مراجعة كاملة للمشروع.",
                evidence = "عدد العناصر التي تم تجاوزها أو تعذرت معالجتها: $skippedFiles.",
                recommendation = "راجع العناصر المتجاوزة، وافحص الأجزاء التي لم تشملها المراجعة بعد معالجة سبب التجاوز.",
                confidence = ApkFindingConfidence.HIGH
            )
        }
        if (files.size > MAX_INPUT_FILES) {
            val skippedCount = files.size - MAX_INPUT_FILES
            findings += ApkSecurityFinding(
                id = "source.scan_input_limit",
                severity = ApkFindingSeverity.LOW,
                title = "الفحص غير مكتمل بسبب حد الملفات",
                description = "تجاوز المشروع الحد الأقصى للملفات؛ لم يشمل الفحص جميع الملفات.",
                evidence = "فُحصت أول $MAX_INPUT_FILES ملفاً من أصل ${files.size}؛ لم تُفحص $skippedCount ملفات.",
                recommendation = "قسّم المشروع إلى أجزاء أصغر وأعد فحص الملفات المتبقية قبل اعتبار المراجعة مكتملة.",
                confidence = ApkFindingConfidence.HIGH
            )
        }
        files.take(MAX_INPUT_FILES).forEach { file ->
            val path = ProjectFilePolicy.normalizeRelativePath(file.relativePath) ?: return@forEach
            if (!ProjectFilePolicy.isEditableTextFile(path.substringAfterLast('/'))) return@forEach
            if (file.content.toByteArray(StandardCharsets.UTF_8).size > ProjectFilePolicy.MAX_TEXT_FILE_BYTES) return@forEach

            file.content.lineSequence().forEachIndexed { index, line ->
                val lineNumber = index + 1
                fun add(
                    id: String,
                    severity: ApkFindingSeverity,
                    title: String,
                    description: String,
                    evidence: String,
                    recommendation: String,
                    confidence: ApkFindingConfidence = ApkFindingConfidence.MEDIUM
                ) {
                    if (findings.size >= MAX_FINDINGS) return
                    findings += ApkSecurityFinding(
                        id = "$id:$path:$lineNumber",
                        severity = severity,
                        title = title,
                        description = description,
                        evidence = evidence,
                        recommendation = recommendation,
                        confidence = confidence
                    )
                }

                secretAssignment.find(line)?.let { match ->
                    val redacted = match.value.replace(match.groupValues[2], "[REDACTED]")
                    add(
                        "source.hardcoded_secret", ApkFindingSeverity.HIGH,
                        "احتمال وجود سر أو كلمة مرور ثابتة",
                        "عُثر على إسناد يشبه مفتاحاً أو كلمة مرور. قد تكون مطابقة كاذبة وتحتاج مراجعة.",
                        "$path:$lineNumber: $redacted",
                        "أزل السر من المصدر، وألغِ المفتاح إذا كان حقيقياً، واستخدم مخزناً آمناً للأسرار.",
                        ApkFindingConfidence.LOW
                    )
                }
                if (bearerCredential.containsMatchIn(line)) add(
                    "source.bearer_token", ApkFindingSeverity.HIGH,
                    "احتمال وجود رمز وصول Bearer ثابت",
                    "عُثر على ترويسة Authorization تحتوي بيانات اعتماد Bearer؛ قد تكون مطابقة كاذبة.",
                    "$path:$lineNumber: تم حجب بيانات الاعتماد.",
                    "أزل الرمز من المصدر وألغِه إذا كان حقيقياً، واستخدم مخزناً آمناً للأسرار.",
                    ApkFindingConfidence.LOW
                )
                providerCredentials.forEach { (pattern, id, title) ->
                    if (pattern.containsMatchIn(line)) add(
                        id, ApkFindingSeverity.HIGH,
                        title,
                        "عُثر على نمط رمز معروف لمزوّد خدمة؛ تحقق من كونه اعتماداً فعلياً.",
                        "$path:$lineNumber: تم حجب بيانات الاعتماد.",
                        "أزل الرمز من المصدر وألغِه أو دوّره إذا كان حقيقياً، واستخدم مخزناً آمناً للأسرار.",
                        ApkFindingConfidence.LOW
                    )
                }
                if (privateKeyHeader.containsMatchIn(line)) add(
                    "source.private_key_material", ApkFindingSeverity.HIGH,
                    "ترويسة مادة مفتاح خاص داخل المصدر",
                    "عُثر على ترويسة PEM لمفتاح خاص. افترض أن المفتاح قد انكشف إذا كان حقيقياً.",
                    "$path:$lineNumber: تم حجب محتوى المفتاح.",
                    "أزل المفتاح من المستودع، وألغِه أو دوّره، وخزّنه خارج المصدر."
                )
                if (debugEnabled.containsMatchIn(line)) add(
                    "source.debuggable", ApkFindingSeverity.MEDIUM,
                    "التصحيح مفعّل في إعداد أو Manifest",
                    "تم العثور على إعداد يفعّل التصحيح.",
                    "$path:$lineNumber",
                    "عطّل التصحيح في إصدار الإنتاج."
                )
                if (cleartextEnabled.containsMatchIn(line) || cleartextUrl.containsMatchIn(line)) add(
                    "source.cleartext_traffic", ApkFindingSeverity.MEDIUM,
                    "إعداد أو عنوان اتصال HTTP غير مشفر",
                    "قد يسمح الاتصال غير المشفر بقراءة البيانات أو تعديلها أثناء النقل.",
                    "$path:$lineNumber: تم حجب نص السطر؛ راجع الملف المحدد.",
                    "استخدم HTTPS مع تحقق صحيح من الشهادة، وعطّل cleartext traffic للإنتاج."
                )
                if (javascriptEnabled.containsMatchIn(line)) add(
                    "source.webview_javascript", ApkFindingSeverity.LOW,
                    "JavaScript مفعّل في WebView",
                    "تفعيل JavaScript ليس ثغرة وحده، لكنه يزيد أثر المحتوى غير الموثوق.",
                    "$path:$lineNumber",
                    "عطّل JavaScript إن لم تكن هناك حاجة، ولا تعرض محتوى غير موثوق في WebView."
                )
                if (javascriptBridge.containsMatchIn(line)) add(
                    "source.webview_bridge", ApkFindingSeverity.MEDIUM,
                    "واجهة JavaScript أصلية في WebView",
                    "الجسر قد يعرّض وظائف التطبيق لمحتوى الويب إذا لم يكن المصدر موثوقاً ومقيداً.",
                    "$path:$lineNumber",
                    "تجنب الجسر مع محتوى غير موثوق، وقلّص الدوال المكشوفة وتحقق من مصدر كل صفحة."
                )
            }
        }
        return findings.distinctBy { it.id }
    }

    const val MAX_FINDINGS = 5_000
    const val MAX_INPUT_FILES = 500
}
