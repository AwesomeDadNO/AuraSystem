package no.aura.os.v2.amp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Profile-local album likes only. No account credentials, provider tokens or implicit Connect sync. */
class AmpAlbumLikes(context:Context,profile:String) {
    private val prefs=context.applicationContext.getSharedPreferences("amp_album_likes_"+ampProfileKey(profile),Context.MODE_PRIVATE)
    fun albums():List<AmpAlbum> = runCatching {
        val rows=JSONArray(prefs.getString("albums","[]"))
        (0 until rows.length().coerceAtMost(2000)).mapNotNull {i->rows.optJSONObject(i)?.let {o->
            val id=o.optString("id").takeIf{it.isNotBlank() && it.length<=200} ?: return@let null
            AmpAlbum(id,o.optString("title").take(300),o.optString("artist").take(300),o.optString("cover").takeIf(AmpProviderSafety::publicHttps),o.optString("date").takeIf{it.isNotBlank()})
        }}
    }.getOrDefault(emptyList())
    fun ids():Set<String> = albums().map{it.id}.toSet()
    fun toggle(album:AmpAlbum):List<AmpAlbum> {
        require(album.id.isNotBlank() && album.id.length<=200)
        val old=albums()
        val next=if(old.any{it.id==album.id})old.filterNot{it.id==album.id} else {require(old.size<2000);old+album}
        val rows=JSONArray(next.map{a->JSONObject().put("id",a.id).put("title",a.title.take(300)).put("artist",a.artist.take(300)).put("cover",a.cover?.takeIf(AmpProviderSafety::publicHttps)).put("date",a.releaseDate?.take(20))})
        prefs.edit().putString("albums",rows.toString()).apply()
        return next
    }
}
object AmpPlaylistAppend {
    fun merge(existing:List<AmpTrack>,album:List<AmpTrack>):List<AmpTrack> {
        val result=(existing+album).distinctBy{it.id}
        require(result.size<=2000){"Playlist exceeds the 2,000-song limit"}
        return result
    }
}

/** Followed artist metadata is local to the active profile; no implicit provider or Connect writes. */
class AmpArtistFollows(context:Context,profile:String) {
    private val prefs=context.applicationContext.getSharedPreferences("amp_artist_follows_"+ampProfileKey(profile),Context.MODE_PRIVATE)
    fun artists():List<AmpArtist> = runCatching {
        val rows=JSONArray(prefs.getString("artists","[]"))
        (0 until rows.length().coerceAtMost(2000)).mapNotNull{i->rows.optJSONObject(i)?.let{o->
            val id=o.optString("id").takeIf{it.isNotBlank() && it.length<=200} ?: return@let null
            AmpArtist(id,o.optString("name").take(300),o.optString("picture").takeIf(AmpProviderSafety::publicHttps))
        }}
    }.getOrDefault(emptyList())
    fun toggle(artist:AmpArtist):List<AmpArtist> {
        require(artist.id.isNotBlank() && artist.id.length<=200)
        val old=artists()
        val next=if(old.any{it.id==artist.id})old.filterNot{it.id==artist.id}else{require(old.size<2000);old+artist}
        prefs.edit().putString("artists",JSONArray(next.map{JSONObject().put("id",it.id).put("name",it.name.take(300)).put("picture",it.picture?.takeIf(AmpProviderSafety::publicHttps))}).toString()).apply()
        return next
    }
}
