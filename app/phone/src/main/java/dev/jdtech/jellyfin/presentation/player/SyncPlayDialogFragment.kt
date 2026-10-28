package dev.jdtech.jellyfin.presentation.player

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
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
            val state = viewModel.syncPlay.state.value
            if (state != null) {
                // In a group: show who is watching and offer to leave
                val people =
                    resources.getQuantityString(
                        R.plurals.syncplay_participants,
                        state.participants.size,
                        state.participants.size,
                    )
                val members = state.participants.joinToString("\n") { "•  $it" }
                builder
                    .setTitle(state.groupName)
                    .setMessage("${getString(R.string.syncplay_watching)} · $people\n\n$members")
                    .setPositiveButton(R.string.syncplay_leave_group) { _, _ ->
                        viewModel.syncPlay.leaveGroup()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
            } else {
                builder
                    .setTitle(R.string.syncplay)
                    .setPositiveButton(R.string.syncplay_new_group) { _, _ ->
                        viewModel.syncPlay.createGroup()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                if (groups.isEmpty()) {
                    builder.setMessage(R.string.syncplay_no_groups)
                } else {
                    builder.setAdapter(GroupAdapter(activity, groups)) { dialog, which ->
                        viewModel.syncPlay.joinGroup(groups[which].groupId)
                        dialog.dismiss()
                    }
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

    /** Two-line rows: group name, and how many people are in it. */
    private class GroupAdapter(context: Context, groups: List<GroupInfoDto>) :
        ArrayAdapter<GroupInfoDto>(
            context,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            groups,
        ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val group = getItem(position) ?: return view
            view.findViewById<TextView>(android.R.id.text1).text = group.groupName
            view.findViewById<TextView>(android.R.id.text2).text =
                context.resources.getQuantityString(
                    R.plurals.syncplay_participants,
                    group.participants.size,
                    group.participants.size,
                )
            return view
        }
    }
}
