package app.gamenative.utils

import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches HowLongToBeat completion time stats for a game.
 *
 * Flow (ported from https://github.com/morwy/hltb-for-deck):
 *  1. GET /api/find/init → auth tokens (token, hpKey, hpVal)
 *  2. POST /api/find with auth headers + body → search results contain all comp times
 *
 * Uses HttpURLConnection for the POST — OkHttp over HTTP/2 gets 404 from HLTB's CDN.
 * Stats are cached for 12 hours.
 */
object HltbService {

    private const val BASE = "https://howlongtobeat.com"
    private const val SEARCH_PATH = "/api/find"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36"

    /** Base URL for a game's HLTB page; append the numeric game ID to form the full URL. */
    const val GAME_URL = "https://howlongtobeat.com/game/"

    @Serializable
    data class Stats(
        val mainHours: String,
        val mainPlusHours: String,
        val completeHours: String,
        val allStylesHours: String,
        val gameId: Int = 0,
    )

    private data class Auth(val token: String, val hpKey: String, val hpVal: String)

    private var auth: Auth? = null

    private val httpClient = okhttp3.OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** Fetch auth tokens from the HLTB init endpoint. */
    private suspend fun fetchAuth(): Auth? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE$SEARCH_PATH/init?t=${System.currentTimeMillis()}")
                .header("Origin", BASE).header("Referer", "$BASE/").header("User-Agent", UA).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val d = JSONObject(resp.body?.string() ?: return@withContext null)
                val token = d.optString("token")
                var key = ""; var value = ""
                for (f in d.keys()) {
                    val l = f.lowercase()
                    if (key.isEmpty() && l.contains("key")) key = d.optString(f)
                    else if (value.isEmpty() && l.contains("val")) value = d.optString(f)
                }
                if (token.isNotEmpty() && key.isNotEmpty() && value.isNotEmpty())
                    Auth(token, key, value).also { auth = it }
                else null
            }
        } catch (e: Exception) { Timber.tag("HLTB").e(e, "fetchAuth"); null }
    }

    /** POST the HLTB search API, returning the best-matching game's stats. */
    private suspend fun search(name: String, a: Auth): Stats? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("searchType", "games")
                put("searchTerms", JSONArray(name.split(" ")))
                put("searchPage", 1); put("size", 20)
                put("searchOptions", JSONObject().apply {
                    put("games", JSONObject().apply {
                        put("userId", 0); put("platform", ""); put("sortCategory", "name")
                        put("rangeCategory", "main"); put("modifier", "hide_dlc")
                        put("rangeTime", JSONObject().apply { put("min", 0); put("max", 0) })
                        put("gameplay", JSONObject().apply {
                            put("perspective", ""); put("flow", ""); put("genre", ""); put("difficulty", "")
                        })
                    })
                    put("users", JSONObject()); put("filter", ""); put("sort", 0); put("randomizer", 0)
                })
                put(a.hpKey, a.hpVal)
            }.toString().toByteArray()

            val conn = URL("$BASE$SEARCH_PATH").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", BASE); conn.setRequestProperty("Referer", "$BASE/")
            conn.setRequestProperty("x-auth-token", a.token)
            conn.setRequestProperty("x-hp-key", a.hpKey); conn.setRequestProperty("x-hp-val", a.hpVal)
            conn.setRequestProperty("User-Agent", UA)
            try {
                conn.outputStream.use { it.write(body) }

                if (conn.responseCode != 200) {
                    Timber.tag("HLTB").w("Search HTTP ${conn.responseCode} for '$name'")
                    auth = null; return@withContext null
                }

                val data = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("data")
                    ?: return@withContext null
                if (data.length() == 0) return@withContext null

                // Pick best match (exact name first, then closest by edit distance)
                val norm = normalize(name)
                var bestMatch = data.getJSONObject(0)
                var bestDist = Int.MAX_VALUE
                for (i in 0 until data.length()) {
                    val candidate = data.getJSONObject(i)
                    val dist = levenshtein(norm, normalize(candidate.optString("game_name")))
                    if (dist < bestDist) { bestDist = dist; bestMatch = candidate }
                    if (dist == 0) break
                }

                // Reject poor fuzzy matches — avoids surfacing unrelated stub entries
                val distanceThreshold = maxOf(3, norm.length / 2)
                if (bestDist > distanceThreshold) return@withContext null

                // Skip entries with no completion data — don't poison the cache with placeholders
                if (listOf("comp_main", "comp_plus", "comp_100", "comp_all")
                        .all { bestMatch.optLong(it) == 0L }) return@withContext null

                Timber.tag("HLTB").i("'$name' → '${bestMatch.optString("game_name")}' main=${bestMatch.optLong("comp_main")}s")
                Stats(
                    mainHours = secs(bestMatch.optLong("comp_main")),
                    mainPlusHours = secs(bestMatch.optLong("comp_plus")),
                    completeHours = secs(bestMatch.optLong("comp_100")),
                    allStylesHours = secs(bestMatch.optLong("comp_all")),
                    gameId = bestMatch.optInt("game_id", 0),
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) { Timber.tag("HLTB").e(e, "search '$name'"); null }
    }

    /** Public entry — cache-first, with one auth retry on failure. */
    suspend fun getStats(name: String): Stats? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        HltbCache.get(name)?.let { return@withContext it }
        val a = auth ?: fetchAuth() ?: return@withContext null
        val stats = search(name, a) ?: run {
            val fresh = fetchAuth() ?: return@withContext null
            search(name, fresh)
        } ?: return@withContext null
        HltbCache.put(name, stats)
        stats
    }

    internal fun secs(s: Long) = if (s <= 0) "--" else "%.1f".format(s / 3600.0)
    internal fun normalize(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            dp[i][j] = minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+(if (a[i-1]==b[j-1]) 0 else 1))
        return dp[a.length][b.length]
    }
}

