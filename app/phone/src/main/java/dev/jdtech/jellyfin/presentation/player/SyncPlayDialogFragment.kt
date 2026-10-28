package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jdtech.jellyfin.player.local.R
import dev.jdtech.jellyfin.player.local.presentation.PlayerViewModel
import java.lang.IllegalStateException
import org.jellyfin.sdk.model.api.GroupInfoDto

class SyncPlayDialogFragment(
    private val viewModel: PlayerViewModel,
    private val groups: List<GroupInfoDto>,
) : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { activity ->
            val builder = MaterialAlertDialogBuilder(activity)
            val groupName = viewModel.syncPlay.groupName.value
            if (groupName != null) {
                val items = arrayOf(getString(R.string.syncplay_leave_group))
                builder.setTitle(groupName).setItems(items) { dialog, _ ->
                    viewModel.syncPlay.leaveGroup()
                    dialog.dismiss()
                }
            } else {
                val groupTexts = groups.map { group ->
                    getString(
                        R.string.syncplay_group_item,
                        group.groupName,
                        group.participants.size,
                    )
                }
                val items = (groupTexts + getString(R.string.syncplay_new_group)).toTypedArray()
                builder.setTitle(getString(R.string.syncplay)).setItems(items) { dialog, which ->
                    val group = groups.getOrNull(which)
                    if (group != null) {
                        viewModel.syncPlay.joinGroup(group.groupId)
                    } else {
                        viewModel.syncPlay.createGroup()
                    }
                    dialog.dismiss()
                }
            }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fix for hiding the system bars on API < 30
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
