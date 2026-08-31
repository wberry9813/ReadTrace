package com.dmer.neoreaderrecords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingEventAttributionTest {
    @Test
    fun exactEligiblePathIsMatched() {
        val result = attribute(
            eventPath = "/Books/current.epub",
            books = listOf(ReadingEventAttribution.Book("/Books/current.epub", true))
        )

        assertEquals(1, result.matches.size)
        assertEquals(ReadingEventAttribution.Confidence.EXACT_PATH, result.matches.single().confidence)
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun filteredBookDoesNotReappearAsDefaultReadingBook() {
        val result = attribute(
            eventPath = "/Books/unread.epub",
            books = listOf(ReadingEventAttribution.Book("/Books/unread.epub", false))
        )

        assertTrue(result.matches.isEmpty())
        assertEquals(
            ReadingEventAttribution.UnmatchedReason.FILTERED_BOOK,
            result.unmatched.single().reason
        )
    }

    @Test
    fun missingPathIsNeverGuessedFromRecentBook() {
        val result = attribute(
            eventPath = "",
            books = listOf(ReadingEventAttribution.Book("/Books/recent.epub", true))
        )

        assertTrue(result.matches.isEmpty())
        assertEquals(
            ReadingEventAttribution.UnmatchedReason.MISSING_PATH,
            result.unmatched.single().reason
        )
    }

    @Test
    fun uniqueFileNameCanBridgeEquivalentStorageRoots() {
        val result = attribute(
            eventPath = "/sdcard/Books/current.epub",
            books = listOf(ReadingEventAttribution.Book("/storage/emulated/0/Books/current.epub", true))
        )

        assertEquals(1, result.matches.size)
        assertEquals(
            ReadingEventAttribution.Confidence.UNIQUE_FILE_NAME,
            result.matches.single().confidence
        )
    }

    @Test
    fun ambiguousFileNameIsNotAttributed() {
        val result = attribute(
            eventPath = "/sdcard/Downloads/book.epub",
            books = listOf(
                ReadingEventAttribution.Book("/Books/A/book.epub", true),
                ReadingEventAttribution.Book("/Books/B/book.epub", true)
            )
        )

        assertTrue(result.matches.isEmpty())
        assertEquals(
            ReadingEventAttribution.UnmatchedReason.AMBIGUOUS_FILE_NAME,
            result.unmatched.single().reason
        )
    }

    @Test
    fun fileUriAndRepeatedSeparatorsAreNormalized() {
        val result = attribute(
            eventPath = "file:///storage//Books/current.epub",
            books = listOf(ReadingEventAttribution.Book("/storage/Books/current.epub", true))
        )

        assertEquals(ReadingEventAttribution.Confidence.EXACT_PATH, result.matches.single().confidence)
    }

    @Test
    fun duplicateMetadataPathPrefersEligibleBook() {
        val result = attribute(
            eventPath = "/Books/current.epub",
            books = listOf(
                ReadingEventAttribution.Book("/Books/current.epub", false),
                ReadingEventAttribution.Book("/Books/current.epub", true)
            )
        )

        assertEquals(1, result.matches.size)
        assertTrue(result.unmatched.isEmpty())
    }

    private fun attribute(
        eventPath: String,
        books: List<ReadingEventAttribution.Book>
    ): ReadingEventAttribution.Result {
        return ReadingEventAttribution.attribute(
            listOf(ReadingEventAttribution.Event(eventPath, 1_000L, 60_000L)),
            books
        )
    }
}