/** In-memory + DataStore cache for HLTB stats (12-hour TTL, max 200 entries). */
object HltbCache {
    private const val TTL = 12 * 3_600_000L
    internal const val MAX_ENTRIES = 200
    private val mem = mutableMapOf<String, HltbService.Stats>()
    private val stamps = mutableMapOf<String, Long>()
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Entry(val stats: HltbService.Stats, val ts: Long)

    @Synchronized
    private fun load() {
        if (loaded) return
        try {
            val raw = PrefManager.hltbCache
            if (raw != "{}") {
                val now = System.currentTimeMillis()
                json.decodeFromString<Map<String, Entry>>(raw).forEach { (k, e) ->
                    if (now - e.ts < TTL) { mem[k] = e.stats; stamps[k] = e.ts }
                }
            }
        } catch (_: Exception) {
        } finally {
            loaded = true
        }
    }

    @Synchronized
    private fun save() {
        try {
            val now = System.currentTimeMillis()
            PrefManager.hltbCache = json.encodeToString(mem.mapValues { Entry(it.value, stamps[it.key] ?: now) })
        } catch (_: Exception) {}
    }

    @Synchronized
    fun get(name: String): HltbService.Stats? {
        load()
        val k = key(name)
        val ts = stamps[k] ?: return null
        if (System.currentTimeMillis() - ts >= TTL) { mem.remove(k); stamps.remove(k); return null }
        return mem[k]
    }

    @Synchronized
    fun put(name: String, stats: HltbService.Stats) {
        load()
        val k = key(name)
        if (mem.size >= MAX_ENTRIES && !mem.containsKey(k)) {
            // Evict the oldest entry to stay within memory budget
            stamps.minByOrNull { it.value }?.key?.let { oldest -> mem.remove(oldest); stamps.remove(oldest) }
        }
        mem[k] = stats
        stamps[k] = System.currentTimeMillis()
        save()
    }

    internal fun key(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Reset state — for testing only. */
    @Synchronized
    internal fun reset() { mem.clear(); stamps.clear(); loaded = false }
}
