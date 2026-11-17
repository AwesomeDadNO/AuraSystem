package com.aura.amp

import android.content.Context
import no.aura.connect.android.AuraConnectFiles
import no.aura.connect.android.ConnectController
import no.aura.connect.android.FileReceiverDirectory
import no.aura.connect.android.FileReceiverStore
import no.aura.connect.android.KeystoreIdentity
import no.aura.connect.android.PairingUri
import no.aura.connect.core.ErrorCode
import org.json.JSONArray
import org.json.JSONObject

class AuraConnectClient(context: Context) {
    private val app = context.applicationContext
    private val controller: ConnectController by lazy {
        val files = AuraConnectFiles(app)
        ConnectController(KeystoreIdentity.loadOrCreate(app), FileReceiverDirectory(FileReceiverStore(files.receiverStore)), name = "AuraAmp")
    }

    fun pair(payload: String): String {
        val target = resolvePairingTarget(payload) ?: return err("Ugyldig paringskode. Tast 6 siffer eller skann QR-kode på TV.")
        return when (val r = controller.pair(target)) {
            is ConnectController.PairResult.Paired -> JSONObject().put("ok", true).put("deviceId", r.deviceId).put("name", r.name).put("fingerprint", r.deviceId.take(8).uppercase()).toString()
            is ConnectController.PairResult.Failed -> err(message(r.code, r.message))
        }
    }

    private fun resolvePairingTarget(payload: String): ConnectController.PairingCode? {
        val clean = payload.trim()
        val uri = PairingUri.parse(clean)
        val uriCode = uri?.code
        if (uri != null && !uriCode.isNullOrBlank()) {
            return ConnectController.PairingCode(uri.host, uri.port, uriCode)
        }

        val digits = clean.replace(Regex("[^0-9]"), "")
        val code = if (digits.length == 6) digits else if (clean.length == 6 && clean.all { it.isDigit() }) clean else null

        val ipRegex = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        val ip = ipRegex.find(clean)?.value

        val portRegex = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:([0-9]{2,5})")
        val port = portRegex.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 47121

        if (ip != null && code != null) {
            return ConnectController.PairingCode(ip, port, code)
        }

        if (code != null) {
            val lastHost = controller.receivers().firstOrNull()?.lastHost ?: "192.168.10.239"
            val lastPort = controller.receivers().firstOrNull()?.lastPort ?: 47121
            return ConnectController.PairingCode(lastHost, lastPort, code)
        }

        return null
    }

    fun receivers(): String {
        val arr = JSONArray()
        controller.receivers().forEach { arr.put(JSONObject().put("deviceId", it.receiver.deviceId).put("name", it.receiver.name).put("host", it.lastHost ?: "")) }
        return arr.toString()
    }

    fun forget(deviceId: String): Boolean = controller.forget(deviceId)

    fun sendArl(deviceId: String, arl: String?): String {
        if (arl.isNullOrBlank()) return err("No Deezer ARL is saved in this app yet.")
        return when (val r = controller.sendDeezerArl(deviceId, arl)) {
            ConnectController.SendResult.Sent -> JSONObject().put("ok", true).toString()
            ConnectController.SendResult.NeedsRenewal -> JSONObject().put("ok", true).put("renew", true).toString()
            ConnectController.SendResult.NotPaired -> err("That device is not paired. Scan its code again.")
            is ConnectController.SendResult.Refused -> err(message(r.code, r.message))
        }
    }

    fun sendPlaylists(deviceId: String, playlistsJson: String?): String {
        if (playlistsJson.isNullOrBlank()) return err("No playlists selected to send.")
        return try {
            val arr = JSONArray(playlistsJson)
            val playlists = ArrayList<ConnectController.PushPlaylist>()
            for (i in 0 until arr.length()) {
                val pObj = arr.getJSONObject(i)
                val tArr = pObj.optJSONArray("tracks") ?: JSONArray()
                val tracks = ArrayList<ConnectController.PushTrack>()
                for (j in 0 until tArr.length()) {
                    val tObj = tArr.getJSONObject(j)
                    tracks.add(
                        ConnectController.PushTrack(
                            id = tObj.optString("id", "t$j"),
                            title = tObj.optString("title", "Untitled"),
                            artist = tObj.optString("artist", "Unknown"),
                            durationMs = tObj.optLong("durationMs", tObj.optLong("duration", 0L) * 1000L),
                            isrc = tObj.optString("isrc").takeIf { !it.isNullOrBlank() },
                            cover = tObj.optString("cover").takeIf { !it.isNullOrBlank() }
                        )
                    )
                }
                playlists.add(
                    ConnectController.PushPlaylist(
                        id = pObj.optString("id", "p$i"),
                        title = pObj.optString("name", pObj.optString("title", "Playlist")),
                        description = pObj.optString("description", ""),
                        tracks = tracks
                    )
                )
            }
            when (val r = controller.sendPlaylists(deviceId, playlists)) {
                ConnectController.SendResult.Sent -> JSONObject().put("ok", true).put("count", playlists.size).toString()
                ConnectController.SendResult.NeedsRenewal -> JSONObject().put("ok", true).put("renew", true).toString()
                ConnectController.SendResult.NotPaired -> err("That device is not paired. Scan its code again.")
                is ConnectController.SendResult.Refused -> err(message(r.code, r.message))
            }
        } catch (e: Exception) {
            err("Invalid playlists payload: ${e.message}")
        }
    }

