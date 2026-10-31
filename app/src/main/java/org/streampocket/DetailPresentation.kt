// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

/** Resume only an exact source, title identity and still available episode URL. */
internal fun detailResume(detail:Detail,history:List<History>):History? {
    val episodes=detail.lines.flatMap { it.episodes }.associateBy { it.url }
    return history.filter { it.playback.video.source.key==detail.video.source.key &&
        it.playback.video.id==detail.video.id && it.playback.episode.url in episodes }
        .maxByOrNull { it.updated }?.let { entry ->
            entry.copy(playback=entry.playback.copy(episode=episodes.getValue(entry.playback.episode.url)))
        }
}
