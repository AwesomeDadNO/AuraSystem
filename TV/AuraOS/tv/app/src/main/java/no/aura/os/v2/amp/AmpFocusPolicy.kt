package no.aura.os.v2.amp

/** A loading/empty detail must never route into an unmounted parent catalogue. */
enum class AmpFocusDestination { PLAYER, HERO, BODY, DETAILS }
object AmpFocusPolicy {
    fun down(nowPlaying:Boolean,detailsOpen:Boolean,detailsHaveContent:Boolean,heroAvailable:Boolean,catalogueHasContent:Boolean):AmpFocusDestination = when {
        nowPlaying -> AmpFocusDestination.PLAYER
        detailsOpen -> if(detailsHaveContent)AmpFocusDestination.BODY else AmpFocusDestination.DETAILS
        heroAvailable -> AmpFocusDestination.HERO
        catalogueHasContent -> AmpFocusDestination.BODY
        else -> AmpFocusDestination.DETAILS
    }
}
