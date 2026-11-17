package no.aura.os.v2.connect

import no.aura.cortex.AuraCortex
import no.aura.cortex.AuraConnectContract
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
    /** Tillatelsen hver operasjon krever (samme tabell som AuraConnect v3). Ukjente operasjoner registreres ikke.
     * Merk: Nye apper som f.eks. AuraBox kan registrere egne operasjoner med eget prefiks (f.eks. "box.*")
     * og en tillatelse direkte her i TvCommands.PERMISSIONS.
     */
    val PERMISSIONS: Map<String, Permission> = AuraConnectContract.requiredPermissions + mapOf(
        "sync.watchlist" to Permission.WATCHLIST_SYNC,
        "media.offer" to Permission.MEDIA_SEND,
        "box.status" to Permission.PLAYBACK_CONTROL, "box.action" to Permission.PLAYBACK_CONTROL,
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
