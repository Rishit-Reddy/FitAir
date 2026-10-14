package com.fitair.app.ui.weight

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fitair.app.ui.components.DetailHeader
import com.fitair.app.ui.components.Page

/** Weight and BMI entry as its own page (opened from More or from the Weight card on Metrics). */
@Composable
fun WeightScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader("Weight and BMI", onBack)
        Page(Modifier.weight(1f)) { WeightEntry() }
    }
}
