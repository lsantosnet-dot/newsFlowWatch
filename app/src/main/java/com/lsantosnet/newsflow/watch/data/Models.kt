package com.lsantosnet.newsflow.watch.data

/** Grupo de notícias (um documento da coleção `profiles`). Só leitura no relógio. */
data class Profile(
    val id: String,
    val name: String,
    val icon: String,
    val active: Boolean,
)

/** Artigo curado (um documento da coleção `articles`). */
data class Article(
    val id: String,
    /** Caminho completo do documento, usado nas escritas e no cursor de paginação. */
    val docName: String,
    val title: String,
    val sourceName: String,
    val summary: String,
    val ttsText: String,
    val score: Int,
    val curatedAtMillis: Long?,
    val favorite: Boolean,
    /** Quantas outras fontes publicaram a mesma notícia. */
    val extraSources: Int,
    /** Posição deste artigo na ordenação do feed, para pedir a página seguinte. */
    val cursor: String,
) {
    /** Texto que o relógio lê em voz alta; cai no resumo se a narração vier vazia. */
    val spokenText: String get() = ttsText.ifBlank { summary }
}
