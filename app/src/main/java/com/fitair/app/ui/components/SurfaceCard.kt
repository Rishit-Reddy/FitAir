package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing

/** A neutral rounded card on the page (same surface and light-theme hairline as the Today tiles) for detail screens. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val border = if (dark) Modifier else Modifier.border(1.dp, Color(0xFFE7E7E4), Shapes.tile)
    Column(modifier.fillMaxWidth().then(border).clip(Shapes.tile).background(MaterialTheme.colorScheme.surfaceContainer).padding(Spacing.l), content = content)
}
