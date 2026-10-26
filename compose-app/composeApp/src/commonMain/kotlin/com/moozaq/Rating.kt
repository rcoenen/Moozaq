package com.moozaq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun RatingControls(mix: Mix, ui: PlayerUiState, store: PlayerStore) {
    val rating = ui.profile?.ratings?.get(mix.id)
    val pending = ui.catalogAction?.key?.startsWith("rate:${mix.id}") == true
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        RatingButton("👍", rating == 1, pending, if (rating == 1) "Remove like for ${mix.title}" else "Like ${mix.title}") {
            store.rateSong(mix.id, if (rating == 1) 0 else 1)
        }
        RatingButton("👎", rating == -1, pending, if (rating == -1) "Remove dislike for ${mix.title}" else "Dislike ${mix.title}") {
            store.rateSong(mix.id, if (rating == -1) 0 else -1)
        }
    }
}

@Composable
private fun RatingButton(symbol: String, selected: Boolean, pending: Boolean, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = !pending, modifier = Modifier.size(36.dp).semantics { contentDescription = description }) {
        Text(symbol, color = if (selected) colors.primary else colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}
