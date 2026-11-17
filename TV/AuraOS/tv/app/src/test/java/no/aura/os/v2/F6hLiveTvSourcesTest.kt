package no.aura.os.v2
import no.aura.os.v2.livetv.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
class F6hLiveTvSourcesTest {
 @Test fun rejectsPrivateAndAmbiguousOriginsButAllowsEncryptedQueryTokens() {
  assertTrue(PublicHttpsPolicy.syntax("https://media.example/live?token=secret"))
  listOf("http://media.example/live","https://user:pass@media.example/a","https://127.1/a","https://2130706433/a","https://[::1]/a","https://host.local/a").forEach { assertFalse(it,PublicHttpsPolicy.syntax(it)) }
  assertFalse(PublicHttpsPolicy.publicAddress(InetAddress.getByName("192.168.0.1")))
  assertFalse(PublicHttpsPolicy.publicAddress(InetAddress.getByName("169.254.169.254")))
 }
 @Test fun playlistMetadataIsBoundedAndNoInlineCredentials() {
  val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"bbc\" tvg-logo=\"https://img.example/a\" group-title=\"News\",BBC\nhttps://tv.example/a?token=t\n#EXTINF:-1,Bad\nhttps://u:p@tv.example/x"
  val item=M3uPlaylist.parse(text).single()
  assertEquals("bbc",item.epgId); assertEquals("News",item.group);assertEquals("BBC",item.name)
 }
 @Test fun xmltvUsesOffsetsAndRejectsExternalEntities() {
  val data=XmlTvGuide.parse("""<tv><channel id="bbc"><display-name>BBC</display-name></channel><programme channel="bbc" start="20261112180000 +0100" stop="20261112190000 +0100"><title>News</title></programme></tv>""")!!
  assertEquals("2026-11-12T17:00:00Z",data.programmes.single().start.toString())
  assertNull(XmlTvGuide.parse("""<!DOCTYPE tv [<!ENTITY x SYSTEM "file:///etc/passwd">]><tv>&x;</tv>"""))
 }
 @Test fun androidFactoryWithoutApacheFeatureStillParsesGuide() {
  val delegate=javax.xml.parsers.SAXParserFactory.newInstance()
  val androidLike=object:javax.xml.parsers.SAXParserFactory() {
   override fun newSAXParser()=delegate.newSAXParser()
   override fun setFeature(name:String,value:Boolean) {
    if(name.startsWith("http://apache.org/"))throw org.xml.sax.SAXNotRecognizedException(name)
    delegate.setFeature(name,value)
   }
   override fun getFeature(name:String)=delegate.getFeature(name)
  }
  val guide=XmlTvGuide.parse("""<tv><channel id="a"><display-name>A</display-name></channel></tv>""",androidLike)
  assertEquals("A",guide!!.channels.single().name)
 }
 @Test fun xmltvDefaultUtcAndInvalidEndAreHandled() {
  val data=XmlTvGuide.parse("""<tv><programme channel="x" start="20261112180000"><title>X</title></programme></tv>""")!!
  assertTrue(data.programmes.isEmpty())
  val utc=XmlTvGuide.parse("""<tv><programme channel="x" start="20261112180000" stop="20261112190000"><title>X</title></programme></tv>""")!!
  assertEquals("2026-11-12T18:00:00Z",utc.programmes.single().start.toString())
 }
}
