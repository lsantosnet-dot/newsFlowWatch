package com.lsantosnet.newsflow.watch.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Erro devolvido pela API (código HTTP fora de 2xx). */
class ApiException(val code: Int, message: String) : IOException(message)

/** Transporte HTTP, trocável nos testes. */
fun interface Http {
    @Throws(IOException::class)
    fun call(method: String, url: String, body: String?): String
}

object UrlHttp : Http {
    override fun call(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw ApiException(code, FirestoreJson.parseError(text) ?: "HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Acesso ao Firestore do NewsFlow pela API REST, sem SDK e sem chave.
 *
 * As regras do projeto liberam leitura pública e, em `articles`, só a
 * alteração de `read`/`read_at`/`favorite`, então o ID do projeto é a única
 * configuração necessária. Todas as chamadas bloqueiam: rode fora da thread
 * principal.
 */
class FirestoreApi(projectId: String, private val http: Http = UrlHttp) {

    init {
        require(isValidProjectId(projectId)) { "ID de projeto inválido" }
    }

    private val documents =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

    fun profiles(): List<Profile> = FirestoreJson.parseProfiles(
        http.call(
            "GET",
            "$documents/profiles?pageSize=100&mask.fieldPaths=name&mask.fieldPaths=icon&mask.fieldPaths=active",
            null,
        ),
    )

    fun unreadCount(profileId: String): Int = FirestoreJson.parseCount(
        http.call("POST", "$documents:runAggregationQuery", FirestoreJson.unreadCountQuery(profileId)),
    )

    fun unreadPage(profileId: String, limit: Int, afterCursor: String?): List<Article> =
        FirestoreJson.parseArticles(
            http.call("POST", "$documents:runQuery", FirestoreJson.unreadQuery(profileId, limit, afterCursor)),
        )

    fun markRead(article: Article) {
        http.call("POST", "$documents:commit", FirestoreJson.markReadCommit(article.docName))
    }

    fun setFavorite(article: Article, favorite: Boolean) {
        http.call("POST", "$documents:commit", FirestoreJson.favoriteCommit(article.docName, favorite))
    }

    companion object {
        private val PROJECT_ID = Regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$")

        /** Formato dos IDs de projeto do Google Cloud; também evita montar uma URL estranha. */
        fun isValidProjectId(id: String): Boolean = PROJECT_ID.matches(id)

        /** Limpa o que o teclado ou o ditado do relógio costumam acrescentar. */
        fun normalizeProjectId(raw: String): String =
            raw.trim().lowercase().replace(Regex("\\s+"), "").trimEnd('.')
    }
}
