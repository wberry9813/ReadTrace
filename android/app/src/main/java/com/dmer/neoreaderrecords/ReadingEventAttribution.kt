package com.dmer.neoreaderrecords

internal object ReadingEventAttribution {
    enum class Confidence {
        EXACT_PATH,
        UNIQUE_FILE_NAME
    }

    enum class UnmatchedReason {
        MISSING_PATH,
        FILTERED_BOOK,
        AMBIGUOUS_FILE_NAME,
        UNKNOWN_PATH
    }

    data class Event(
        val path: String,
        val timestampMs: Long,
        val durationMs: Long
    )

    data class Book(
        val path: String,
        val eligible: Boolean
    )

    data class Match(
        val event: Event,
        val book: Book,
        val confidence: Confidence
    )

    data class Unmatched(
        val event: Event,
        val reason: UnmatchedReason
    )

    data class Result(
        val matches: List<Match>,
        val unmatched: List<Unmatched>
    )

    fun attribute(events: List<Event>, books: List<Book>): Result {
        val normalizedBooks = books
            .filter { it.path.isNotBlank() }
            .groupBy { normalizePath(it.path) }
            .mapValues { (_, samePath) ->
                samePath.firstOrNull { it.eligible } ?: samePath.first()
            }
        val booksByFileName = normalizedBooks.values
            .groupBy { fileName(normalizePath(it.path)) }

        val matches = mutableListOf<Match>()
        val unmatched = mutableListOf<Unmatched>()
        events.forEach { event ->
            val normalizedPath = normalizePath(event.path)
            if (normalizedPath.isBlank()) {
                unmatched += Unmatched(event, UnmatchedReason.MISSING_PATH)
                return@forEach
            }

            val exact = normalizedBooks[normalizedPath]
            if (exact != null) {
                if (exact.eligible) {
                    matches += Match(event, exact, Confidence.EXACT_PATH)
                } else {
                    unmatched += Unmatched(event, UnmatchedReason.FILTERED_BOOK)
                }
                return@forEach
            }

            val fileNameCandidates = booksByFileName[fileName(normalizedPath)].orEmpty()
            when {
                fileNameCandidates.size > 1 -> {
                    unmatched += Unmatched(event, UnmatchedReason.AMBIGUOUS_FILE_NAME)
                }
                fileNameCandidates.size == 1 && fileNameCandidates.first().eligible -> {
                    matches += Match(event, fileNameCandidates.first(), Confidence.UNIQUE_FILE_NAME)
                }
                fileNameCandidates.size == 1 -> {
                    unmatched += Unmatched(event, UnmatchedReason.FILTERED_BOOK)
                }
                else -> {
                    unmatched += Unmatched(event, UnmatchedReason.UNKNOWN_PATH)
                }
            }
        }
        return Result(matches, unmatched)
    }

    internal fun normalizePath(raw: String): String {
        val withoutScheme = raw.trim().removePrefix("file://")
        if (withoutScheme.isBlank()) return ""
        return withoutScheme.replace(Regex("/{2,}"), "/").removeSuffix("/")
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')
}
