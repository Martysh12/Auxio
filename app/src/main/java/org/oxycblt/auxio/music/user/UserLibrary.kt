/*
 * Copyright (c) 2023 Auxio Project
 * UserLibrary.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music.user

import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import org.oxycblt.auxio.music.*
import org.oxycblt.auxio.music.device.DeviceLibrary

/**
 * Organized library information controlled by the user.
 *
 * Unlike [DeviceLibrary], [UserLibrary]s can be mutated without needing to clone the instance. It
 * is also not backed by library information, rather an app database with in-memory caching. It is
 * generally not expected to create this yourself, and instead rely on MusicRepository.
 *
 * @author Alexander Capehart
 */
interface UserLibrary {
    /** The current user-defined playlists. */
    val playlists: List<Playlist>

    /**
     * Find a [Playlist] instance corresponding to the given [Music.UID].
     *
     * @param uid The [Music.UID] to search for.
     * @return The corresponding [Song], or null if one was not found.
     */
    fun findPlaylist(uid: Music.UID): Playlist?

    /**
     * Finds a playlist by it's [name]. Since all [Playlist] names must be unique, this will always
     * return at most 1 value.
     *
     * @param name The name [String] to search for.
     */
    fun findPlaylist(name: String): Playlist?

    /** Constructs a [UserLibrary] implementation in an asynchronous manner. */
    interface Factory {
        /**
         * Create a new [UserLibrary].
         *
         * @param deviceLibraryChannel Asynchronously populated [DeviceLibrary] that can be obtained
         *   later. This allows database information to be read before the actual instance is
         *   constructed.
         * @return A new [MutableUserLibrary] with the required implementation.
         */
        suspend fun read(deviceLibraryChannel: Channel<DeviceLibrary>): MutableUserLibrary
    }
}

/**
 * A mutable instance of [UserLibrary]. Not meant for use outside of the music module. Use
 * [MusicRepository] instead.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface MutableUserLibrary : UserLibrary {
    /**
     * Make a new [Playlist].
     *
     * @param name The name of the [Playlist].
     * @param songs The songs to place in the [Playlist].
     */
    suspend fun createPlaylist(name: String, songs: List<Song>)

    /**
     * Rename a [Playlist].
     *
     * @param playlist The [Playlist] to rename.
     * @param name The name of the new [Playlist].
     */
    suspend fun renamePlaylist(playlist: Playlist, name: String)

    /**
     * Delete a [Playlist].
     *
     * @param playlist The playlist to delete.
     */
    suspend fun deletePlaylist(playlist: Playlist)

    /**
     * Add [Song]s to a [Playlist].
     *
     * @param playlist The [Playlist] to add to. Must currently exist.
     */
    suspend fun addToPlaylist(playlist: Playlist, songs: List<Song>)

    /**
     * Update the [Song]s of a [Playlist].
     *
     * @param playlist The [Playlist] to update.
     * @param songs The new [Song]s to be contained in the [Playlist].
     */
    suspend fun rewritePlaylist(playlist: Playlist, songs: List<Song>)
}

class UserLibraryFactoryImpl
@Inject
constructor(private val playlistDao: PlaylistDao, private val musicSettings: MusicSettings) :
    UserLibrary.Factory {
    override suspend fun read(deviceLibraryChannel: Channel<DeviceLibrary>): MutableUserLibrary {
        // While were waiting for the library, read our playlists out.
        val rawPlaylists = playlistDao.readRawPlaylists()
        val deviceLibrary = deviceLibraryChannel.receive()
        // Convert the database playlist information to actual usable playlists.
        val playlistMap = mutableMapOf<Music.UID, PlaylistImpl>()
        for (rawPlaylist in rawPlaylists) {
            val playlistImpl = PlaylistImpl.fromRaw(rawPlaylist, deviceLibrary, musicSettings)
            playlistMap[playlistImpl.uid] = playlistImpl
        }
        return UserLibraryImpl(playlistDao, playlistMap, musicSettings)
    }
}

private class UserLibraryImpl(
    private val playlistDao: PlaylistDao,
    private val playlistMap: MutableMap<Music.UID, PlaylistImpl>,
    private val musicSettings: MusicSettings
) : MutableUserLibrary {
    override val playlists: List<Playlist>
        get() = playlistMap.values.toList()

    override fun findPlaylist(uid: Music.UID) = playlistMap[uid]

    override fun findPlaylist(name: String) = playlistMap.values.find { it.name.raw == name }

    override suspend fun createPlaylist(name: String, songs: List<Song>) {
        val playlistImpl = PlaylistImpl.from(name, songs, musicSettings)
        synchronized(this) { playlistMap[playlistImpl.uid] = playlistImpl }
        val rawPlaylist =
            RawPlaylist(
                PlaylistInfo(playlistImpl.uid, playlistImpl.name.raw),
                playlistImpl.songs.map { PlaylistSong(it.uid) })
        playlistDao.insertPlaylist(rawPlaylist)
    }

    override suspend fun renamePlaylist(playlist: Playlist, name: String) {
        val playlistImpl =
            requireNotNull(playlistMap[playlist.uid]) { "Cannot rename invalid playlist" }
        synchronized(this) { playlistMap[playlist.uid] = playlistImpl.edit(name, musicSettings) }
        playlistDao.replacePlaylistInfo(PlaylistInfo(playlist.uid, name))
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        synchronized(this) {
            requireNotNull(playlistMap.remove(playlist.uid)) { "Cannot remove invalid playlist" }
        }
        playlistDao.deletePlaylist(playlist.uid)
    }

    override suspend fun addToPlaylist(playlist: Playlist, songs: List<Song>) {
        val playlistImpl =
            requireNotNull(playlistMap[playlist.uid]) { "Cannot add to invalid playlist" }
        synchronized(this) { playlistMap[playlist.uid] = playlistImpl.edit { addAll(songs) } }
        playlistDao.insertPlaylistSongs(playlist.uid, songs.map { PlaylistSong(it.uid) })
    }

    override suspend fun rewritePlaylist(playlist: Playlist, songs: List<Song>) {
        val playlistImpl =
            requireNotNull(playlistMap[playlist.uid]) { "Cannot rewrite invalid playlist" }
        synchronized(this) { playlistMap[playlist.uid] = playlistImpl.edit(songs) }
        playlistDao.replacePlaylistSongs(playlist.uid, songs.map { PlaylistSong(it.uid) })
    }
}
