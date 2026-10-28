package dev.jdtech.jellyfin.presentation.film

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.player.local.domain.SyncPlayManager
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.GroupInfoDto
import timber.log.Timber

/** SyncPlay outside the player: the groups dialog and opening what the group watches. */
@HiltViewModel
class SyncPlayViewModel
@Inject
constructor(
    private val manager: SyncPlayManager,
    private val repository: JellyfinRepository,
) : ViewModel() {
    val state = manager.state
    val pendingOpen = manager.pendingOpen

    private val _groups = MutableStateFlow<List<GroupInfoDto>?>(null)
    val groups = _groups.asStateFlow()

    private val _openItem = MutableStateFlow<FindroidItem?>(null)
    /** The title the group started, ready to be opened in the player. */
    val openItem = _openItem.asStateFlow()

    suspend fun refreshGroups() {
        try {
            _groups.value = manager.getGroups()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    fun createGroup() = manager.createGroup()

    fun joinGroup(groupId: UUID) = manager.joinGroup(groupId)

    fun leaveGroup() = manager.leaveGroup()

    /** Looks up the title the group started so it can be opened. */
    fun resolvePendingOpen(queueItem: SyncPlayManager.QueueItem) {
        manager.consumePendingOpen()
        viewModelScope.launch {
            try {
                val item = repository.getItem(queueItem.itemId)
                if (item is FindroidMovie || item is FindroidEpisode) _openItem.value = item
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    fun consumeOpenItem() {
        _openItem.value = null
    }
}
