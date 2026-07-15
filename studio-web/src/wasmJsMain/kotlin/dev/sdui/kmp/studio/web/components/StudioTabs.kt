package dev.sdui.kmp.studio.web.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Dense, left-aligned underline tabs. Material3's `TabRow` stretches tabs across the full
 * width — wrong for a pro tool — so this draws its own compact row with a 2dp accent
 * underline on the selection and a full-width hairline underneath.
 */
@Composable
internal fun StudioTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(index) }
                        .padding(horizontal = TAB_H_PADDING),
                ) {
                    Box(Modifier.height(TAB_HEIGHT), contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Box(
                        Modifier
                            .width(TAB_UNDERLINE_WIDTH)
                            .height(TAB_UNDERLINE_HEIGHT)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            ),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private val TAB_HEIGHT = 32.dp
private val TAB_H_PADDING = 12.dp
private val TAB_UNDERLINE_WIDTH = 48.dp
private val TAB_UNDERLINE_HEIGHT = 2.dp
