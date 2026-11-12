package com.lsantosnet.newsflow.watch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.lsantosnet.newsflow.watch.data.Article
import com.lsantosnet.newsflow.watch.repository
import com.lsantosnet.newsflow.watch.tts.Player
import com.lsantosnet.newsflow.watch.tts.ReaderService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProjectIdFrom(intent)
        setContent { NewsFlowApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyProjectIdFrom(intent)
    }

    /**
     * Define o ID do projeto pelo computador, como alternativa a digitar no relógio:
     * `adb shell am start -n com.lsantosnet.newsflow.watch/.ui.MainActivity --es project_id SEU-PROJETO`
     */
    private fun applyProjectIdFrom(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PROJECT_ID) ?: return
        intent.removeExtra(EXTRA_PROJECT_ID)
        val message = if (repository.setProjectId(id)) "Projeto salvo: ${repository.projectId.value}" else "ID de projeto inválido"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }
}

private object Routes {
    const val GROUPS = "groups"
    const val FEED = "feed"
    const val ARTICLE = "article"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val PROJECT_ID = "project_id"
}

/** Âmbar sobre preto: legível em OLED e econômico em bateria. */
private val NewsFlowColors = ColorScheme(
    primary = Color(0xFFFFB95C),
    primaryDim = Color(0xFFE0A04A),
    primaryContainer = Color(0xFF4A3410),
    onPrimary = Color(0xFF3A2300),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFE2C19A),
    secondaryDim = Color(0xFFC5A680),
    secondaryContainer = Color(0xFF3B3022),
    onSecondary = Color(0xFF3A2A12),
    onSecondaryContainer = Color(0xFFF6DDB9),
    surfaceContainerLow = Color(0xFF1A1713),
    surfaceContainer = Color(0xFF211D18),
    surfaceContainerHigh = Color(0xFF2C2620),
    onSurface = Color(0xFFF2EEE8),
    onSurfaceVariant = Color(0xFFA9A39A),
    outline = Color(0xFF7A736A),
    outlineVariant = Color(0xFF3A332B),
    background = Color.Black,
    onBackground = Color(0xFFF2EEE8),
)

@Composable
fun NewsFlowApp() {
    val context = LocalContext.current
    val repository = context.repository
    val navController = rememberSwipeDismissableNavController()

    // A notificação da leitura só aparece com esta permissão (Android 13+).
    // A leitura em si funciona sem ela, então não bloqueia nada.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun listen(queue: List<Article>, startIndex: Int) {
        if (queue.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ReaderService.play(context, queue, startIndex, repository.feed.value.profile?.name.orEmpty())
        navController.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    LaunchedEffect(Unit) {
        repository.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Aberto pelo ícone de "em andamento" do mostrador: vai direto para a leitura.
    LaunchedEffect(Unit) {
        if (Player.state.value.active) navController.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    MaterialTheme(colorScheme = NewsFlowColors) {
        AppScaffold {
            SwipeDismissableNavHost(navController = navController, startDestination = Routes.GROUPS) {
                composable(Routes.GROUPS) {
                    GroupsScreen(
                        onOpenGroup = {
                            repository.openGroup(it)
                            navController.navigate(Routes.FEED)
                        },
                        onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.FEED) {
                    FeedScreen(
                        onOpenArticle = {
                            repository.open(it)
                            navController.navigate(Routes.ARTICLE)
                        },
                        onListenAll = { listen(repository.feed.value.articles, 0) },
                        onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    )
                }
                composable(Routes.ARTICLE) {
                    ArticleScreen(
                        onListen = { article ->
                            val list = repository.feed.value.articles
                            val index = list.indexOfFirst { it.id == article.id }
                            if (index >= 0) listen(list, index) else listen(listOf(article), 0)
                        },
                        onClose = { navController.popBackStack(Routes.ARTICLE, inclusive = true) },
                    )
                }
                composable(Routes.PLAYER) {
                    PlayerScreen(onClosed = { navController.popBackStack(Routes.PLAYER, inclusive = true) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onEditProjectId = { navController.navigate(Routes.PROJECT_ID) })
                }
                composable(Routes.PROJECT_ID) {
                    ProjectIdKeyboardScreen(
                        initial = repository.projectId.value,
                        onConfirm = { typed ->
                            val saved = repository.setProjectId(typed)
                            Toast.makeText(
                                context,
                                if (saved) "Projeto salvo" else "ID inválido: 6 a 30 caracteres, começando por letra",
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (saved) navController.popBackStack()
                            saved
                        },
                    )
                }
            }
        }
    }
}
