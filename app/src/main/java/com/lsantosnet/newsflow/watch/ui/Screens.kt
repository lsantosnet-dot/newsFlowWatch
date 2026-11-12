package com.lsantosnet.newsflow.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.media.AudioManager
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.lsantosnet.newsflow.watch.data.Article
import com.lsantosnet.newsflow.watch.data.Labels
import com.lsantosnet.newsflow.watch.data.Profile
import com.lsantosnet.newsflow.watch.repository
import com.lsantosnet.newsflow.watch.tts.Player
import com.lsantosnet.newsflow.watch.tts.ReaderService

/** Os grupos usam nomes de ícones do Material (definidos no app do celular). */
private fun profileIcon(name: String): ImageVector = when (name) {
    "trending_up" -> Icons.AutoMirrored.Rounded.TrendingUp
    "lightbulb" -> Icons.Rounded.Lightbulb
    "account_balance" -> Icons.Rounded.AccountBalance
    "public" -> Icons.Rounded.Public
    "code" -> Icons.Rounded.Code
    else -> Icons.AutoMirrored.Rounded.Article
}

/** Lista rolável padrão das telas: curva com a tela redonda e segue o bisel. */
@Composable
private fun WatchList(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

private fun ScalingLazyListScope.loading() {
    item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
}

/** Atalho de volta para a leitura em andamento, quando há uma. */
private fun ScalingLazyListScope.nowPlaying(title: String?, onOpenPlayer: () -> Unit) {
    if (title == null) return
    item {
        Button(
            onClick = onOpenPlayer,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
            secondaryLabel = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            label = { Text("Ouvindo agora") },
        )
    }
}

// ---------- Grupos ----------

@Composable
fun GroupsScreen(onOpenGroup: (Profile) -> Unit, onOpenPlayer: () -> Unit, onOpenSettings: () -> Unit) {
    val repository = LocalContext.current.repository
    val state by repository.groups.collectAsState()
    val projectId by repository.projectId.collectAsState()
    val player by Player.state.collectAsState()

    // Recarrega sempre que a tela volta, para as contagens acompanharem o que foi lido.
    LaunchedEffect(projectId) { repository.loadGroups() }

    WatchList {
        item { ListHeader { Text("NewsFlow") } }
        nowPlaying(player.current?.title, onOpenPlayer)
        when {
            !repository.isConfigured -> {
                item { Note("Falta informar o ID do projeto Firebase para carregar seus grupos.") }
                item { Button(onClick = onOpenSettings, label = { Text("Configurar") }) }
            }
            state.groups.isEmpty() && state.loading -> loading()
            state.groups.isEmpty() && state.error != null -> {
                item { Note("Não carregou: ${state.error}") }
                item { Button(onClick = { repository.loadGroups() }, label = { Text("Tentar de novo") }) }
            }
            state.groups.isEmpty() -> item { Note("Nenhum grupo ainda. Crie um no app do celular.") }
            else -> items(state.groups, key = { it.profile.id }) { group ->
                FilledTonalButton(
                    onClick = { onOpenGroup(group.profile) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(profileIcon(group.profile.icon), contentDescription = null) },
                    secondaryLabel = { Text(Labels.unread(group.unread)) },
                    label = { Text(group.profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        if (repository.isConfigured) {
            item {
                CompactButton(
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("Configuração") },
                )
            }
        }
    }
}

// ---------- Lista de não lidos ----------

@Composable
fun FeedScreen(onOpenArticle: (Article) -> Unit, onListenAll: () -> Unit, onOpenPlayer: () -> Unit) {
    val repository = LocalContext.current.repository
    val state by repository.feed.collectAsState()
    val player by Player.state.collectAsState()
    val now = System.currentTimeMillis()

    WatchList {
        item {
            ListHeader {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.profile?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Labels.unread(state.unread),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        nowPlaying(player.current?.title, onOpenPlayer)
        if (state.articles.isNotEmpty() && !player.active) {
            item {
                Button(
                    onClick = onListenAll,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    label = { Text("Ouvir tudo") },
                )
            }
        }
        items(state.articles, key = { it.id }) { article ->
            Card(onClick = { onOpenArticle(article) }, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = listOf(
                            if (article.favorite) "★" else "",
                            article.sourceName,
                            Labels.age(now, article.curatedAtMillis),
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = article.score.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            state.loading -> loading()
            state.error != null -> {
                item { Note("Não carregou: ${state.error}") }
                item {
                    FilledTonalButton(
                        onClick = { if (state.articles.isEmpty()) repository.refreshFeed() else repository.loadMore() },
                        label = { Text("Tentar de novo") },
                    )
                }
            }
            state.hasMore -> item {
                FilledTonalButton(
                    onClick = { repository.loadMore() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mais artigos", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                )
            }
            state.articles.isEmpty() -> item { Note("Nada para ler aqui. O próximo ciclo da curadoria traz mais.") }
        }
    }
}

// ---------- Artigo ----------

@Composable
fun ArticleScreen(onListen: (Article) -> Unit, onClose: () -> Unit) {
    val repository = LocalContext.current.repository
    val current by repository.article.collectAsState()
    val readIds by repository.readIds.collectAsState()
    val article = current ?: return

    // Marcado como lido (pelo botão ou por ter sido ouvido até o fim): fecha a tela.
    val isRead = article.id in readIds
    LaunchedEffect(isRead) { if (isRead) onClose() }

    WatchList {
        item {
            Note(
                listOf(
                    article.sourceName,
                    Labels.age(System.currentTimeMillis(), article.curatedAtMillis),
                    "nota ${article.score}",
                ).filter { it.isNotEmpty() }.joinToString(" · "),
            )
        }
        item {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Text(
                text = article.summary.ifBlank { article.ttsText },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (article.extraSources > 0) {
            item { Note(Labels.extraSources(article.extraSources)) }
        }
        item {
            Button(
                onClick = { onListen(article) },
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(Icons.Rounded.Headphones, contentDescription = null) },
                label = { Text("Ouvir") },
            )
        }
        item {
            FilledTonalButton(
                onClick = { repository.toggleFavorite(article) },
                modifier = Modifier.fillMaxWidth(),
                icon = {
                    Icon(
                        if (article.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        tint = if (article.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                },
                label = { Text(if (article.favorite) "Guardado" else "Guardar p/ celular") },
            )
        }
        item {
            FilledTonalButton(
                onClick = { repository.markRead(article) },
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(Icons.Rounded.Check, contentDescription = null) },
                label = { Text("Marcar como lido") },
            )
        }
    }
}

// ---------- Ouvir ----------

@Composable
fun PlayerScreen(onClosed: () -> Unit) {
    val context = LocalContext.current
    val state by Player.state.collectAsState()

    // A fila acabou (ou a leitura foi encerrada pela notificação): sai da tela.
    LaunchedEffect(state.active) { if (!state.active) onClosed() }
    val article = state.current ?: return

    fun volume(direction: Int) {
        context.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxSize().padding(3.dp),
                strokeWidth = 4.dp,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${state.index + 1} de ${state.queue.size} · ${state.groupName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = article.title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalIconButton(onClick = { ReaderService.previous(context) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Artigo anterior")
                    }
                    FilledIconButton(
                        onClick = { if (state.playing) ReaderService.pause(context) else ReaderService.resume(context) },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.playing) "Pausar" else "Ouvir",
                        )
                    }
                    FilledTonalIconButton(onClick = { ReaderService.next(context) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Próximo artigo")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(onClick = { volume(AudioManager.ADJUST_LOWER) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = "Diminuir o volume")
                    }
                    IconButton(onClick = { volume(AudioManager.ADJUST_RAISE) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Aumentar o volume")
                    }
                }
            }
        }
    }
}