    fun control(deviceId: String, op: String, argsJson: String?): String {
        val argsMap = mutableMapOf<String, Any?>()
        if (!argsJson.isNullOrBlank()) {
            try {
                val parsed = toPlainValue(JSONObject(argsJson))
                if (parsed is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    argsMap.putAll(parsed as Map<String, Any?>)
                }
            } catch (_: Exception) {}
        }
        return when (val r = controller.control(deviceId, op, argsMap)) {
            is ConnectController.ControlResult.State -> {
                val s = r.state
                JSONObject().put("ok", true)
                    .put("playing", s.playing).put("loading", s.loading)
                    .put("shuffle", s.shuffle).put("repeat", s.repeat)
                    .put("positionMs", s.positionMs).put("durationMs", s.durationMs)
                    .put("trackId", s.trackId ?: "").put("title", s.title ?: "")
                    .put("artist", s.artist ?: "").put("queueLength", s.queueLength)
                    .toString()
            }
            ConnectController.ControlResult.NotPaired -> err("That device is not paired.")
            is ConnectController.ControlResult.Refused -> err(message(r.code, r.message))
        }
    }

    fun sendMediaServerSignIn(deviceId: String, serverJson: String?): String {
        if (serverJson.isNullOrBlank()) return err("No media server details provided.")
        return try {
            val obj = JSONObject(serverJson)
            val base = obj.optString("base", "http://192.168.10.1:32400")
            val s = ConnectController.MediaServerSignIn(
                kind = obj.optString("kind", obj.optString("type", "plex")).lowercase(),
                serverId = obj.optString("serverId", obj.optString("id", "server1")),
                serverName = obj.optString("serverName", obj.optString("name", "Home Media Server")),
                base = base,
                lanHttp = obj.optBoolean("lanHttp", base.startsWith("http://")),
                userId = obj.optString("userId", "u1"),
                userName = obj.optString("userName", "User"),
                token = obj.optString("token", ""),
                deviceId = obj.optString("deviceId", "aura_amp_mobile")
            )
            when (val r = controller.sendMediaServerSignIn(deviceId, s)) {
                ConnectController.SendResult.Sent -> JSONObject().put("ok", true).toString()
                ConnectController.SendResult.NeedsRenewal -> JSONObject().put("ok", true).put("renew", true).toString()
                ConnectController.SendResult.NotPaired -> err("That device is not paired.")
                is ConnectController.SendResult.Refused -> err(message(r.code, r.message))
            }
        } catch (e: Exception) {
            err("Invalid media server payload: ${e.message}")
        }
    }

    private fun toPlainValue(v: Any?): Any? = when (v) {
        is JSONObject -> {
            val map = mutableMapOf<String, Any?>()
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = toPlainValue(v.get(k))
            }
            map
        }
        is JSONArray -> {
            val list = mutableListOf<Any?>()
            for (i in 0 until v.length()) {
                list.add(toPlainValue(v.get(i)))
            }
            list
        }
        JSONObject.NULL -> null
        else -> v
    }

    private fun err(message: String) = JSONObject().put("ok", false).put("error", message).toString()
    private fun message(code: ErrorCode, fallback: String): String = when (code) {
        ErrorCode.NOT_AUTHORIZED -> "The TV must allow receiving a sign-in first. Choose Connect via phone on the TV."
        ErrorCode.PAIRING_FAILED -> "Wrong or expired code. Show a new code on the TV and try again."
        ErrorCode.PAIRING_EXPIRED -> "The code expired. Show a new code on the TV."
        ErrorCode.PAIRING_UNAVAILABLE -> "The TV is not ready to pair. Choose Connect/Pair on the TV first."
        ErrorCode.BUSY -> "Another device is pairing with the TV. Try again in a moment."
        ErrorCode.REVOKED -> "This phone was removed on the TV. Pair again."
        ErrorCode.TIMEOUT -> "Could not reach the TV. Check that both are on the same network."
        else -> fallback
    }
}
