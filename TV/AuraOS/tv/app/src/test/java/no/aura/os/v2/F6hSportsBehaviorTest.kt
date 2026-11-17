package no.aura.os.v2
import no.aura.os.v2.sports.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
class F6hSportsBehaviorTest {
 @Test fun parsesRealScoresAndRejectsMalformedEnvelope() {
  val parsed = SportsLogic.parse("""{"matches":[{"slug":"a","home":"A","away":"B","home_score":0,"away_score":null,"status":"finished","time":"2026-11-12T18:00:00Z"}]}""")!!
  assertEquals("0", parsed.single().homeScore); assertNull(parsed.single().awayScore)
  assertNull(SportsLogic.parse("{}")); assertNull(SportsLogic.parse("{broken"))
 }
 @Test fun rejectsMissingIdentityAndDeduplicatesProviderIds() {
  assertTrue(SportsLogic.parse("""{"matches":[{"home":"A","away":"B"}]}""")!!.isEmpty())
  assertEquals(1, SportsLogic.parse("""{"matches":[{"slug":"a","home":"A","away":"B"},{"slug":"a","home":"A","away":"B"}]}""")!!.size)
 }
 @Test fun localTimeRequiresKnownOffset() {
  assertTrue(SportsLogic.localTime("2026-11-12T18:00:00Z", ZoneId.of("Europe/Oslo")).contains("19:00"))
  assertEquals("Time unavailable", SportsLogic.localTime("18:00", ZoneId.of("UTC")))
 }
 @Test fun noPollingWhenHiddenDisabledOrWithinCacheWindow() {
  assertFalse(SportsLogic.shouldFetch(false,true,100000,null))
  assertFalse(SportsLogic.shouldFetch(true,false,100000,null))
  assertFalse(SportsLogic.shouldFetch(true,true,100000,50000))
  assertTrue(SportsLogic.shouldFetch(true,true,110000,50000))
 }
 @Test fun preferencesAreProfileScoped() {
  assertNotEquals(SportsLogic.preferenceKey("profile-a"), SportsLogic.preferenceKey("profile-b"))
  assertEquals(SportsLogic.preferenceKey("profile-a"), SportsLogic.preferenceKey("profile-a"))
 }
 @Test fun favoritesAreSportSpecificAndOptionalLeaguesAreHonest() {
  assertNotEquals(SportsLogic.teamKey(Sport.FOOTBALL,"United"),SportsLogic.teamKey(Sport.BASKETBALL,"United"))
  assertTrue(SportsLogic.parse("""{"matches":[{"slug":"a","home":"A","away":"B"}]}""")!!.all { it.league == null })
 }
}
