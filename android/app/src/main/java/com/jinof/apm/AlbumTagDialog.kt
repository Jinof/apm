package com.jinof.apm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AlbumTagDialog(
    photos: List<GalleryPhotoCard>,
    canSearch: Boolean,
    onTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opening the directory is the only operation that needs a full tag tally.
    val tags by produceState<List<TagCount>?>(null, photos) {
        value = withContext(Dispatchers.Default) { countGalleryTags(photos) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("all_tags_dialog"),
        title = { Text("全部标签") },
        text = {
            val ranked = tags
            when {
                ranked == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                ranked.isEmpty() -> Text("暂无标签")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(124.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("ranked_tags"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ranked, key = TagCount::label) { tag ->
                        Surface(
                            onClick = { onTag(tag.label) },
                            enabled = canSearch,
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tag.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("${tag.photoCount} 张", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
