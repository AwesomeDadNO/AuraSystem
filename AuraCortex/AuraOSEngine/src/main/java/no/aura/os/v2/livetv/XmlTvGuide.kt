package no.aura.os.v2.livetv

import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** Bounded user XMLTV feed; guide data never proves an installed app can tune the programme. */
data class GuideChannel(val id: String,val name: String)
data class GuideProgramme(val channelId: String,val title: String,val start: Instant,val end: Instant,val description: String="")
data class GuideData(val channels: List<GuideChannel>,val programmes: List<GuideProgramme>)
object XmlTvGuide {
    const val MAX_BYTES=8*1024*1024
    fun parse(text: String): GuideData? = parse(text, SAXParserFactory.newInstance())
    internal fun parse(text: String, factory: SAXParserFactory): GuideData? = runCatching {
        require(text.length<=MAX_BYTES && !Regex("<!\\s*(DOCTYPE|ENTITY)",RegexOption.IGNORE_CASE).containsMatchIn(text))
        factory.apply {
            isNamespaceAware=false;isValidating=false
            setFeature("http://xml.org/sax/features/external-general-entities",false)
            setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        }
        val channels=ArrayList<GuideChannel>();val programmes=ArrayList<GuideProgramme>()
        val handler=object: DefaultHandler() {
            var channelId: String?=null;var channelName="";var programmeId: String?=null
            var start: Instant?=null;var stop: Instant?=null;var title="";var description=""
            var leaf="";val content=StringBuilder();var depth=0;var root=false
            override fun startElement(uri:String?,localName:String?,qName:String,attrs:Attributes) {
                depth++;require(depth<=24);if(depth==1){require(qName=="tv");root=true}
                leaf=qName;content.setLength(0)
                when(qName) {
                    "channel" -> { require(channels.size<5000);channelId=attrs.getValue("id")?.take(200);channelName="" }
                    "programme" -> { require(programmes.size<30000);programmeId=attrs.getValue("channel")?.take(200)
                        start=parseTime(attrs.getValue("start"));stop=parseTime(attrs.getValue("stop"));title="";description="" }
                }
            }
            override fun characters(ch:CharArray,start:Int,length:Int) { if(content.length<4000)content.append(ch,start,minOf(length,4000-content.length)) }
            override fun endElement(uri:String?,localName:String?,qName:String) {
                val value=content.toString().trim()
                when(qName) {
                    "display-name" -> if(channelId!=null && channelName.isEmpty())channelName=value.take(200)
                    "title" -> if(programmeId!=null && title.isEmpty())title=value.take(200)
                    "desc" -> if(programmeId!=null && description.isEmpty())description=value.take(2000)
                    "channel" -> { channelId?.takeIf { it.isNotBlank() }?.let { channels+=GuideChannel(it,channelName.ifBlank { it }) };channelId=null }
                    "programme" -> { val s=start;val e=stop;val id=programmeId
                        if(id!=null && id.isNotBlank() && s!=null && e!=null && e>s && title.isNotBlank())programmes+=GuideProgramme(id,title,s,e,description)
                        programmeId=null }
                }
                content.setLength(0);depth--
            }
            override fun resolveEntity(publicId:String?,systemId:String?):InputSource = throw IllegalArgumentException("External entities are forbidden")
        }
        factory.newSAXParser().parse(InputSource(StringReader(text)),handler)
        require(handler.root)
        GuideData(channels.distinctBy { it.id },programmes.distinctBy { Triple(it.channelId,it.start,it.title) }.sortedBy { it.start })
    }.getOrNull()
    private fun parseTime(raw:String?):Instant? = runCatching {
        val value=raw?.trim() ?:return null
        require(value.matches(Regex("[0-9]{14}( [+-][0-9]{4})?")))
        // XMLTV specifies UTC when no explicit zone is present.
        OffsetDateTime.parse(if(value.length==14) "$value +0000" else value,DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")).toInstant()
    }.getOrNull()
}
