package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.api.JellyfinApi
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate

/** SyncPlay outside the player: lists groups and finds out what a group is watching. */
class SyncPlayGroupFinder @Inject constructor(private val jellyfinApi: JellyfinApi) {
    private val api = jellyfinApi.api

    suspend fun getGroups(): List<GroupInfoDto> = api.syncPlayApi.syncPlayGetGroups().content

    /**
     * Joins the group and returns the item it is watching. The player joins the same group again
     * once it has loaded the item, which the server treats as restoring the session. Returns null,
     * and leaves the group, when nothing is playing.
     */
    suspend fun joinAndGetPlayingItem(groupId: UUID): UUID? = coroutineScope {
        // Start listening before joining so the play queue the server sends is not missed
        val playQueue = async {
            withTimeoutOrNull(10_000) {
                api.webSocket
                    .subscribe<SyncPlayGroupUpdateMessage>()
                    .mapNotNull { (it.data as? SyncPlayPlayQueueUpdate)?.data }
                    .first()
            }
        }
        withTimeout(10_000) { api.webSocket.state.first { it is SocketApiState.Connected } }
        api.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId))
        val update = playQueue.await()
        val itemId = update?.playlist?.getOrNull(update.playingItemIndex)?.itemId
        if (itemId == null) leaveGroup()
        itemId
    }

    suspend fun leaveGroup() {
        api.syncPlayApi.syncPlayLeaveGroup()
    }
}
