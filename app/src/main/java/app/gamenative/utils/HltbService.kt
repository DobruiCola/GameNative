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
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", BASE); conn.setRequestProperty("Referer", "$BASE/")
            conn.setRequestProperty("x-auth-token", a.token)
            conn.setRequestProperty("x-hp-key", a.hpKey); conn.setRequestProperty("x-hp-val", a.hpVal)
            conn.setRequestProperty("User-Agent", UA)
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
            var best = data.getJSONObject(0)
            var bestDist = Int.MAX_VALUE
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val d = levenshtein(norm, normalize(item.optString("game_name")))
                if (d < bestDist) { bestDist = d; best = item }
                if (d == 0) break
            }

            val g = best
            Timber.tag("HLTB").i("'$name' → '${g.optString("game_name")}' main=${g.optLong("comp_main")}s")
            Stats(
                mainHours = secs(g.optLong("comp_main")),
                mainPlusHours = secs(g.optLong("comp_plus")),
                completeHours = secs(g.optLong("comp_100")),
                allStylesHours = secs(g.optLong("comp_all")),
                gameId = g.optInt("game_id", 0),
            )
        } catch (e: Exception) { Timber.tag("HLTB").e(e, "search '$name'"); null }
    }

    /** Public entry — cache-first, with one auth retry on failure. */
    suspend fun getStats(name: String): Stats? {
        if (name.isBlank()) return null
        HltbCache.get(name)?.let { return it }
        val a = auth ?: fetchAuth() ?: return null
        val stats = search(name, a) ?: run {
            val fresh = fetchAuth() ?: return null
            search(name, fresh)
        } ?: return null
        HltbCache.put(name, stats)
        return stats
    }

    private fun secs(s: Long) = if (s <= 0) "--" else "%.1f".format(s / 3600.0)
    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) { it } }
        for (j in 1..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            dp[i][j] = minOf(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+(if (a[i-1]==b[j-1]) 0 else 1))
        return dp[a.length][b.length]
    }
}

/** In-memory + DataStore cache for HLTB stats (12-hour TTL). */
object HltbCache {
    private const val TTL = 12 * 3_600_000L
    private val mem = mutableMapOf<String, HltbService.Stats>()
    private val stamps = mutableMapOf<String, Long>()
    private var loaded = false
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable data class Entry(val stats: HltbService.Stats, val ts: Long)

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val raw = PrefManager.hltbCache
            if (raw == "{}") return
            val now = System.currentTimeMillis()
            json.decodeFromString<Map<String, Entry>>(raw).forEach { (k, e) ->
                if (now - e.ts < TTL) { mem[k] = e.stats; stamps[k] = e.ts }
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        try {
            val now = System.currentTimeMillis()
            PrefManager.hltbCache = json.encodeToString(mem.mapValues { Entry(it.value, stamps[it.key] ?: now) })
        } catch (_: Exception) {}
    }

    fun get(name: String): HltbService.Stats? {
        load()
        val k = key(name)
        val ts = stamps[k] ?: return null
        if (System.currentTimeMillis() - ts >= TTL) { mem.remove(k); stamps.remove(k); return null }
        return mem[k]
    }

    fun put(name: String, stats: HltbService.Stats) {
        load(); val k = key(name)
        mem[k] = stats; stamps[k] = System.currentTimeMillis(); save()
    }

    private fun key(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()
}
