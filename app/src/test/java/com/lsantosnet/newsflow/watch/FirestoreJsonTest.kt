package com.lsantosnet.newsflow.watch

import com.lsantosnet.newsflow.watch.data.Article
import com.lsantosnet.newsflow.watch.data.FirestoreApi
import com.lsantosnet.newsflow.watch.data.FirestoreJson
import com.lsantosnet.newsflow.watch.data.Http
import com.lsantosnet.newsflow.watch.data.Labels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreJsonTest {

    private val docs = "projects/demo-proj/databases/(default)/documents"

    private val articleRows = """
        [
          {"readTime": "2026-11-12T18:00:00Z"},
          {"document": {
            "name": "$docs/articles/abc123",
            "fields": {
              "title": {"stringValue": "Inflação desacelera"},
              "source_name": {"stringValue": "g1 Economia"},
              "technical_summary": {"stringValue": "O IPCA subiu 0,14%."},
              "tts_text": {"stringValue": "A inflação subiu zero vírgula quatorze por cento."},
              "relevance_score": {"integerValue": "95"},
              "curated_at": {"timestampValue": "2026-11-12T12:26:53.046450Z"},
              "favorite": {"booleanValue": true},
              "read": {"booleanValue": false},
              "also_covered_by": {"arrayValue": {"values": [
                {"mapValue": {"fields": {"url": {"stringValue": "https://a"}}}},
                {"mapValue": {"fields": {"url": {"stringValue": "https://b"}}}}
              ]}}
            }
          }},
          {"document": {
            "name": "$docs/articles/semcampos",
            "fields": {"title": {"stringValue": "Só título"}, "relevance_score": {"doubleValue": 71.0}}
          }}
        ]
    """.trimIndent()

    @Test
    fun parsesArticlesAndSkipsRowsWithoutDocument() {
        val articles = FirestoreJson.parseArticles(articleRows)
        assertEquals(2, articles.size)

        val first = articles[0]
        assertEquals("abc123", first.id)
        assertEquals("$docs/articles/abc123", first.docName)
        assertEquals("Inflação desacelera", first.title)
        assertEquals("g1 Economia", first.sourceName)
        assertEquals(95, first.score)
        assertTrue(first.favorite)
        assertEquals(2, first.extraSources)
        assertEquals(1794486413046L, first.curatedAtMillis)
    }

    @Test
    fun missingFieldsFallBackToSafeDefaults() {
        val bare = FirestoreJson.parseArticles(articleRows)[1]
        assertEquals(71, bare.score)
        assertEquals("", bare.summary)
        assertFalse(bare.favorite)
        assertEquals(0, bare.extraSources)
        assertNull(bare.curatedAtMillis)
    }

    @Test
    fun cursorCarriesTheSortKeyOfTheArticle() {
        val cursor = JSONArray(FirestoreJson.parseArticles(articleRows)[0].cursor)
        assertEquals("95", cursor.getJSONObject(0).getString("integerValue"))
        assertEquals("2026-11-12T12:26:53.046450Z", cursor.getJSONObject(1).getString("timestampValue"))
        assertEquals("$docs/articles/abc123", cursor.getJSONObject(2).getString("referenceValue"))
    }

    @Test
    fun unreadQueryFiltersByGroupAndUnreadInFeedOrder() {
        val query = JSONObject(FirestoreJson.unreadQuery("tech", 8, null)).getJSONObject("structuredQuery")
        val filters = query.getJSONObject("where").getJSONObject("compositeFilter").getJSONArray("filters")
        assertEquals("tech", filters.getJSONObject(0).getJSONObject("fieldFilter").getJSONObject("value").getString("stringValue"))
        assertFalse(filters.getJSONObject(1).getJSONObject("fieldFilter").getJSONObject("value").getBoolean("booleanValue"))
        val order = query.getJSONArray("orderBy")
        assertEquals("relevance_score", order.getJSONObject(0).getJSONObject("field").getString("fieldPath"))
        assertEquals("curated_at", order.getJSONObject(1).getJSONObject("field").getString("fieldPath"))
        assertEquals(8, query.getInt("limit"))
        assertFalse(query.has("startAt"))
    }

    @Test
    fun nextPageStartsAfterTheLastArticle() {
        val last = FirestoreJson.parseArticles(articleRows)[0]
        val startAt = JSONObject(FirestoreJson.unreadQuery("tech", 8, last.cursor))
            .getJSONObject("structuredQuery").getJSONObject("startAt")
        assertFalse(startAt.getBoolean("before"))
        assertEquals(3, startAt.getJSONArray("values").length())
    }

    @Test
    fun markReadOnlyTouchesFieldsTheRulesAllow() {
        val write = JSONObject(FirestoreJson.markReadCommit("$docs/articles/abc123"))
            .getJSONArray("writes").getJSONObject(0)
        assertEquals("[\"read\"]", write.getJSONObject("updateMask").getJSONArray("fieldPaths").toString())
        assertEquals("read_at", write.getJSONArray("updateTransforms").getJSONObject(0).getString("fieldPath"))
        assertTrue(write.getJSONObject("currentDocument").getBoolean("exists"))
        assertEquals(1, write.getJSONObject("update").getJSONObject("fields").length())
    }

    @Test
    fun favoriteCommitWritesOnlyFavorite() {
        val write = JSONObject(FirestoreJson.favoriteCommit("$docs/articles/abc123", true))
            .getJSONArray("writes").getJSONObject(0)
        assertEquals("[\"favorite\"]", write.getJSONObject("updateMask").getJSONArray("fieldPaths").toString())
        assertFalse(write.has("updateTransforms"))
    }

    @Test
    fun parsesProfilesWithActiveFirst() {
        val body = """
            {"documents": [
              {"name": "$docs/profiles/tech", "fields": {"name": {"stringValue": "Tecnologia"}, "icon": {"stringValue": "code"}, "active": {"booleanValue": false}}},
              {"name": "$docs/profiles/economia", "fields": {"name": {"stringValue": "Economia"}, "active": {"booleanValue": false}}},
              {"name": "$docs/profiles/mundo", "fields": {"name": {"stringValue": "Política Mundial"}, "active": {"booleanValue": true}}}
            ]}
        """.trimIndent()
        val profiles = FirestoreJson.parseProfiles(body)
        assertEquals(listOf("mundo", "economia", "tech"), profiles.map { it.id })
        assertEquals("code", profiles[2].icon)
        assertEquals(emptyList<Any>(), FirestoreJson.parseProfiles("{}"))
    }

    @Test
    fun parsesCountAndApiError() {
        assertEquals(24, FirestoreJson.parseCount("""[{"result": {"aggregateFields": {"n": {"integerValue": "24"}}}}]"""))
        assertEquals(0, FirestoreJson.parseCount("[]"))
        assertEquals("Missing index", FirestoreJson.parseError("""[{"error": {"code": 400, "message": "Missing index"}}]"""))
        assertNull(FirestoreJson.parseError("not json"))
    }

    @Test
    fun apiCallsTheExpectedEndpoints() {
        val calls = mutableListOf<String>()
        val http = Http { method, url, _ ->
            calls += "$method ${url.substringAfter("/documents")}"
            if (url.endsWith(":runAggregationQuery")) "[]" else if (url.endsWith(":runQuery")) "[]" else "{}"
        }
        val api = FirestoreApi("demo-proj", http)
        api.profiles()
        api.unreadCount("tech")
        api.unreadPage("tech", 8, null)
        val article = FirestoreJson.parseArticles(articleRows)[0]
        api.markRead(article)
        api.setFavorite(article, false)
        assertTrue(calls[0].startsWith("GET /profiles?"))
        assertEquals(listOf("POST :runAggregationQuery", "POST :runQuery", "POST :commit", "POST :commit"), calls.drop(1))
    }

    @Test
    fun projectIdIsValidatedAndNormalized() {
        assertTrue(FirestoreApi.isValidProjectId("newsflow-2c039"))
        assertFalse(FirestoreApi.isValidProjectId(""))
        assertFalse(FirestoreApi.isValidProjectId("abc"))
        assertFalse(FirestoreApi.isValidProjectId("has/slash-here"))
        assertFalse(FirestoreApi.isValidProjectId("Upper-Case-1"))
        assertEquals("newsflow-2c039", FirestoreApi.normalizeProjectId("  NewsFlow-2c039. "))
        assertEquals("newsflow-2c039", FirestoreApi.normalizeProjectId("newsflow -2c039"))
    }

    @Test
    fun labels() {
        assertEquals("tudo lido", Labels.unread(0))
        assertEquals("1 não lido", Labels.unread(1))
        assertEquals("24 não lidos", Labels.unread(24))
        assertEquals("", Labels.unread(null))
        val now = 10_000_000_000L
        assertEquals("agora", Labels.age(now, now - 20_000))
        assertEquals("12 min", Labels.age(now, now - 12 * 60_000))
        assertEquals("3 h", Labels.age(now, now - 170 * 60_000))
        assertEquals("2 d", Labels.age(now, now - 50 * 3_600_000L))
        assertEquals("", Labels.age(now, null))
    }

    @Test
    fun narrationAnnouncesPositionAndFallsBackToSummary() {
        val article = Article(
            id = "x", docName = "d", title = "Título", sourceName = "", summary = "Resumo.", ttsText = "",
            score = 1, curatedAtMillis = null, favorite = false, extraSources = 0, cursor = "[]",
        )
        assertEquals("Artigo 3 de 24. Título.\nResumo.", Labels.narration(3, 24, article))
    }
}
