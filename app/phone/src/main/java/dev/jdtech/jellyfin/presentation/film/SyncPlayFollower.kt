package dev.jdtech.jellyfin.presentation.film

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.film.components.VersionSelectionDialog
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Opens the title a SyncPlay group starts, wherever the app is. Asks for the version first when
 * there are several, so everyone can pick their own (e.g. 4K or 1080p).
 */
@Composable
fun SyncPlayFollower(viewModel: SyncPlayViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val pendingOpen by viewModel.pendingOpen.collectAsStateWithLifecycle()
    val openItem by viewModel.openItem.collectAsStateWithLifecycle()

    LaunchedEffect(pendingOpen) { pendingOpen?.let { viewModel.resolvePendingOpen(it) } }

    val item = openItem ?: return
    if (item.sources.size > 1) {
        VersionSelectionDialog(
            mediaSources = item.sources,
            onSelect = { mediaSourceId ->
                viewModel.consumeOpenItem()
                startPlayer(context, item, mediaSourceId)
            },
            onDismiss = { viewModel.consumeOpenItem() },
        )
    } else {
        LaunchedEffect(item) {
            viewModel.consumeOpenItem()
            startPlayer(context, item, null)
        }
    }
}

private fun startPlayer(context: Context, item: FindroidItem, mediaSourceId: String?) {
    val itemKind = if (item is FindroidEpisode) BaseItemKind.EPISODE else BaseItemKind.MOVIE
    val intent = Intent(context, PlayerActivity::class.java)
    intent.putExtra("itemId", item.id.toString())
    intent.putExtra("itemKind", itemKind.serialName)
    intent.putExtra("mediaSourceId", mediaSourceId)
    intent.putExtra("startFromBeginning", false)
    context.startActivity(intent)
}
