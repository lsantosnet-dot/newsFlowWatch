package com.lsantosnet.newsflow.watch.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Monta os pedidos e interpreta as respostas da API REST do Firestore.
 *
 * Fica separado da rede para ser testável na JVM. Os nomes de campos são os
 * mesmos que o pipeline de curadoria do NewsFlow grava.
 */
object FirestoreJson {

    // ---------- leitura ----------

    fun parseProfiles(body: String): List<Profile> {
        val docs = JSONObject(body).optJSONArray("documents") ?: return emptyList()
        return (0 until docs.length()).map { i ->
            val doc = docs.getJSONObject(i)
            val fields = doc.optJSONObject("fields") ?: JSONObject()
            val id = doc.getString("name").substringAfterLast('/')
            Profile(
                id = id,
                name = fields.string("name").ifBlank { id },
                icon = fields.string("icon"),
                active = fields.bool("active"),
            )
        }.sortedWith(compareByDescending<Profile> { it.active }.thenBy { it.name.lowercase() })
    }

    /** Resposta de `:runQuery`: uma lista em que só alguns itens trazem `document`. */
    fun parseArticles(body: String): List<Article> {
        val rows = JSONArray(body)
        val out = ArrayList<Article>(rows.length())
        for (i in 0 until rows.length()) {
            val doc = rows.getJSONObject(i).optJSONObject("document") ?: continue
            val fields = doc.optJSONObject("fields") ?: JSONObject()
            val docName = doc.getString("name")
            val scoreValue = fields.optJSONObject("relevance_score") ?: JSONObject().put("integerValue", "0")
            val curatedValue = fields.optJSONObject("curated_at")
            val cursor = JSONArray()
                .put(scoreValue)
                .put(curatedValue ?: JSONObject().put("nullValue", JSONObject.NULL))
                .put(JSONObject().put("referenceValue", docName))
            out += Article(
                id = docName.substringAfterLast('/'),
                docName = docName,
                title = fields.string("title"),
                sourceName = fields.string("source_name"),
                summary = fields.string("technical_summary"),
                ttsText = fields.string("tts_text"),
                score = scoreValue.number().toInt(),
                curatedAtMillis = curatedValue?.optString("timestampValue")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                favorite = fields.bool("favorite"),
                extraSources = fields.optJSONObject("also_covered_by")
                    ?.optJSONObject("arrayValue")?.optJSONArray("values")?.length() ?: 0,
                cursor = cursor.toString(),
            )
        }
        return out
    }

    /** Resposta de `:runAggregationQuery` com um `count` de alias `n`. */
    fun parseCount(body: String): Int {
        val rows = JSONArray(body)
        if (rows.length() == 0) return 0
        return rows.getJSONObject(0).optJSONObject("result")
            ?.optJSONObject("aggregateFields")?.optJSONObject("n")?.number()?.toInt() ?: 0
    }

    /** Mensagem de erro que a API devolve no corpo, se houver. */
    fun parseError(body: String): String? = runCatching {
        val trimmed = body.trim()
        val obj = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
        obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ---------- consultas ----------

    private fun unreadOf(profileId: String) = JSONObject().put(
        "compositeFilter",
        JSONObject().put("op", "AND").put(
            "filters",
            JSONArray()
                .put(fieldEquals("profile_id", JSONObject().put("stringValue", profileId)))
                .put(fieldEquals("read", JSONObject().put("booleanValue", false))),
        ),
    )

    private fun fieldEquals(field: String, value: JSONObject) = JSONObject().put(
        "fieldFilter",
        JSONObject().put("field", fieldRef(field)).put("op", "EQUAL").put("value", value),
    )

    private fun fieldRef(path: String) = JSONObject().put("fieldPath", path)

    private fun descending(path: String) = JSONObject().put("field", fieldRef(path)).put("direction", "DESCENDING")

    /**
     * Não lidos de um grupo, na mesma ordem do feed do celular (relevância,
     * depois data de curadoria). Usa o índice composto que o NewsFlow já
     * publica: (profile_id, read, relevance_score desc, curated_at desc).
     *
     * [afterCursor] é o `cursor` do último artigo já carregado.
     */
    fun unreadQuery(profileId: String, limit: Int, afterCursor: String?): String {
        val query = JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "articles")))
            .put("where", unreadOf(profileId))
            .put(
                "orderBy",
                JSONArray()
                    .put(descending("relevance_score"))
                    .put(descending("curated_at"))
                    .put(descending("__name__")),
            )
            .put("limit", limit)
        if (afterCursor != null) {
            query.put("startAt", JSONObject().put("values", JSONArray(afterCursor)).put("before", false))
        }
        return JSONObject().put("structuredQuery", query).toString()
    }

    fun unreadCountQuery(profileId: String): String {
        val query = JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", "articles")))
            .put("where", unreadOf(profileId))
        val aggregation = JSONObject()
            .put("structuredQuery", query)
            .put("aggregations", JSONArray().put(JSONObject().put("count", JSONObject()).put("alias", "n")))
        return JSONObject().put("structuredAggregationQuery", aggregation).toString()
    }

    // ---------- escritas ----------
    // As regras do Firestore do NewsFlow só aceitam, em `articles`, mudanças
    // nos campos `read`, `read_at` e `favorite`. `currentDocument.exists`
    // impede recriar um artigo que o pipeline já tenha apagado.

    fun markReadCommit(docName: String): String = commit(
        docName = docName,
        fields = JSONObject().put("read", JSONObject().put("booleanValue", true)),
        mask = listOf("read"),
        transforms = JSONArray().put(
            JSONObject().put("fieldPath", "read_at").put("setToServerValue", "REQUEST_TIME"),
        ),
    )

    fun favoriteCommit(docName: String, favorite: Boolean): String = commit(
        docName = docName,
        fields = JSONObject().put("favorite", JSONObject().put("booleanValue", favorite)),
        mask = listOf("favorite"),
        transforms = null,
    )

    private fun commit(docName: String, fields: JSONObject, mask: List<String>, transforms: JSONArray?): String {
        val write = JSONObject()
            .put("update", JSONObject().put("name", docName).put("fields", fields))
            .put("updateMask", JSONObject().put("fieldPaths", JSONArray(mask)))
            .put("currentDocument", JSONObject().put("exists", true))
        if (transforms != null) write.put("updateTransforms", transforms)
        return JSONObject().put("writes", JSONArray().put(write)).toString()
    }

    // ---------- valores ----------

    private fun JSONObject.string(key: String): String =
        optJSONObject(key)?.optString("stringValue").orEmpty()

    private fun JSONObject.bool(key: String): Boolean =
        optJSONObject(key)?.optBoolean("booleanValue", false) ?: false

    /** `integerValue` vem como texto; `doubleValue`, como número. */
    private fun JSONObject.number(): Double = when {
        has("integerValue") -> optString("integerValue").toDoubleOrNull() ?: 0.0
        has("doubleValue") -> optDouble("doubleValue", 0.0)
        else -> 0.0
    }
}
