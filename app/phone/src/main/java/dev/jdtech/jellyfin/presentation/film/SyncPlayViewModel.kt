package dev.jdtech.jellyfin.presentation.film

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.player.local.R as PlayerLocalR
import dev.jdtech.jellyfin.player.local.domain.SyncPlayGroupFinder
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import timber.log.Timber

/** Joins a SyncPlay group from outside the player and finds the title to open for it. */
@HiltViewModel
class SyncPlayViewModel
@Inject
constructor(
    private val groupFinder: SyncPlayGroupFinder,
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _groups = MutableStateFlow<List<GroupInfoDto>?>(null)
    val groups = _groups.asStateFlow()

    private val _isJoining = MutableStateFlow(false)
    val isJoining = _isJoining.asStateFlow()

    private val eventsChannel = Channel<SyncPlayEvent>()
    val events = eventsChannel.receiveAsFlow()

    suspend fun refreshGroups() {
        try {
            _groups.value = groupFinder.getGroups()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    fun join(group: GroupInfoDto) {
        if (group.state == GroupStateType.IDLE) {
            viewModelScope.launch {
                eventsChannel.send(
                    SyncPlayEvent.Message(PlayerLocalR.string.syncplay_nothing_playing)
                )
            }
            return
        }
        viewModelScope.launch {
            _isJoining.value = true
            try {
                val itemId = groupFinder.joinAndGetPlayingItem(group.groupId)
                val item = itemId?.let { repository.getItem(it) }
                when {
                    itemId == null ->
                        eventsChannel.send(
                            SyncPlayEvent.Message(PlayerLocalR.string.syncplay_nothing_playing)
                        )
                    item != null && (item is FindroidMovie || item is FindroidEpisode) ->
                        eventsChannel.send(SyncPlayEvent.Open(item, group.groupId))
                    else -> {
                        groupFinder.leaveGroup()
                        eventsChannel.send(
                            SyncPlayEvent.Message(PlayerLocalR.string.syncplay_unsupported_item)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e)
                eventsChannel.send(
                    SyncPlayEvent.Message(PlayerLocalR.string.syncplay_request_failed)
                )
            } finally {
                _isJoining.value = false
            }
        }
    }

    /** Leaves the group joined by [join] when the title is not opened after all. */
    fun cancelJoin() {
        viewModelScope.launch {
            try {
                groupFinder.leaveGroup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }
}

sealed interface SyncPlayEvent {
    data class Open(val item: FindroidItem, val groupId: UUID) : SyncPlayEvent

    data class Message(@StringRes val resId: Int) : SyncPlayEvent
}
