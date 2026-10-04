package com.fitair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.fitair.app.ui.theme.Shapes
import com.fitair.app.ui.theme.Spacing

/** A titled section card in the detail-page style (surface variant, 12 dp corners, 16 dp padding). [title] null = no caption. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Shapes.card).background(MaterialTheme.colorScheme.surfaceVariant).padding(Spacing.l)) {
        if (title != null) {
            SectionHeader(title)
            Spacer(Modifier.height(Spacing.m))
        }
        content()
    }
}
