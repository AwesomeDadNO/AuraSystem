package no.aura.os.v2.overlay

enum class PlayerVisibility { HIDDEN, COLLAPSED, EXPANDED }

sealed interface PlayerAction {
    object Show : PlayerAction; object Expand : PlayerAction; object Collapse : PlayerAction; object Dismiss : PlayerAction
    object TogglePlay : PlayerAction; object Next : PlayerAction; object Previous : PlayerAction
}

/** State of the compact AuraAmp player. Playback position is deliberately NOT part of this state (see [no.aura.os.v2.overlay.DemoClock]). */
data class MiniPlayerState(val visibility: PlayerVisibility = PlayerVisibility.EXPANDED, val playing: Boolean = true, val trackIndex: Int = 0)

object MiniPlayerReducer {
    fun reduce(s: MiniPlayerState, a: PlayerAction, trackCount: Int): MiniPlayerState = when (a) {
        PlayerAction.Show -> if (trackCount > 0) s.copy(visibility = PlayerVisibility.EXPANDED) else s
        PlayerAction.Expand -> if (s.visibility == PlayerVisibility.COLLAPSED) s.copy(visibility = PlayerVisibility.EXPANDED) else s
        PlayerAction.Collapse -> if (s.visibility == PlayerVisibility.EXPANDED) s.copy(visibility = PlayerVisibility.COLLAPSED) else s
        PlayerAction.Dismiss -> s.copy(visibility = PlayerVisibility.HIDDEN, playing = false)
        PlayerAction.TogglePlay -> if (s.visibility == PlayerVisibility.HIDDEN) s else s.copy(playing = !s.playing)
        PlayerAction.Next -> if (trackCount == 0) s else s.copy(trackIndex = (s.trackIndex + 1) % trackCount)
        PlayerAction.Previous -> if (trackCount == 0) s else s.copy(trackIndex = (s.trackIndex - 1 + trackCount) % trackCount)
    }
}

/** Layering contract: base content, focus interactions, compact player, dialogs, system-style overlays. [AuraShell] composes them in this order. */
enum class AuraLayer { BASE_CONTENT, INTERACTIONS, COMPACT_PLAYER, DIALOGS, SYSTEM_OVERLAYS }
