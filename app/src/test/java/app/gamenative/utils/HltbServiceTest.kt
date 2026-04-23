package app.gamenative.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class HltbServiceTest {

    // ── secs() ──────────────────────────────────────────────────────────────

    @Test
    fun secs_zeroReturnsPlaceholder() {
        assertEquals("--", HltbService.secs(0L))
    }

    @Test
    fun secs_negativeReturnsPlaceholder() {
        assertEquals("--", HltbService.secs(-3600L))
    }

    @Test
    fun secs_oneHourFormatsCorrectly() {
        assertEquals("1.0", HltbService.secs(3600L))
    }

    @Test
    fun secs_ninetyMinutesFormatsCorrectly() {
        assertEquals("1.5", HltbService.secs(5400L))
    }

    @Test
    fun secs_fractionalHourRoundsToOneDecimal() {
        // 3700s ≈ 1.027… → "1.0"
        assertEquals("1.0", HltbService.secs(3700L))
    }

    @Test
    fun secs_largeValueFormatsCorrectly() {
        // 100 hours
        assertEquals("100.0", HltbService.secs(360_000L))
    }

    // ── normalize() ─────────────────────────────────────────────────────────

    @Test
    fun normalize_lowercasesInput() {
        assertEquals("halo", HltbService.normalize("HALO"))
    }

    @Test
    fun normalize_replacesSpecialCharsWithSpace() {
        assertEquals("the witcher 3", HltbService.normalize("The Witcher 3"))
    }

    @Test
    fun normalize_collapsesMultipleSpaces() {
        assertEquals("a b c", HltbService.normalize("A   B   C"))
    }

    @Test
    fun normalize_stripsPunctuation() {
        assertEquals("hollow knight", HltbService.normalize("Hollow Knight!"))
    }

    @Test
    fun normalize_trimsLeadingAndTrailingSpaces() {
        assertEquals("celeste", HltbService.normalize("  Celeste  "))
    }

    // ── levenshtein() ────────────────────────────────────────────────────────

    @Test
    fun levenshtein_identicalStringsReturnZero() {
        assertEquals(0, HltbService.levenshtein("halo", "halo"))
    }

    @Test
    fun levenshtein_emptyAndNonEmptyReturnsLength() {
        assertEquals(4, HltbService.levenshtein("", "halo"))
        assertEquals(4, HltbService.levenshtein("halo", ""))
    }

    @Test
    fun levenshtein_singleSubstitution() {
        assertEquals(1, HltbService.levenshtein("halo", "hale"))
    }

    @Test
    fun levenshtein_singleInsertion() {
        assertEquals(1, HltbService.levenshtein("halo", "halos"))
    }

    @Test
    fun levenshtein_singleDeletion() {
        assertEquals(1, HltbService.levenshtein("halos", "halo"))
    }

    @Test
    fun levenshtein_completelyDifferentStrings() {
        assertEquals(4, HltbService.levenshtein("halo", "doom"))
    }

    @Test
    fun levenshtein_isSymmetric() {
        val a = "witcher"
        val b = "alchemy"
        assertEquals(HltbService.levenshtein(a, b), HltbService.levenshtein(b, a))
    }

    // ── normalize + levenshtein integration ──────────────────────────────────

    @Test
    fun normalizeAndLevenshtein_exactMatchAfterNormalizeIsZero() {
        val dist = HltbService.levenshtein(
            HltbService.normalize("The Witcher 3: Wild Hunt"),
            HltbService.normalize("The Witcher 3: Wild Hunt"),
        )
        assertEquals(0, dist)
    }

    @Test
    fun normalizeAndLevenshtein_closeMatchHasSmallDistance() {
        // "Hollow Knight" vs "Hollow Knight" with trailing punctuation stripped
        val dist = HltbService.levenshtein(
            HltbService.normalize("Hollow Knight"),
            HltbService.normalize("Hollow Knight!"),
        )
        assertEquals(0, dist)
    }
}
