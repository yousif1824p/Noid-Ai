package ai.noid.app

import java.text.Normalizer
import kotlin.math.ln

/**
 * Deterministic, bounded BM25-style lexical ranking for user-approved memories.
 * This is lexical retrieval, not embedding-based semantic search.
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

    fun tokens(value: String): Set<String> =
        normalize(value)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .filter { it.length >= 2 && it !in normalizedStopWords }
            .take(64)
            .toSet()

    fun score(
        query: String,
        queryTokens: Set<String>,
        memory: UserMemory,
        corpus: List<UserMemory>
    ): Double {
        if (queryTokens.isEmpty() || corpus.isEmpty()) return 0.0

        val titleTokens = tokens(memory.title).toList()
        val contentTokens = tokens(memory.content).toList()
        val sourceTokens = tokens(memory.source).toList()
        val allTokens = titleTokens.toSet() + contentTokens + sourceTokens
        val matched = queryTokens.intersect(allTokens)
        if (matched.isEmpty()) return 0.0

        val corpusFields = corpus.map { candidate ->
            Triple(tokens(candidate.title), tokens(candidate.content), tokens(candidate.source))
        }
        val averageTitleLength = corpusFields.map { it.first.size }.average().coerceAtLeast(1.0)
        val averageContentLength = corpusFields.map { it.second.size }.average().coerceAtLeast(1.0)
        val averageSourceLength = corpusFields.map { it.third.size }.average().coerceAtLeast(1.0)

        fun fieldScore(
            field: List<String>,
            averageLength: Double,
            term: String,
            weight: Double
        ): Double {
            val frequency = field.count { it == term }
            if (frequency == 0) return 0.0
            val documentFrequency = corpusFields.count { (title, content, source) ->
                term in title || term in content || term in source
            }
            val documentCount = corpusFields.size.toDouble()
            val inverseDocumentFrequency = ln(
                1.0 + (documentCount - documentFrequency + 0.5) /
                    (documentFrequency + 0.5)
            )
            val lengthNorm = K1 * (1.0 - B + B * field.size / averageLength)
            val termFrequency = frequency * (K1 + 1.0) / (frequency + lengthNorm)
            return weight * inverseDocumentFrequency * termFrequency
        }

        var lexicalScore = 0.0
        for (term in queryTokens) {
            lexicalScore += fieldScore(titleTokens, averageTitleLength, term, TITLE_WEIGHT)
            lexicalScore += fieldScore(contentTokens, averageContentLength, term, CONTENT_WEIGHT)
            lexicalScore += fieldScore(sourceTokens, averageSourceLength, term, SOURCE_WEIGHT)
        }

        val coverage = matched.size.toDouble() / queryTokens.size
        val normalizedQuery = normalize(query).trim()
        val phraseBoost = if (
            normalizedQuery.length >= 3 &&
            (normalize(memory.title).contains(normalizedQuery) ||
                normalize(memory.content).contains(normalizedQuery))
        ) 1.5 else 0.0
        return lexicalScore + coverage * 1.5 + phraseBoost
    }

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
