/*
 * Copyright (c) 2024 Auxio Project
 * CoverExtractor.kt is part of Auxio.
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
 
package org.oxycblt.auxio.image.stack.extractor

import android.net.Uri
import javax.inject.Inject
import org.oxycblt.auxio.musikr.cover.Cover

interface CoverExtractor {
    suspend fun extract(cover: Cover.Single): ByteArray?
}

data class CoverSources(val sources: List<CoverSource>)

interface CoverSource {
    suspend fun extract(fileUri: Uri): ByteArray?
}

class CoverExtractorImpl @Inject constructor(private val coverSources: CoverSources) :
    CoverExtractor {
    override suspend fun extract(cover: Cover.Single): ByteArray? {
        return null
        for (coverSource in coverSources.sources) {
            val stream = coverSource.extract(cover.uri)
            if (stream != null) {
                return stream
            }
        }
        return null
    }
}
