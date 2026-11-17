package no.aura.os.v2.overlay

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.settings.Sheet
import no.aura.os.v2.settings.SheetOption

/** Library navigation uses SheetHost's CanvasDialog, design tokens and DPAD focus handling. */
@Composable
fun MediaServerLibraryHost(service: MediaServerService, profileId: String, account: ServerAccount, onPlay: (ServerPlayback) -> Unit, onClose: () -> Unit, initialItem: MediaEntry? = null) {
    val api = service.libraryPlayback
    val scope = rememberCoroutineScope()
    var sheet by remember(profileId, account) { mutableStateOf<Sheet>(Sheet.Message(account.serverName, listOf("Loading libraries…"))) }
    var pageIndex by remember(profileId, account) { mutableIntStateOf(0) }
    var library by remember(profileId, account) { mutableStateOf<MediaLibrary?>(null) }
    var series by remember(profileId, account) { mutableStateOf<MediaEntry?>(null) }
    fun error(e: MediaError) { sheet = Sheet.Message(account.serverName, listOf(e.message())) }
    fun choose(entry: MediaEntry) {
        if (entry.type == EntryType.SERIES) { series = entry; pageIndex = 0 }
        else if (entry.type == EntryType.MOVIE || entry.type == EntryType.EPISODE) {
            fun play(resume: Boolean) { scope.launch {
                sheet = Sheet.Message(entry.title, listOf("Preparing authenticated playback…"))
                when (val r = api.playback(profileId, account, if (resume) entry else entry.copy(resumeMs = null))) {
                    is MediaResult.Failure -> error(r.error)
                    is MediaResult.Ok -> onPlay(r.value)
                }
            } }
            sheet = Sheet.Choice(entry.title, buildList {
                if ((entry.resumeMs ?: 0) > 0) add(SheetOption("Resume") { play(true) })
                add(SheetOption("Play from beginning") { play(false) })
            }, note = "Plays formats supported by this TV. Transcoding is not available.")
        }
    }
    LaunchedEffect(initialItem) { initialItem?.let { choose(it) } }
    LaunchedEffect(profileId, account, library, series, pageIndex) {
        if (initialItem != null && library == null && series == null) return@LaunchedEffect
        val selected = library
        val show = series
        if (selected == null && show == null) {
            when (val r = api.libraries(profileId, account)) {
                is MediaResult.Failure -> error(r.error)
                is MediaResult.Ok -> {
                    val resume = (api.resume(profileId, account) as? MediaResult.Ok)?.value.orEmpty()
                    sheet = Sheet.Choice(account.serverName, buildList {
                        resume.forEach { e -> add(SheetOption("Continue · ${e.seriesTitle?.let { "$it · " }.orEmpty()}${e.title}") { choose(e) }) }
                        r.value.forEach { l -> add(SheetOption(l.name) { library = l; series = null; pageIndex = 0 }) }
                    }, note = if (r.value.isEmpty()) "No libraries are available to this server user." else "${account.kind.title} · ${account.userName}")
                }
            }
        } else {
            val r = if (show != null) api.episodes(profileId, account, show, pageIndex * 60) else api.items(profileId, account, selected!!, pageIndex * 60)
            when (r) {
                is MediaResult.Failure -> error(r.error)
                is MediaResult.Ok -> sheet = Sheet.Choice(show?.title ?: selected!!.name, buildList {
                    r.value.items.forEach { e -> add(SheetOption(e.title + if (e.type == EntryType.SERIES) " · Episodes" else "") { choose(e) }) }
                    if (pageIndex > 0) add(SheetOption("Previous page") { pageIndex-- })
                    if ((pageIndex + 1) * 60 < r.value.total) add(SheetOption("Next page") { pageIndex++ })
                    add(SheetOption("Libraries") { library = null; series = null; pageIndex = 0 })
                }, note = if (r.value.items.isEmpty()) "No playable items found." else "Page ${pageIndex + 1}")
            }
        }
    }
    SheetHost(sheet, onClose)
}
