package no.aura.os.v2.connect

import no.aura.cortex.AuraCortex
import no.aura.cortex.CallError
import no.aura.cortex.CommandResult
import java.util.concurrent.Executors

// Skall-adapter: TV-appens eksisterende kommando-handlere kjører over AuraCortex (coretex.kt).
// All tilkobling, paring og sikkerhet ligger i coretex.kt; her er bare koblingen til TV-ens funksjoner.

typealias CommandContext = no.aura.cortex.CommandContext
typealias Permission = no.aura.cortex.Permission

val CommandContext.deviceId: String get() = device.deviceId

enum class ErrorCode(val error: CallError) {
    UNKNOWN_OPERATION(CallError.UNKNOWN_OPERATION), NOT_AUTHORIZED(CallError.NOT_ALLOWED),
    BAD_REQUEST(CallError.BAD_REQUEST), HANDLER_FAILED(CallError.FAILED),
}

class ProtocolException(val code: ErrorCode, message: String) : Exception(message)

fun interface CommandHandler { fun handle(context: CommandContext): Map<String, Any?> }

object TvCommands {
    /** Tillatelsen hver operasjon krever (samme tabell som AuraConnect v3). Ukjente operasjoner registreres ikke. */
    val PERMISSIONS: Map<String, Permission> = mapOf(
        "playback.status" to Permission.PLAYBACK_STATUS,
        "playback.play" to Permission.PLAYBACK_CONTROL, "playback.pause" to Permission.PLAYBACK_CONTROL,
        "playback.next" to Permission.PLAYBACK_CONTROL, "playback.previous" to Permission.PLAYBACK_CONTROL,
        "playback.seek" to Permission.PLAYBACK_CONTROL, "playback.shuffle" to Permission.PLAYBACK_CONTROL, "playback.repeat" to Permission.PLAYBACK_CONTROL,
        "queue.add" to Permission.QUEUE_EDIT, "queue.remove" to Permission.QUEUE_EDIT, "queue.move" to Permission.QUEUE_EDIT, "queue.clear" to Permission.QUEUE_EDIT,
        "sync.playlists" to Permission.PLAYLIST_SYNC, "playlists.push" to Permission.PLAYLIST_SYNC, "sync.watchlist" to Permission.WATCHLIST_SYNC,
        "media.offer" to Permission.MEDIA_SEND, "credential.offer" to Permission.CREDENTIAL_RECEIVE,
    )

    // Handlerne hopper selv til hovedtråden (runBlocking(Dispatchers.Main)), så de må kjøre på en bakgrunnstråd.
    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "aura-tv-command").apply { isDaemon = true } }

    fun register(handlers: Map<String, (CommandContext) -> Map<String, Any?>>) {
        handlers.forEach { (op, handler) ->
            val permission = PERMISSIONS[op] ?: return@forEach
            AuraCortex.connect.registerCommand(op, permission) { ctx, respond ->
                worker.execute {
                    respond(
                        try { CommandResult.ok(handler(ctx)) }
                        catch (e: ProtocolException) { CommandResult.fail(e.code.error, e.message) }
                        catch (e: Exception) { CommandResult.fail(CallError.FAILED, "The TV could not complete this") },
                    )
                }
            }
        }
    }

    fun unregister(ops: Collection<String>) = ops.forEach { AuraCortex.connect.unregisterCommand(it) }
}
