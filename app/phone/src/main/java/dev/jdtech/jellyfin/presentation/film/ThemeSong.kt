package dev.jdtech.jellyfin.presentation.film

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.player.local.domain.ThemeSongPlayer
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ThemeSongViewModel @Inject constructor(val player: ThemeSongPlayer) : ViewModel()

/**
 * Plays the theme song of [itemId] while the page is shown. It stops when leaving the page or the
 * app, and when starting playback since the player covers the page.
 */
@Composable
fun ThemeSong(itemId: UUID, viewModel: ThemeSongViewModel = hiltViewModel()) {
    if (LocalOfflineMode.current) return
    LifecycleStartEffect(itemId) {
        viewModel.player.play(itemId)
        onStopOrDispose { viewModel.player.stop() }
    }
}
