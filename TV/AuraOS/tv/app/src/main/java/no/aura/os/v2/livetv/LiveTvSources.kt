package no.aura.os.v2.livetv

import android.content.Context
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Guide configuration is independent of installed-app versus user-playlist mode. */
enum class LiveTvSourceMode { IPTV_PLAYLIST, INSTALLED_TV_APPS }
data class UserPlaylist(val id: String, val name: String, val url: String)
data class LiveTvConfiguration(
    val mode: LiveTvSourceMode, val country: String, val epgFeedConfigured: Boolean, val playlistConfigured: Boolean,
    val region: String = "", val timezone: String = java.time.ZoneId.systemDefault().id,
)

/** All URLs (including query credentials), source names and guide mappings are encrypted per profile. */
class LiveTvPreferences(context: Context, profileId: String = "default") {
    private val store = LiveTvConfigStore(context,profileId)
    private fun data(): JSONObject = runCatching { JSONObject(store.read() ?: "{}") }.getOrElse { JSONObject() }
    private fun edit(block: (JSONObject)->Unit): Boolean = runCatching {
        if(store.has() && store.read()==null)return false
        val d=data();block(d);store.save(d.toString()) }.getOrDefault(false)
    fun storageAvailable(): Boolean = !store.has() || store.read()!=null
    fun mode(): LiveTvSourceMode = runCatching { LiveTvSourceMode.valueOf(data().optString("mode")) }.getOrDefault(LiveTvSourceMode.INSTALLED_TV_APPS)
    fun setMode(mode: LiveTvSourceMode) = edit { it.put("mode",mode.name) }
    fun country(): String = data().optString("country",Locale.getDefault().country.ifBlank { "NO" })
    fun setCountry(code: String): Boolean = code.matches(Regex("[A-Z]{2}")) && edit { it.put("country",code) }
    fun region(): String = data().optString("region","")
    fun setRegion(value: String): Boolean = value.length<=100 && edit { it.put("region",value) }
    fun timezone(): String = data().optString("timezone",java.time.ZoneId.systemDefault().id)
    fun setTimezone(value: String): Boolean = runCatching { java.time.ZoneId.of(value); edit { it.put("timezone",value) } }.getOrDefault(false)
    fun playlists(): List<UserPlaylist> {
        val list=data().optJSONArray("playlists") ?: return emptyList()
        return (0 until list.length().coerceAtMost(20)).mapNotNull { n -> list.optJSONObject(n)?.let {
            val id=it.optString("id");val name=it.optString("name");val url=it.optString("url")
            if(id.isBlank() || !PublicHttpsPolicy.syntax(url)) null else UserPlaylist(id,name,url)
        } }
    }
    fun addPlaylist(name: String,url: String): Boolean {
        if(name.isBlank() || name.length>100 || !PublicHttpsPolicy.syntax(url))return false
        val existing=playlists();if(existing.size>=20)return false
        val next=existing+UserPlaylist(java.util.UUID.randomUUID().toString(),name,url)
        return edit { it.put("playlists",playlistJson(next)) }
    }
    fun removePlaylist(id: String): Boolean = edit { it.put("playlists",playlistJson(playlists().filterNot { p -> p.id==id })) }
    private fun playlistJson(values: List<UserPlaylist>) = JSONArray().apply { values.forEach { p -> put(JSONObject().put("id",p.id).put("name",p.name).put("url",p.url)) } }
    fun setPlaylistUrl(url: String): Boolean {
        if(!PublicHttpsPolicy.syntax(url))return false
        return edit { it.put("playlists",playlistJson(listOf(UserPlaylist("primary","My playlist",url)))) }
    }
    fun playlistUrl(): String? = playlists().firstOrNull()?.url
    fun clearPlaylist() = edit { it.remove("playlists") }
    fun setEpgUrl(url: String): Boolean = PublicHttpsPolicy.syntax(url) && edit { it.put("epg",url) }
    fun epgUrl(): String? = data().optString("epg").takeIf { PublicHttpsPolicy.syntax(it) }
    fun clearEpg() = edit { it.remove("epg") }
    fun mappings(): Map<String,String> {
        val obj=data().optJSONObject("mappings") ?:return emptyMap()
        return obj.keys().asSequence().take(3000).associateWith { obj.optString(it) }
    }
    fun setMapping(channelKey: String,epgId: String?): Boolean {
        if(channelKey.length>300 || (epgId?.length ?:0)>200)return false
        return edit { d -> val mappings=d.optJSONObject("mappings") ?: JSONObject()
            if(epgId.isNullOrBlank())mappings.remove(channelKey) else mappings.put(channelKey,epgId)
            d.put("mappings",mappings) }
    }
    fun current() = LiveTvConfiguration(mode(),country(),epgUrl()!=null,playlists().isNotEmpty(),region(),timezone())
    companion object {
        /** Legacy nonsecret URL check retained for older callers; encrypted source APIs permit query credentials. */
        fun safePublicHttps(raw: String): Boolean = PublicHttpsPolicy.syntax(raw) && runCatching { java.net.URI(raw).rawQuery==null }.getOrDefault(false)
    }
}

/** Existing constructor preserved. Stream URL is private input; never use it as visible text or diagnostic identity. */
data class PlaylistChannel(val name: String,val url: String,val epgId: String?,val logo: String?=null,val group: String?=null,val playlistId: String="") {
    val key: String get() = playlistId+":"+java.security.MessageDigest.getInstance("SHA-256").digest((epgId.orEmpty()+"\n"+name+"\n"+url).toByteArray()).joinToString("") { "%02x".format(it) }
}
object M3uPlaylist {
    const val MAX_BYTES=2*1024*1024
    fun parse(text: String,maxChannels: Int=3000): List<PlaylistChannel> {
        if(text.length>MAX_BYTES || !text.trimStart('\uFEFF',' ','\n','\r','\t').startsWith("#EXTM3U"))return emptyList()
        val out=ArrayList<PlaylistChannel>();var metadata: String?=null
        fun attr(info: String,name: String): String? = Regex("(?:^|\\s)"+Regex.escape(name)+"=\"([^\"]{1,500})\"").find(info)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        for(line in text.lineSequence().take(20000)) {
            val value=line.trim();if(value.length>8192){metadata=null;continue}
            if(value.startsWith("#EXTINF:"))metadata=value
            else if(value.isNotBlank() && !value.startsWith('#')) {
                val info=metadata;metadata=null
                if(info!=null && PublicHttpsPolicy.syntax(value)) {
                    // First comma outside quoted attributes separates the displayed title.
                    var quoted=false;val comma=info.indexOfFirst { ch -> if(ch=='"')quoted=!quoted;ch==',' && !quoted }
                    val title=if(comma>=0)info.substring(comma+1).trim().take(140) else ""
                    if(title.isNotBlank())out+=PlaylistChannel(title,value,attr(info,"tvg-id")?.take(200),
                        attr(info,"tvg-logo")?.takeIf(PublicHttpsPolicy::syntax),attr(info,"group-title")?.take(140))
                    if(out.size>=maxChannels.coerceIn(1,3000))break
                }
            }
        }
        return out.distinctBy { it.key }
    }
}
