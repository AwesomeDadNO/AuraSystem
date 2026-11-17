package no.aura.os.v2.search

import no.aura.os.v2.settings.*

/** Search configuration labels, never profile secrets, values or credentials. */
class SettingsSearchSource : SearchSource {
    override suspend fun search(request:SearchRequest):SearchBatch {
        val words=request.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if(words.isEmpty()) return SearchBatch(emptyList())
        val aliases=mapOf(
            "general.android_tv" to "android tv device system enhet nettverk lyd bilde innstillinger settings",
            "general.set_default" to "home launcher hjem standard",
            "general.notifications" to "varsler notifikasjoner notifications",
            "general.accessibility" to "tilgjengelighet accessibility",
            SettingsCatalog.PB_AUDIO to "audio sound lyd språk language",
            SettingsCatalog.PB_SUBLANG to "undertekst subtitle språk language",
            "simkl.auto_sync" to "simkl sync synkronisering hourly autosync automatisk",
        )
        val hits=SettingsCatalog.all.filter { item ->
            val text="${item.title} ${item.category.title} ${aliases[item.id].orEmpty()}".lowercase()
            words.all { it in text }
        }.map { item -> SearchHit("settings:${item.id}",item.title,"AuraOS settings",SearchKind.SETTINGS,"settings:${item.id}",
            subtitle=item.category.title+if(item.availability==Availability.COMING_LATER) " · Coming later" else "") }
        return SearchBatch(hits)
    }
}
