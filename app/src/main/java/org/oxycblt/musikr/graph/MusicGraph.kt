/*
 * Copyright (c) 2024 Auxio Project
 * MusicGraph.kt is part of Auxio.
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
 
package org.oxycblt.musikr.graph

import org.oxycblt.auxio.util.unlikelyToBeNull
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.tag.interpret.PreAlbum
import org.oxycblt.musikr.tag.interpret.PreArtist
import org.oxycblt.musikr.tag.interpret.PreGenre
import org.oxycblt.musikr.tag.interpret.PreSong
import timber.log.Timber as L

data class MusicGraph(
    val songVertex: List<SongVertex>,
    val albumVertex: List<AlbumVertex>,
    val artistVertex: List<ArtistVertex>,
    val genreVertex: List<GenreVertex>
) {
    interface Builder {
        fun add(preSong: PreSong)

        fun build(): MusicGraph
    }

    companion object {
        fun builder(): Builder = MusicGraphBuilderImpl()
    }
}

private class MusicGraphBuilderImpl : MusicGraph.Builder {
    private val songVertices = mutableMapOf<Music.UID, SongVertex>()
    private val albumVertices = mutableMapOf<PreAlbum, AlbumVertex>()
    private val artistVertices = mutableMapOf<PreArtist, ArtistVertex>()
    private val genreVertices = mutableMapOf<PreGenre, GenreVertex>()

    override fun add(preSong: PreSong) {
        val uid = preSong.computeUid()
        if (songVertices.containsKey(uid)) {
            L.d("Song ${preSong.path} already in graph at ${songVertices[uid]?.preSong?.path}")
            return
        }

        val songGenreVertices =
            preSong.preGenres.map { preGenre ->
                genreVertices.getOrPut(preGenre) { GenreVertex(preGenre) }
            }

        val songArtistVertices =
            preSong.preArtists.map { preArtist ->
                artistVertices.getOrPut(preArtist) { ArtistVertex(preArtist) }
            }

        val albumVertex =
            albumVertices.getOrPut(preSong.preAlbum) {
                // Albums themselves have their own parent artists that also need to be
                // linked up.
                val albumArtistVertices =
                    preSong.preAlbum.preArtists.map { preArtist ->
                        artistVertices.getOrPut(preArtist) { ArtistVertex(preArtist) }
                    }
                val albumVertex = AlbumVertex(preSong.preAlbum, albumArtistVertices.toMutableList())
                // Album vertex is linked, now link artists back to album.
                albumArtistVertices.forEach { artistVertex ->
                    artistVertex.albumVertices.add(albumVertex)
                }
                albumVertex
            }

        val songVertex =
            SongVertex(
                preSong,
                albumVertex,
                songArtistVertices.toMutableList(),
                songGenreVertices.toMutableList())
        albumVertex.songVertices.add(songVertex)

        songArtistVertices.forEach { artistVertex ->
            artistVertex.songVertices.add(songVertex)
            songGenreVertices.forEach { genreVertex ->
                // Mutually link any new genres to the artist
                artistVertex.genreVertices.add(genreVertex)
                genreVertex.artistVertices.add(artistVertex)
            }
        }

        songGenreVertices.forEach { genreVertex -> genreVertex.songVertices.add(songVertex) }

        songVertices[uid] = songVertex
    }

    override fun build(): MusicGraph {
        val genreClusters = genreVertices.values.groupBy { it.preGenre.rawName?.lowercase() }
        for (cluster in genreClusters.values) {
            simplifyGenreCluster(cluster)
        }

        val artistClusters = artistVertices.values.groupBy { it.preArtist.rawName?.lowercase() }
        for (cluster in artistClusters.values) {
            simplifyArtistCluster(cluster)
        }

        val albumClusters = albumVertices.values.groupBy { it.preAlbum.rawName.lowercase() }
        for (cluster in albumClusters.values) {
            simplifyAlbumCluster(cluster)
        }

        // Remove any edges that wound up connecting to the same artist or genre
        // in the end after simplification.
        albumVertices.values.forEach {
            it.artistVertices = it.artistVertices.distinct().toMutableList()
        }

        songVertices.values.forEach {
            it.artistVertices = it.artistVertices.distinct().toMutableList()
            it.genreVertices = it.genreVertices.distinct().toMutableList()
        }

        return MusicGraph(
            songVertices.values.toList(),
            albumVertices.values.toList(),
            artistVertices.values.toList(),
            genreVertices.values.toList())
    }

    private fun simplifyGenreCluster(cluster: Collection<GenreVertex>) {
        // All of these genres are semantically equivalent. Pick the most popular variation
        // and merge all the others into it.
        val clusterSet = cluster.toMutableSet()
        val dst = clusterSet.maxBy { it.songVertices.size }
        clusterSet.remove(dst)
        for (src in clusterSet) {
            meldGenreVertices(src, dst)
        }
    }

    private fun meldGenreVertices(src: GenreVertex, dst: GenreVertex) {
        // Link all songs and artists from the irrelevant genre to the relevant genre.
        dst.songVertices.addAll(src.songVertices)
        dst.artistVertices.addAll(src.artistVertices)
        // Update all songs and artists to point to the relevant genre.
        src.songVertices.forEach {
            val index = it.genreVertices.indexOf(src)
            check(index >= 0) { "Illegal state: directed edge between genre and song" }
            it.genreVertices[index] = dst
        }
        src.artistVertices.forEach {
            it.genreVertices.remove(src)
            it.genreVertices.add(dst)
        }
        // Remove the irrelevant genre from the graph.
        genreVertices.remove(src.preGenre)
    }

    private fun simplifyArtistCluster(cluster: Collection<ArtistVertex>) {
        val fullMusicBrainzIdCoverage = cluster.all { it.preArtist.musicBrainzId != null }
        if (fullMusicBrainzIdCoverage) {
            // All artists have MBIDs, nothing needs to be merged.
            val mbidClusters = cluster.groupBy { unlikelyToBeNull(it.preArtist.musicBrainzId) }
            for (mbidCluster in mbidClusters.values) {
                simplifyArtistClusterImpl(mbidCluster)
            }
            return
        }
        // No full MBID coverage, discard the MBIDs from the graph.
        val strippedCluster =
            cluster.map {
                val noMbidPreArtist = it.preArtist.copy(musicBrainzId = null)
                val simpleMbidVertex =
                    artistVertices.getOrPut(noMbidPreArtist) { ArtistVertex(noMbidPreArtist) }
                meldArtistVertices(it, simpleMbidVertex)
                simpleMbidVertex
            }
        simplifyArtistClusterImpl(strippedCluster)
    }

    private fun simplifyArtistClusterImpl(cluster: Collection<ArtistVertex>) {
        if (cluster.size == 1) {
            // One canonical artist, nothing to collapse
            return
        }
        val clusterSet = cluster.toMutableSet()
        val relevantArtistVertex = clusterSet.maxBy { it.songVertices.size }
        clusterSet.remove(relevantArtistVertex)
        for (irrelevantArtistVertex in clusterSet) {
            meldArtistVertices(irrelevantArtistVertex, relevantArtistVertex)
        }
    }

    private fun meldArtistVertices(src: ArtistVertex, dst: ArtistVertex) {
        // Link all songs and albums from the irrelevant artist to the relevant artist.
        dst.songVertices.addAll(src.songVertices)
        dst.albumVertices.addAll(src.albumVertices)
        // Update all songs, albums, and genres to point to the relevant artist.
        src.songVertices.forEach {
            val index = it.artistVertices.indexOf(src)
            check(index >= 0) { "Illegal state: directed edge between artist and song" }
            it.artistVertices[index] = dst
        }
        src.albumVertices.forEach {
            val index = it.artistVertices.indexOf(src)
            check(index >= 0) { "Illegal state: directed edge between artist and album" }
            it.artistVertices[index] = dst
        }
        src.genreVertices.forEach {
            it.artistVertices.remove(src)
            it.artistVertices.add(dst)
        }

        // Remove the irrelevant artist from the graph.
        artistVertices.remove(src.preArtist)
    }

    private fun simplifyAlbumCluster(cluster: Collection<AlbumVertex>) {
        val fullMusicBrainzIdCoverage = cluster.all { it.preAlbum.musicBrainzId != null }
        if (fullMusicBrainzIdCoverage) {
            // All albums have MBIDs, nothing needs to be merged.
            val mbidClusters = cluster.groupBy { unlikelyToBeNull(it.preAlbum.musicBrainzId) }
            for (mbidCluster in mbidClusters.values) {
                simplifyAlbumClusterImpl(mbidCluster)
            }
            return
        }
        // No full MBID coverage, discard the MBIDs from the graph.
        val strippedCluster =
            cluster.map {
                val noMbidPreAlbum = it.preAlbum.copy(musicBrainzId = null)
                val simpleMbidVertex =
                    albumVertices.getOrPut(noMbidPreAlbum) {
                        AlbumVertex(noMbidPreAlbum, it.artistVertices.toMutableList())
                    }
                meldAlbumVertices(it, simpleMbidVertex)
                simpleMbidVertex
            }
        simplifyAlbumClusterImpl(strippedCluster)
    }

    private fun simplifyAlbumClusterImpl(cluster: Collection<AlbumVertex>) {
        // All of these albums are semantically equivalent. Pick the most popular variation
        // and merge all the others into it.
        val clusterSet = cluster.toMutableSet()
        val dst = clusterSet.maxBy { it.songVertices.size }
        clusterSet.remove(dst)
        for (src in clusterSet) {
            meldAlbumVertices(src, dst)
        }
    }

    private fun meldAlbumVertices(src: AlbumVertex, dst: AlbumVertex) {
        // Link all songs and artists from the irrelevant album to the relevant album.
        dst.songVertices.addAll(src.songVertices)
        dst.artistVertices.addAll(src.artistVertices)
        // Update all songs and artists to point to the relevant album.
        src.songVertices.forEach { it.albumVertex = dst }
        src.artistVertices.forEach {
            it.albumVertices.remove(src)
            it.albumVertices.add(dst)
        }
        // Remove the irrelevant album from the graph.
        albumVertices.remove(src.preAlbum)
    }
}

class SongVertex(
    val preSong: PreSong,
    var albumVertex: AlbumVertex,
    var artistVertices: MutableList<ArtistVertex>,
    var genreVertices: MutableList<GenreVertex>
) {
    var tag: Any? = null
}

class AlbumVertex(val preAlbum: PreAlbum, var artistVertices: MutableList<ArtistVertex>) {
    val songVertices = mutableSetOf<SongVertex>()
    var tag: Any? = null
}

class ArtistVertex(
    val preArtist: PreArtist,
) {
    val songVertices = mutableSetOf<SongVertex>()
    val albumVertices = mutableSetOf<AlbumVertex>()
    val genreVertices = mutableSetOf<GenreVertex>()
    var tag: Any? = null
}

class GenreVertex(val preGenre: PreGenre) {
    val songVertices = mutableSetOf<SongVertex>()
    val artistVertices = mutableSetOf<ArtistVertex>()
    var tag: Any? = null
}
