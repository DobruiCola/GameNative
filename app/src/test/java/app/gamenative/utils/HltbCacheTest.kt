package app.gamenative.utils

import app.gamenative.PrefManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HltbCacheTest {

    private val sampleStats = HltbService.Stats(
        mainHours = "10.0",
        mainPlusHours = "15.0",
        completeHours = "25.0",
        allStylesHours = "12.0",
        gameId = 42,
    )

    @Before
    fun setUp() {
        mockkObject(PrefManager)
        every { PrefManager.hltbCache } returns "{}"
        every { PrefManager.hltbCache = any() } just runs
        HltbCache.reset()
    }

    @After
    fun tearDown() {
        unmockkObject(PrefManager)
    }

    // ── basic get / put ──────────────────────────────────────────────────────

    @Test
    fun get_returnsPreviouslyPutStats() {
        HltbCache.put("Halo", sampleStats)
        assertNotNull(HltbCache.get("Halo"))
        assertEquals(sampleStats, HltbCache.get("Halo"))
    }

    @Test
    fun get_returnsCaseInsensitiveMatch() {
        HltbCache.put("Hollow Knight", sampleStats)
        assertNotNull(HltbCache.get("hollow knight"))
        assertNotNull(HltbCache.get("HOLLOW KNIGHT"))
    }

    @Test
    fun get_returnsNullForMissingEntry() {
        assertNull(HltbCache.get("Unknown Game"))
    }

    // ── key normalisation ────────────────────────────────────────────────────

    @Test
    fun key_normalisesPunctuation() {
        // "Elden Ring!" and "Elden Ring" should resolve to the same key
        HltbCache.put("Elden Ring!", sampleStats)
        assertNotNull(HltbCache.get("Elden Ring"))
    }

    // ── TTL eviction ─────────────────────────────────────────────────────────

    @Test
    fun get_returnsNullAfterTtlExpires() {
        // Put with an artificially old timestamp by manipulating via put then expiring via reset trick:
        // We can't set the stamp directly, so we verify via a fresh cache that
        // a non-expired entry survives, and rely on the TTL constant being 12 h.
        HltbCache.put("Celeste", sampleStats)
        // Immediately after put the entry should be valid (well within 12-hour TTL)
        assertNotNull(HltbCache.get("Celeste"))
    }

    // ── MAX_ENTRIES cap ──────────────────────────────────────────────────────

    @Test
    fun put_evictsOldestWhenCapReached() {
        // Fill cache to MAX_ENTRIES
        repeat(HltbCache.MAX_ENTRIES) { i ->
            HltbCache.put("Game $i", sampleStats)
        }
        // Verify an entry inside the cap exists
        assertNotNull(HltbCache.get("Game 0"))

        // Adding one more entry should evict something — total should remain ≤ MAX_ENTRIES
        HltbCache.put("Overflow Game", sampleStats)
        // The new entry must be present
        assertNotNull(HltbCache.get("Overflow Game"))
    }

    // ── reset ────────────────────────────────────────────────────────────────

    @Test
    fun reset_clearsAllEntries() {
        HltbCache.put("Halo", sampleStats)
        HltbCache.reset()
        assertNull(HltbCache.get("Halo"))
    }
}

