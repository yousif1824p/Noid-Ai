package ai.noid.app

import java.text.Normalizer
import kotlin.math.ln

/**
 * Deterministic, bounded BM25-style lexical ranking for user-approved memories.
 * Corpus tokenization, document frequencies, and field averages are computed once
 * per query. This is lexical retrieval, not embedding-based semantic search.
 */
object MemoryRetrievalPolicy {
    private val stopWords = setOf(
        "في", "من", "على", "الى", "إلى", "عن", "مع", "هذا", "هذه", "ذلك",
        "التي", "الذي", "هو", "هي", "كان", "كانت", "ما", "لا", "لم", "لن",
        "the", "and", "for", "with", "from", "that", "this", "are", "was"
    )
    private val normalizedStopWords = stopWords.map(::normalize).toSet()

    private const val K1 = 1.2
    private const val B = 0.75
    private const val TITLE_WEIGHT = 2.5
    private const val CONTENT_WEIGHT = 1.0
    private const val SOURCE_WEIGHT = 0.15

    private data class PreparedMemory(
        val memory: UserMemory,
        val title: List<String>,
        val content: List<String>,
        val source: List<String>
    ) {
        val documentTerms: Set<String> = title.toSet() + content + source
    }

    private data class FieldStats(
        val averageLength: Double,
        val documentFrequency: Map<String, Int>
    )

    fun tokens(value: String): Set<String> = tokenList(value).toSet()

    /**
     * Scores the complete bounded candidate set in one corpus pass.
     * The caller is responsible for bounding candidates before calling.
     */
    fun rank(query: String, candidates: List<UserMemory>): List<Pair<UserMemory, Double>> {
        if (candidates.isEmpty()) return emptyList()
        val queryTerms = tokens(query)
        if (queryTerms.isEmpty()) return emptyList()

        val prepared = candidates.map { memory ->
            PreparedMemory(
                memory = memory,
                title = tokenList(memory.title),
                content = tokenList(memory.content),
                source = tokenList(memory.source)
            )
        }

        val titleStats = stats(prepared) { it.title }
        val contentStats = stats(prepared) { it.content }
        val sourceStats = stats(prepared) { it.source }
        val documentCount = prepared.size.toDouble()
        val normalizedQuery = normalize(query).trim()

        return prepared.mapNotNull { candidate ->
            val matched = queryTerms.intersect(candidate.documentTerms)
            if (matched.isEmpty()) return@mapNotNull null

            var lexicalScore = 0.0
            for (term in queryTerms) {
                lexicalScore += fieldScore(candidate.title, titleStats, term, documentCount, TITLE_WEIGHT)
                lexicalScore += fieldScore(candidate.content, contentStats, term, documentCount, CONTENT_WEIGHT)
                lexicalScore += fieldScore(candidate.source, sourceStats, term, documentCount, SOURCE_WEIGHT)
            }

            val coverage = matched.size.toDouble() / queryTerms.size
            val phraseBoost = if (
                normalizedQuery.length >= 3 &&
                (normalize(candidate.memory.title).contains(normalizedQuery) ||
                    normalize(candidate.memory.content).contains(normalizedQuery))
            ) 1.5 else 0.0
            val score = lexicalScore + coverage * 1.5 + phraseBoost
            if (score > 0.0) candidate.memory to score else null
        }
    }

    private fun stats(
        corpus: List<PreparedMemory>,
        field: (PreparedMemory) -> List<String>
    ): FieldStats {
        val fields = corpus.map(field)
        val frequencies = HashMap<String, Int>()
        for (candidate in corpus) {
            for (term in field(candidate).toSet()) {
                frequencies[term] = (frequencies[term] ?: 0) + 1
            }
        }
        return FieldStats(
            averageLength = fields.sumOf { it.size }.toDouble()
                .div(fields.size.coerceAtLeast(1)).coerceAtLeast(1.0),
            documentFrequency = frequencies
        )
    }

    private fun fieldScore(
        field: List<String>,
        stats: FieldStats,
        term: String,
        documentCount: Double,
        weight: Double
    ): Double {
        val frequency = field.count { it == term }
        if (frequency == 0) return 0.0
        val documentFrequency = stats.documentFrequency[term] ?: 0
        val inverseDocumentFrequency = ln(
            1.0 + (documentCount - documentFrequency + 0.5) /
                (documentFrequency + 0.5)
        )
        val lengthNorm = K1 * (1.0 - B + B * field.size / stats.averageLength)
        val termFrequency = frequency * (K1 + 1.0) / (frequency + lengthNorm)
        return weight * inverseDocumentFrequency * termFrequency
    }

    private fun tokenList(value: String): List<String> =
        normalize(value)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .filter { it.length >= 2 && it !in normalizedStopWords }
            .take(64)
            .toList()

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (character in decomposed) {
                if (Character.getType(character) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(character) == Character.COMBINING_SPACING_MARK.toInt() ||
                    Character.getType(character) == Character.ENCLOSING_MARK.toInt() ||
                    character == 'ـ'
                ) continue
                append(
                    when (character) {
                        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                        'ى' -> 'ي'
                        'ؤ' -> 'و'
                        'ئ' -> 'ي'
                        'ة' -> 'ه'
                        else -> character.lowercaseChar()
                    }
                )
            }
        }
    }
}
