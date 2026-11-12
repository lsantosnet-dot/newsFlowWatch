package com.lsantosnet.newsflow.watch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.lsantosnet.newsflow.watch.BuildConfig
import com.lsantosnet.newsflow.watch.repository

/**
 * Única configuração do relógio: de qual projeto Firebase ler.
 *
 * Não há chave para digitar. O Firestore do NewsFlow libera leitura pública e
 * só aceita, em artigos, marcar lido e favoritar; por isso o ID do projeto
 * basta. Grupos, fontes e critérios continuam sendo editados no celular.
 */
@Composable
fun SettingsScreen(onEditProjectId: () -> Unit) {
    val repository = LocalContext.current.repository
    val projectId by repository.projectId.collectAsState()
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Configuração") } }
            item {
                Text(
                    text = "Projeto Firebase",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    text = projectId.ifBlank { "não definido" },
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Button(
                    onClick = onEditProjectId,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (projectId.isBlank()) "Digitar o ID" else "Trocar o ID") },
                )
            }
            if (BuildConfig.DEFAULT_PROJECT_ID.isNotBlank() && projectId != BuildConfig.DEFAULT_PROJECT_ID) {
                item {
                    FilledTonalButton(
                        onClick = { repository.resetProjectId() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Voltar ao padrão") },
                    )
                }
            }
            item {
                Text(
                    text = "Só o ID do projeto fica no relógio, sem chave. Grupos e fontes são editados no celular.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Text(
                    text = "Versão ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
