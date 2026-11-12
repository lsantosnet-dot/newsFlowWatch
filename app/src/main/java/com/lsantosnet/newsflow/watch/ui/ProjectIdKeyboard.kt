package com.lsantosnet.newsflow.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

private val LETTER_ROWS = listOf("abcdef", "ghijklm", "nopqrst", "uvwxyz")
private val DIGIT_ROWS = listOf("12345", "67890")
private const val MAX_LENGTH = 30
private const val VISIBLE_TAIL = 11

/**
 * Teclado próprio para o ID do projeto Firebase: letras minúsculas, números e
 * hífen, que são os únicos caracteres válidos. Existe porque o teclado do
 * sistema de alguns relógios (Galaxy Watch) não oferece números nem hífen.
 *
 * As linhas têm larguras diferentes para acompanhar a tela redonda: mais
 * teclas no meio, menos perto das bordas. Tudo é proporcional à tela.
 *
 * [onConfirm] devolve `false` se o ID for recusado; a tela continua aberta.
 */
@Composable
fun ProjectIdKeyboardScreen(initial: String, onConfirm: (String) -> Boolean) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var digits by rememberSaveable { mutableStateOf(false) }

    fun type(char: Char) {
        if (text.length < MAX_LENGTH) text += char
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Unidade: 1/227 da largura (227 dp é um relógio redondo pequeno).
        val u = minOf(maxWidth, maxHeight) / 227f
        val gap = u * 2

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                Text(
                    text = when {
                        text.isEmpty() -> "ID do projeto"
                        text.length > VISIBLE_TAIL -> "…" + text.takeLast(VISIBLE_TAIL)
                        else -> text
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(u * 104),
                )
                Key(width = u * 28, height = u * 28, label = "Apagar", onClick = { text = text.dropLast(1) }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = null,
                        modifier = Modifier.size(u * 16),
                    )
                }
            }

            // Altura fixa para a tela não pular ao alternar entre letras e números.
            Column(
                modifier = Modifier.height(u * 118),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
            ) {
                val rows = if (digits) DIGIT_ROWS else LETTER_ROWS
                val keyWidth = if (digits) u * 34 else u * 26
                val keyHeight = if (digits) u * 40 else u * 28
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { char ->
                            Key(width = keyWidth, height = keyHeight, label = char.toString(), onClick = { type(char) }) {
                                Text(char.toString(), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Key(
                    width = u * 40,
                    height = u * 28,
                    label = if (digits) "Letras" else "Números",
                    onClick = { digits = !digits },
                ) {
                    Text(if (digits) "abc" else "123", style = MaterialTheme.typography.labelMedium)
                }
                Key(width = u * 40, height = u * 28, label = "Hífen", onClick = { type('-') }) {
                    Text("-", style = MaterialTheme.typography.titleSmall)
                }
                Key(
                    width = u * 40,
                    height = u * 28,
                    label = "Salvar",
                    primary = true,
                    onClick = { onConfirm(text) },
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(u * 18),
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(
    width: Dp,
    height: Dp,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
