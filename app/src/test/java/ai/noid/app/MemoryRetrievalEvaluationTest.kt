package ai.noid.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Small held-out regression corpus for the deterministic Arabic lexical retriever.
 *
 * This measures lexical retrieval only. It is not evidence of semantic understanding.
 */
class MemoryRetrievalEvaluationTest {
    private data class Case(
        val query: String,
        val expectedMemoryId: Long
    )

    @Test
    fun heldOutArabicQueriesMeetRecallAtFiveAndMeanReciprocalRankTargets() = runBlocking {
        val memories = listOf(
            memory(1, "تفضيل العربية الفصحى", "يفضل المستخدم الشرح بالعربية الفصحى وبأسلوب موجز"),
            memory(2, "حدود الذاكرة والسياق", "يجب تحديد سقف عدد الذكريات وطول سياق الاسترجاع"),
            memory(3, "استعادة الخطط بعد الإغلاق", "الخطة التي كانت قيد التنفيذ تحتاج إلى مراجعة بعد إعادة تشغيل التطبيق"),
            memory(4, "موافقة تنفيذ الأدوات", "لا تنفذ الأدوات الحساسة قبل موافقة المستخدم الصريحة"),
            memory(5, "تنقية سجل التدقيق", "إخفاء الرموز السرية وبيانات الاعتماد من تفاصيل السجل"),
            memory(6, "البحث في المحادثات", "البحث النصي في الرسائل السابقة مع تحديد عدد النتائج"),
            memory(7, "تحليل الصور محلياً", "معالجة الصور ضمن حدود الذاكرة وعدم رفعها تلقائياً"),
            memory(8, "استقلال المشروع", "NOID AI مشروع منفصل عن تطبيق Noid السابق"),
            memory(9, "إعدادات الواجهة", "ألوان الواجهة وحجم الخط وطريقة العرض"),
            memory(10, "اتصال المحرك البعيد", "عنوان الخادم ومهلة الاتصال وإعادة المحاولة"),
            memory(11, "نسخ قاعدة البيانات", "إنشاء نسخة احتياطية واستعادتها عند الحاجة"),
            memory(12, "اختبارات Android", "تشغيل اختبارات الوحدة والتحقق من ترحيل Room"),
            memory(13, "إدارة الملفات", "قراءة الملفات التي يختارها المستخدم عبر SAF"),
            memory(14, "سياسة الشبكة", "الاتصال المحلي والاتصال البعيد وحماية النقل")
        )

        val cases = listOf(
            Case("العربية الفصحى", 1),
            Case("سقف الذاكرة وطول السياق", 2),
            Case("مراجعة الخطة بعد إعادة التشغيل", 3),
            Case("موافقة المستخدم قبل تنفيذ الأدوات", 4),
            Case("إخفاء بيانات الاعتماد من السجل", 5),
            Case("البحث النصي في الرسائل السابقة", 6),
            Case("معالجة الصور وحدود الذاكرة", 7),
            Case("مشروع منفصل عن التطبيق السابق", 8),
            Case("ألوان الواجهة وحجم الخط", 9),
            Case("مهلة الاتصال بعنوان الخادم", 10),
            Case("إنشاء نسخة احتياطية ثم استعادتها", 11),
            Case("اختبار ترحيل Room", 12),
            Case("قراءة الملفات التي حددها المستخدم", 13),
            Case("حماية الاتصال المحلي والبعيد", 14)
        )

        val dao = EvaluationMemoryDao(memories)
        val repository = MemoryRepository(dao)
        var reciprocalRankTotal = 0.0
        var hitsAtFive = 0

        for (case in cases) {
            val ranked = repository.relevantContext(case.query, maxItems = 5)
            val rank = ranked.indexOfFirst { it.id == case.expectedMemoryId }
            if (rank >= 0) {
                hitsAtFive++
                reciprocalRankTotal += 1.0 / (rank + 1)
            }
        }

        val recallAtFive = hitsAtFive.toDouble() / cases.size
        val meanReciprocalRank = reciprocalRankTotal / cases.size

        assertTrue("Recall@5 was $recallAtFive", recallAtFive >= 0.875)
        assertTrue("MRR was $meanReciprocalRank", meanReciprocalRank >= 0.70)
    }

    @Test
    fun normalizedArabicStopWordsDoNotBecomeSearchTerms() {
        assertTrue(
            "Arabic stop words must be removed after normalization",
            MemoryRetrievalPolicy.tokens("إلى في من").isEmpty()
        )
    }

    @Test
    fun repeatedTermsContributeToBm25TermFrequency() {
        val memories = listOf(
            memory(1, "عنوان ثابت", "مفتاح قيمة قيمة قيمة"),
            memory(2, "عنوان ثابت", "مفتاح مفتاح مفتاح قيمة"),
            memory(3, "عنوان مختلف", "محتوى آخر جدا")
        )

        val scores = MemoryRetrievalPolicy.rank("مفتاح", memories).associate {
            it.first.id to it.second
        }

        assertTrue(
            "A higher term frequency should produce a higher BM25 score",
            scores.getValue(2L) > scores.getValue(1L)
        )
    }

    @Test
    fun titleMatchesOutrankIncidentalSourceMatches() = runBlocking {
        val memories = listOf(
            memory(1, "استعادة الخطط بعد الإغلاق", "مراجعة حالة التنفيذ بعد إعادة تشغيل التطبيق"),
            memory(2, "ملاحظات عامة", "تفاصيل متنوعة",).copy(source = "استعادة الخطط بعد الإغلاق"),
            memory(3, "إعدادات الواجهة", "ألوان وأحجام النصوص")
        )
        val repository = MemoryRepository(EvaluationMemoryDao(memories))

        val result = repository.relevantContext("استعادة الخطط بعد الإغلاق", maxItems = 3)

        assertEquals(1L, result.first().id)
    }

    private fun memory(id: Long, title: String, content: String) = UserMemory(
        id = id,
        title = title,
        content = content,
        source = "موافقة المستخدم",
        createdAt = id,
        updatedAt = id
    )

    private class EvaluationMemoryDao(
        private val records: List<UserMemory>
    ) : UserMemoryDao {
        override fun observeAll(): Flow<List<UserMemory>> = flowOf(records)
        override suspend fun getAll(): List<UserMemory> = records
        override suspend fun getRecent(limit: Int): List<UserMemory> =
            records.sortedByDescending { it.updatedAt }.take(limit)
        override suspend fun search(query: String, limit: Int): List<UserMemory> =
            records.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.content.contains(query, ignoreCase = true)
            }.take(limit)
        override suspend fun insert(memory: UserMemory): Long =
            throw UnsupportedOperationException("Evaluation fixture is read-only")
        override suspend fun update(
            memoryId: Long,
            title: String,
            content: String,
            source: String,
            updatedAt: Long
        ) = throw UnsupportedOperationException("Evaluation fixture is read-only")
        override suspend fun deleteById(memoryId: Long) =
            throw UnsupportedOperationException("Evaluation fixture is read-only")
        override suspend fun deleteAll() =
            throw UnsupportedOperationException("Evaluation fixture is read-only")
    }
}
