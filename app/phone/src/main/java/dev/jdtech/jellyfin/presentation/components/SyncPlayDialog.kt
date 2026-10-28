package dev.jdtech.jellyfin.presentation.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.R as PlayerLocalR
import dev.jdtech.jellyfin.player.local.domain.SyncPlayManager.SyncPlayState
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType

/**
 * SyncPlay groups dialog. Shows the group we are in with its members, or the groups that can be
 * joined. Callers keep [state] and [groups] up to date, the dialog redraws as they change.
 *
 * @param groups groups to join, null while they are still loading
 * @param isBusy shows a progress indicator instead of the content, e.g. while joining
 * @param onCreate creates a new group, the button is hidden when null
 */
@Composable
fun SyncPlayDialog(
    state: SyncPlayState?,
    groups: List<GroupInfoDto>?,
    onJoin: (GroupInfoDto) -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
    isBusy: Boolean = false,
    onCreate: (() -> Unit)? = null,
    @StringRes emptyMessage: Int = PlayerLocalR.string.syncplay_no_groups,
) {
    BaseDialog(
        title = state?.groupName ?: stringResource(PlayerLocalR.string.syncplay),
        onDismiss = onDismiss,
        negativeButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(PlayerLocalR.string.syncplay_close))
            }
        },
        positiveButton = {
            when {
                state != null ->
                    TextButton(onClick = onLeave) {
                        Text(
                            text = stringResource(PlayerLocalR.string.syncplay_leave_group),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                onCreate != null ->
                    TextButton(onClick = onCreate, enabled = !isBusy) {
                        Text(text = stringResource(PlayerLocalR.string.syncplay_new_group))
                    }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
        ) {
            when {
                isBusy || (state == null && groups == null) -> Loading()
                state != null -> {
                    Text(
                        text =
                            stringResource(PlayerLocalR.string.syncplay_watching) +
                                " · " +
                                pluralStringResource(
                                    PlayerLocalR.plurals.syncplay_participants,
                                    state.participants.size,
                                    state.participants.size,
                                ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(contentPadding),
                    )
                    state.participants.forEach { participant ->
                        SyncPlayRow(
                            icon = CoreR.drawable.ic_user,
                            title = participant,
                            subtitle = null,
                            onClick = null,
                        )
                    }
                    if (state.isWaiting) {
                        Text(
                            text = stringResource(PlayerLocalR.string.syncplay_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(contentPadding),
                        )
                    }
                }
                groups.isNullOrEmpty() ->
                    Text(
                        text = stringResource(emptyMessage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(contentPadding),
                    )
                else ->
                    groups.orEmpty().forEach { group ->
                        SyncPlayRow(
                            icon = CoreR.drawable.ic_users,
                            title = group.groupName,
                            subtitle =
                                pluralStringResource(
                                    PlayerLocalR.plurals.syncplay_participants,
                                    group.participants.size,
                                    group.participants.size,
                                ) + " · " + stringResource(group.state.labelRes()),
                            onClick = { onJoin(group) },
                        )
                    }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SyncPlayRow(icon: Int, title: String, subtitle: String?, onClick: (() -> Unit)?) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(
                    horizontal = MaterialTheme.spacings.default,
                    vertical = MaterialTheme.spacings.small,
                ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun GroupStateType.labelRes(): Int =
    when (this) {
        GroupStateType.PLAYING -> PlayerLocalR.string.syncplay_state_playing
        GroupStateType.PAUSED -> PlayerLocalR.string.syncplay_state_paused
        GroupStateType.WAITING -> PlayerLocalR.string.syncplay_state_waiting
        GroupStateType.IDLE -> PlayerLocalR.string.syncplay_state_idle
    }

@Preview
@Composable
private fun SyncPlayDialogPreview() {
    FindroidTheme {
        SyncPlayDialog(
            state = null,
            groups =
                listOf(
                    GroupInfoDto(
                        groupId = UUID.randomUUID(),
                        groupName = "Jonas's group",
                        state = GroupStateType.PLAYING,
                        participants = listOf("jonas", "alex"),
                        lastUpdatedAt = LocalDateTime.now(),
                    )
                ),
            onJoin = {},
            onLeave = {},
            onDismiss = {},
            onCreate = {},
        )
    }
}
