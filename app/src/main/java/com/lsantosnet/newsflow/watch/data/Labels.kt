package com.lsantosnet.newsflow.watch.data

/** Textos curtos usados nas telas e na narração. */
object Labels {

    fun unread(count: Int?): String = when {
        count == null -> ""
        count <= 0 -> "tudo lido"
        count == 1 -> "1 não lido"
        else -> "$count não lidos"
    }

    /** Idade compacta de um artigo: "agora", "12 min", "3 h", "2 d". */
    fun age(nowMillis: Long, thenMillis: Long?): String {
        if (thenMillis == null) return ""
        val minutes = ((nowMillis - thenMillis) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "agora"
            minutes < 60 -> "$minutes min"
            minutes < 60 * 24 -> "${(minutes + 30) / 60} h"
            else -> "${(minutes + 720) / 1440} d"
        }
    }

    fun extraSources(count: Int): String =
        if (count == 1) "+1 fonte cobriu a mesma notícia" else "+$count fontes cobriram a mesma notícia"

    /**
     * O que o relógio fala para um artigo. Começa pela posição na fila, igual
     * ao modo podcast do celular, para ficar claro de ouvido que mudou de artigo.
     */
    fun narration(position: Int, total: Int, article: Article): String =
        "Artigo $position de $total. ${article.title}.\n${article.spokenText}"
}
