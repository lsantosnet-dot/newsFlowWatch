package com.lsantosnet.newsflow.watch.data

import android.content.SharedPreferences
import com.lsantosnet.newsflow.watch.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class Group(val profile: Profile, val unread: Int?)

data class GroupsState(
    val loading: Boolean = false,
    val error: String? = null,
    val groups: List<Group> = emptyList(),
)

data class FeedState(
    val profile: Profile? = null,
    val articles: List<Article> = emptyList(),
    val unread: Int? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
)

/**
 * Estado do app, compartilhado entre as telas e o serviço de leitura em voz
 * alta (que marca artigos como lidos mesmo com a tela apagada).
 */
class NewsRepository(
    private val prefs: SharedPreferences,
    private val http: Http = UrlHttp,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var feedJob: Job? = null

    /** ID do projeto Firebase: o digitado no relógio ou, na falta, o embutido no build. */
    val projectId = MutableStateFlow(
        prefs.getString(KEY_PROJECT, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_PROJECT_ID,
    )
    val groups = MutableStateFlow(GroupsState())
    val feed = MutableStateFlow(FeedState())
    val article = MutableStateFlow<Article?>(null)

    /** Avisos curtos para mostrar ao usuário (toast). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val isConfigured: Boolean get() = FirestoreApi.isValidProjectId(projectId.value)

    private fun api() = FirestoreApi(projectId.value, http)

    /** Grava o ID digitado. Devolve `false` se o formato for inválido. */
    fun setProjectId(raw: String): Boolean {
        val id = FirestoreApi.normalizeProjectId(raw)
        if (!FirestoreApi.isValidProjectId(id)) return false
        prefs.edit().putString(KEY_PROJECT, id).apply()
        projectId.value = id
        feedJob?.cancel()
        feed.value = FeedState()
        article.value = null
        groups.value = GroupsState()
        loadGroups()
        return true
    }

    /** Volta ao ID embutido no build (ou a nenhum, se o build veio sem). */
    fun resetProjectId() {
        prefs.edit().remove(KEY_PROJECT).apply()
        projectId.value = BuildConfig.DEFAULT_PROJECT_ID
        groups.value = GroupsState()
        loadGroups()
    }

    fun loadGroups() {
        if (!isConfigured || groups.value.loading) return
        groups.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val api = api()
                val loaded = withContext(Dispatchers.IO) {
                    val profiles = api.profiles()
                    coroutineScope {
                        profiles.map { profile ->
                            async { Group(profile, runCatching { api.unreadCount(profile.id) }.getOrNull()) }
                        }.awaitAll()
                    }
                }
                groups.value = GroupsState(groups = loaded)
            } catch (e: Exception) {
                groups.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    fun openGroup(profile: Profile) {
        feedJob?.cancel()
        val known = groups.value.groups.firstOrNull { it.profile.id == profile.id }?.unread
        feed.value = FeedState(profile = profile, unread = known, loading = true)
        loadPage(reset = true)
    }

    fun refreshFeed() {
        val profile = feed.value.profile ?: return
        feedJob?.cancel()
        feed.update { it.copy(loading = true, error = null) }
        loadPage(reset = true)
        refreshCount(profile.id)
    }

    fun loadMore() {
        val state = feed.value
        if (state.loading || !state.hasMore) return
        feed.update { it.copy(loading = true, error = null) }
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean) {
        val profile = feed.value.profile ?: return
        val after = if (reset) null else feed.value.articles.lastOrNull()?.cursor
        feedJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { api().unreadPage(profile.id, PAGE_SIZE, after) }
                feed.update { current ->
                    if (current.profile?.id != profile.id) return@update current
                    val known = if (reset) emptySet() else current.articles.mapTo(HashSet()) { it.id }
                    val merged = (if (reset) emptyList() else current.articles) + page.filter { it.id !in known }
                    current.copy(
                        articles = merged,
                        loading = false,
                        error = null,
                        hasMore = page.size == PAGE_SIZE,
                    )
                }
            } catch (e: Exception) {
                feed.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    private fun refreshCount(profileId: String) {
        scope.launch {
            val count = runCatching { withContext(Dispatchers.IO) { api().unreadCount(profileId) } }.getOrNull()
                ?: return@launch
            setCount(profileId) { count }
        }
    }

    private fun setCount(profileId: String, change: (Int) -> Int) {
        groups.update { state ->
            state.copy(
                groups = state.groups.map { g ->
                    if (g.profile.id == profileId && g.unread != null) {
                        g.copy(unread = change(g.unread).coerceAtLeast(0))
                    } else {
                        g
                    }
                },
            )
        }
        feed.update { state ->
            if (state.profile?.id == profileId && state.unread != null) {
                state.copy(unread = change(state.unread).coerceAtLeast(0))
            } else {
                state
            }
        }
    }

    fun open(article: Article) {
        this.article.value = article
    }

    /**
     * Marca como lido: some da lista na hora e grava no Firestore em seguida.
     * O celular enxerga a mudança, e o pipeline apaga lidos não favoritados no
     * ciclo seguinte, como já faz hoje.
     */
    fun markRead(article: Article) {
        val profileId = feed.value.profile?.id
        val wasListed = feed.value.articles.any { it.id == article.id }
        feed.update { it.copy(articles = it.articles.filterNot { a -> a.id == article.id }) }
        if (wasListed && profileId != null) setCount(profileId) { it - 1 }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().markRead(article) }
            } catch (e: Exception) {
                messages.tryEmit("Não marcou como lido: ${describe(e)}")
                refreshFeed()
            }
        }
    }

    fun toggleFavorite(article: Article) {
        val next = !article.favorite
        replace(article.copy(favorite = next))
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api().setFavorite(article, next) }
                if (next) messages.tryEmit("Guardado para ler no celular")
            } catch (e: Exception) {
                replace(article)
                messages.tryEmit("Não salvou o favorito: ${describe(e)}")
            }
        }
    }

    private fun replace(updated: Article) {
        feed.update { it.copy(articles = it.articles.map { a -> if (a.id == updated.id) updated else a }) }
        article.update { if (it?.id == updated.id) updated else it }
    }

    private fun describe(e: Exception): String = when (e) {
        is UnknownHostException -> "sem conexão"
        is SocketTimeoutException -> "a conexão demorou demais"
        is ApiException -> when (e.code) {
            404 -> "projeto ou artigo não encontrado"
            403 -> "o Firestore recusou o acesso"
            400 -> "pedido recusado (${e.message})"
            else -> "erro ${e.code} do Firestore"
        }
        is IOException -> "falha de rede"
        else -> e.message ?: "erro inesperado"
    }

    companion object {
        const val PAGE_SIZE = 8
        private const val KEY_PROJECT = "project_id"
    }
}
