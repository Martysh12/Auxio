/*
 * Copyright (c) 2023 Auxio Project
 * NewPlaylistDialog.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music.picker

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogPlaylistNameBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.ui.ViewBindingDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.unlikelyToBeNull

/**
 * A dialog allowing the name of a new/existing playlist to be edited.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class NewPlaylistDialog : ViewBindingDialogFragment<DialogPlaylistNameBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val pickerModel: PlaylistPickerViewModel by activityViewModels()
    // Information about what playlist to name for is initially within the navigation arguments
    // as UIDs, as that is the only safe way to parcel playlist information.
    private val args: NewPlaylistDialogArgs by navArgs()

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_new_playlist)
            .setPositiveButton(R.string.lbl_ok) { _, _ ->
                val pendingPlaylist = unlikelyToBeNull(pickerModel.currentPendingPlaylist.value)
                val name =
                    when (val chosenName = pickerModel.chosenName.value) {
                        is ChosenName.Valid -> chosenName.value
                        is ChosenName.Empty -> pendingPlaylist.preferredName
                        else -> throw IllegalStateException()
                    }
                // TODO: Navigate to playlist if there are songs in it
                musicModel.createPlaylist(name, pendingPlaylist.songs)
                pickerModel.confirmPlaylistCreation()
                requireContext().showToast(R.string.lng_playlist_created)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogPlaylistNameBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogPlaylistNameBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.playlistName.addTextChangedListener { pickerModel.updateChosenName(it?.toString()) }

        // --- VIEWMODEL SETUP ---
        pickerModel.setPendingPlaylist(requireContext(), args.songUids)
        collectImmediately(pickerModel.currentPendingPlaylist, ::updatePendingPlaylist)
        collectImmediately(pickerModel.chosenName, ::updateChosenName)
    }

    private fun updatePendingPlaylist(pendingPlaylist: PendingPlaylist?) {
        if (pendingPlaylist == null) {
            findNavController().navigateUp()
            return
        }

        requireBinding().playlistName.hint = pendingPlaylist.preferredName
    }

    private fun updateChosenName(chosenName: ChosenName) {
        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            chosenName is ChosenName.Valid || chosenName is ChosenName.Empty
    }
}
