package dev.jdtech.jellyfin.player.local.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.ForceKeepAliveMessage
import org.jellyfin.sdk.model.api.InboundKeepAliveMessage
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import timber.log.Timber

/**
 * WebSocket used for SyncPlay. The SDK's socket hands messages over through a StateFlow, which
 * drops a message when the next one arrives before it was handled. The server sends a playback
 * command and the group state update right after each other, so commands got lost. This socket
 * delivers every message and reconnects when the connection drops.
 */
class SyncPlaySocket(private val api: ApiClient) {
    private val client = OkHttpClient()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun messages(): Flow<OutboundWebSocketMessage> =
        callbackFlow {
                val accessToken = checkNotNull(api.accessToken) { "Not logged in" }
                val request =
                    Request.Builder()
                        .url(api.createUrl("/socket"))
                        .header(
                            "Authorization",
                            AuthorizationHeaderBuilder.buildHeader(
                                clientName = api.clientInfo.name,
                                clientVersion = api.clientInfo.version,
                                deviceId = api.deviceInfo.id,
                                deviceName = api.deviceInfo.name,
                                accessToken = accessToken,
                            ),
                        )
                        .build()

                var keepAliveJob: Job? = null
                val listener =
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            _isConnected.value = true
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message =
                                try {
                                    ApiSerializer.decodeSocketMessage(text)
                                } catch (e: Exception) {
                                    Timber.tag(SyncPlayManager.TAG).w(e, "Unknown message")
                                    return
                                }
                            if (message is ForceKeepAliveMessage) {
                                // The server drops the socket without a keep alive within this time
                                val intervalMs = message.data * 1_000L / 2
                                keepAliveJob?.cancel()
                                keepAliveJob = launch {
                                    while (isActive) {
                                        webSocket.send(
                                            ApiSerializer.encodeSocketMessage(
                                                InboundKeepAliveMessage()
                                            )
                                        )
                                        delay(intervalMs)
                                    }
                                }
                            }
                            trySend(message)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            close(IllegalStateException("Socket closed: $code $reason"))
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            close(t)
                        }
                    }

                val webSocket = client.newWebSocket(request, listener)
                awaitClose {
                    _isConnected.value = false
                    webSocket.close(1000, null)
                }
            }
            .buffer(Channel.UNLIMITED)
            .retryWhen { cause, attempt ->
                Timber.tag(SyncPlayManager.TAG).w(cause, "Socket lost, reconnecting")
                delay(minOf(1_000L * (attempt + 1), 10_000L))
                true
            }
}
