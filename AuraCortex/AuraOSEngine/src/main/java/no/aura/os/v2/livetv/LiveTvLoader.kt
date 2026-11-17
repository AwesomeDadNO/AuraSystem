package no.aura.os.v2.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class LiveTvLoadState(val channels: List<PlaylistChannel> = emptyList(),val guide: GuideData?=null,val error: String?=null,val warnings: List<String> = emptyList(),val loading: Boolean=false)
class LiveTvLoader(private val fetch: suspend (String, Int) -> String = { url, max -> LiveTvHttp.read(url,max) }) {
    suspend fun load(playlists: List<UserPlaylist>,epgUrl: String?): LiveTvLoadState = withContext(Dispatchers.IO) {
        val warnings=ArrayList<String>();val channels=ArrayList<PlaylistChannel>()
        playlists.take(20).forEach { source ->
            coroutineContext.ensureActive()
            try {
                val parsed=M3uPlaylist.parse(fetch(source.url,M3uPlaylist.MAX_BYTES))
                if(parsed.isEmpty())warnings+="A playlist returned no supported HTTPS channels."
                channels+=parsed.map { it.copy(playlistId=source.id) }
            } catch(cancel:CancellationException){throw cancel} catch(_:Exception){warnings+="A playlist could not be loaded. Check its HTTPS address and access rights."}
        }
        coroutineContext.ensureActive()
        val guide=if(epgUrl!=null)try {
            XmlTvGuide.parse(fetch(epgUrl,XmlTvGuide.MAX_BYTES)).also { if(it==null)warnings+="The XMLTV guide is invalid or exceeds supported limits." }
        } catch(cancel:CancellationException){throw cancel} catch(_:Exception){warnings+="The XMLTV guide could not be loaded.";null} else null
        coroutineContext.ensureActive()
        LiveTvLoadState(channels.take(10000),guide,warnings=warnings.distinct())
    }
}
