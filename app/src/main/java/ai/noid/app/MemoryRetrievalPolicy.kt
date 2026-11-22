package ai.noid.app

import java.text.Normalizer

/** Small deterministic lexical retriever for user-approved memory records. */
object MemoryRetrievalPolicy {
    private val stopWords = setOf(
        "في", "من", "على", "الى", "إلى", "عن", "مع", "هذا", "هذه", "ذلك",
        "التي", "الذي", "هو", "هي", "كان", "كانت", "ما", "لا", "لم", "لن",
        "the", "and", "for", "with", "from", "that", "this", "are", "was"
    )

    fun tokens(value: String): Set<String> =
        normalize(value)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .filter { it.length >= 2 && it !in stopWords }
            .take(64)
            .toSet()

    fun score(query: String, queryTokens: Set<String>, memory: UserMemory): Double {
        if (queryTokens.isEmpty()) return 0.0
        val titleTokens = tokens(memory.title)
        val contentTokens = tokens(memory.content)
        val sourceTokens = tokens(memory.source)
        val matchedTitle = queryTokens.intersect(titleTokens).size
        val matchedContent = queryTokens.intersect(contentTokens).size
        val matchedSource = queryTokens.intersect(sourceTokens).size
        val matched = queryTokens.intersect(titleTokens + contentTokens + sourceTokens).size
        if (matched == 0) return 0.0

        val coverage = matched.toDouble() / queryTokens.size
        val normalizedQuery = normalize(query).trim()
        val phraseBoost = if (
            normalizedQuery.length >= 3 &&
            (normalize(memory.title).contains(normalizedQuery) ||
                normalize(memory.content).contains(normalizedQuery))
        ) 2.0 else 0.0
        return matchedTitle * 3.0 +
            matchedContent * 1.0 +
            matchedSource * 0.2 +
            coverage * 2.0 +
            phraseBoost
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (character in decomposed) {
                if (Character.getType(character) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(character) == Character.COMBINING_SPACING_MARK.toInt() ||
                    Character.getType(character) == Character.ENCLOSING_MARK.toInt() ||
                    character == '\u0640'
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
