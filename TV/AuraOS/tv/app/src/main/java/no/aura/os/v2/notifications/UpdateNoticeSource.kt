package no.aura.os.v2.notifications

import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.tmdb.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class VerifiedUpdateNotice(val versionCode: Long, val versionName: String, val publishedAt: Long)
/** A notice is metadata only. No download, installation, APK signing or release operation. */
object UpdateManifestPolicy {
    fun verify(envelope: String, publicKey: String, application: String, channel: String, currentVersion: Long, now: Long): VerifiedUpdateNotice? = runCatching {
        if (envelope.length > 128 * 1024 || publicKey.length !in 64..8192) return null
        val root = Json.parse(envelope).obj() ?: return null
        val payload = Base64.getDecoder().decode(root.str("payload") ?: return null)
        if (payload.size > 64 * 1024) return null
        val signature = Base64.getDecoder().decode(root.str("signature") ?: return null)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        if (!Signature.getInstance("SHA256withRSA").run { initVerify(key); update(payload); verify(signature) }) return null
        val notice = Json.parse(String(payload, Charsets.UTF_8)).obj() ?: return null
        if (notice.str("appId") != application || notice.str("channel") != channel) return null
        val version = (notice["versionCode"] as? Double)?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 1.0..Int.MAX_VALUE.toDouble() }?.toLong() ?: return null
        val name = notice.str("versionName")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: return null
        val published = Instant.parse(notice.str("publishedAt") ?: return null).toEpochMilli()
        if (version <= currentVersion || published > now || published < now - 30L * 24 * 3600_000L) return null
        VerifiedUpdateNotice(version, name, published)
    }.getOrNull()
}
class UpdateNoticeSource(private val manifestUrl: String, private val verificationKey: String, private val application: String,
    private val channel: String, private val version: Long, private val now: () -> Long = System::currentTimeMillis) : NotificationSourceAdapter {
    override val category = NotificationCategory.AURA_UPDATE
    override suspend fun fetch(profile: String): SourceBatch {
        if (manifestUrl.isBlank() || verificationKey.isBlank()) return SourceBatch(emptyList(), "No signed AuraOS update channel configured")
        val body = try { no.aura.os.v2.livetv.LiveTvHttp.read(manifestUrl, 128 * 1024) }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { return SourceBatch(emptyList(), "Update channel unavailable") }
        val notice = UpdateManifestPolicy.verify(body, verificationKey, application, channel, version, now())
            ?: return SourceBatch(emptyList(), "No newer verified update for this application/channel")
        return SourceBatch(listOf(CenterEvent("aura-update:$channel:${notice.versionCode}", category, "Signed AuraOS update channel",
            "AuraOS ${notice.versionName} is available", "Version ${notice.versionCode}. Updates are never installed automatically.", notice.publishedAt)))
    }
}
