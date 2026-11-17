package com.aura.amp

import android.content.Context
import androidx.core.content.ContextCompat
import no.aura.cortex.AuraCortex
import no.aura.cortex.AuraConnect
import no.aura.cortex.AuraConnectContract
import org.json.JSONArray
import org.json.JSONObject

class AuraConnectClient(context: Context) {
    private val app = context.applicationContext

    init {
        if (!AuraCortex.isStarted) {
            try {
                AuraCortex.start(
                    AuraCortex.Config(
                        dataDir = app.filesDir,
                        deviceName = "AuraAmp Phone",
                        deviceType = AuraConnect.DeviceType.PHONE,
                        callbackExecutor = ContextCompat.getMainExecutor(app),
                    )
                )
            } catch (_: Exception) {}
        }
    }

    fun pair(payload: String): String {
        val clean = payload.trim()
        val resultJson = JSONObject()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg = "Ugyldig paringskode eller QR-kode."

        if (clean.startsWith("auraconnect://")) {
            AuraCortex.connect.pairWithQr(clean) { res ->
                success = res.isSuccess
                if (success) {
                    val dev = res.getOrNull()
                    if (dev != null) {
                        resultJson.put("ok", true).put("deviceId", dev.deviceId).put("name", dev.name).put("fingerprint", dev.shortFingerprint)
                    } else {
                        resultJson.put("ok", true)
                    }
                } else {
                    errorMsg = res.exceptionOrNull()?.message ?: "Pering feilet"
                }
                latch.countDown()
            }
        } else {
            val digits = clean.replace(Regex("[^0-9]"), "")
            val code = if (digits.length == 6) digits else if (clean.length == 6 && clean.all { it.isDigit() }) clean else null
            if (code != null) {
                val devices = AuraCortex.connect.pairedDevices()
                val discovered = devices.firstOrNull()
                if (discovered != null) {
                    val discDev = AuraConnect.DiscoveredDevice(discovered.deviceId, discovered.name, discovered.ipAddress, discovered.type)
                    AuraCortex.connect.pairWithDevice(discDev, code) { res ->
                        success = res.isSuccess
                        if (success) {
                            val dev = res.getOrNull()
                            if (dev != null) {
                                resultJson.put("ok", true).put("deviceId", dev.deviceId).put("name", dev.name).put("fingerprint", dev.shortFingerprint)
                            } else {
                                resultJson.put("ok", true)
                            }
                        } else {
                            errorMsg = res.exceptionOrNull()?.message ?: "Pering feilet"
                        }
                        latch.countDown()
                    }
                } else {
                    AuraCortex.connect.scanForAuraDevices { dev ->
                        val discDev = AuraConnect.DiscoveredDevice(dev.deviceId, dev.name, dev.ipAddress, dev.type)
                        AuraCortex.connect.pairWithDevice(discDev, code) { res ->
                            success = res.isSuccess
                            if (success) {
                                val d = res.getOrNull()
                                if (d != null) {
                                    resultJson.put("ok", true).put("deviceId", d.deviceId).put("name", d.name).put("fingerprint", d.shortFingerprint)
                                } else {
                                    resultJson.put("ok", true)
                                }
                            } else {
                                errorMsg = res.exceptionOrNull()?.message ?: "Pering feilet"
                            }
                            latch.countDown()
                        }
                    }
                }
            } else {
                return err("Ugyldig paringskode. Tast 6 siffer eller skann QR-kode på TV.")
            }
        }

        try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return if (success) resultJson.toString() else err(errorMsg)
    }

    fun receivers(): String {
        val arr = JSONArray()
        AuraCortex.connect.pairedDevices().forEach { d ->
            arr.put(JSONObject().put("deviceId", d.deviceId).put("name", d.name).put("host", d.ipAddress))
        }
        return arr.toString()
    }

    fun forget(deviceId: String): Boolean {
        return try {
            AuraCortex.connect.unpair(deviceId)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendArl(deviceId: String, arl: String?): String {
        if (arl.isNullOrBlank()) return err("No Deezer ARL is saved in this app yet.")
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg = "Feil ved sending av ARL"
        AuraCortex.connect.call(deviceId, AuraConnectContract.CREDENTIAL_OFFER, mapOf("kind" to AuraConnectContract.DEEZER_ARL_KIND, "value" to arl)) { res ->
            success = res.ok
            if (!success) {
                errorMsg = res.message ?: res.error?.name ?: "Feil"
            }
            latch.countDown()
        }
        try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return if (success) JSONObject().put("ok", true).toString() else err(errorMsg)
    }

    fun sendPlaylists(deviceId: String, playlistsJson: String?): String {
        if (playlistsJson.isNullOrBlank()) return err("No playlists selected to send.")
        return try {
            val arr = JSONArray(playlistsJson)
            val playlistsList = mutableListOf<Map<String, Any?>>()
            for (i in 0 until arr.length()) {
                val pObj = arr.getJSONObject(i)
                val tArr = pObj.optJSONArray("tracks") ?: JSONArray()
                val tracksList = mutableListOf<Map<String, Any?>>()
                for (j in 0 until tArr.length()) {
                    val tObj = tArr.getJSONObject(j)
                    tracksList.add(
                        mapOf(
                            "id" to tObj.optString("id", "t$j"),
                            "title" to tObj.optString("title", "Untitled"),
                            "artist" to tObj.optString("artist", "Unknown"),
                            "durationMs" to tObj.optLong("durationMs", tObj.optLong("duration", 0L) * 1000L),
                            "isrc" to tObj.optString("isrc").takeIf { it.isNotBlank() },
                            "cover" to tObj.optString("cover").takeIf { it.isNotBlank() },
                        )
                    )
                }
                playlistsList.add(
                    mapOf(
                        "id" to pObj.optString("id", "p$i"),
                        "title" to pObj.optString("name", pObj.optString("title", "Playlist")),
                        "description" to pObj.optString("description", ""),
                        "tracks" to tracksList,
                    )
                )
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            var success = false
            var errorMsg = "Feil ved sending av spillelister"
            var resultData: Map<String, Any?> = emptyMap()
            AuraCortex.connect.call(deviceId, AuraConnectContract.PLAYLIST_PUSH, mapOf("playlists" to playlistsList)) { res ->
                success = res.ok
                if (!success) {
                    errorMsg = res.message ?: res.error?.name ?: "Feil"
                } else resultData = res.data
                latch.countDown()
            }
            try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            if (success) {
                val skipped = resultData["skipped"] as? List<*> ?: emptyList<Any?>()
                val imported = (resultData["imported"] as? Number)?.toInt() ?: playlistsList.size
                JSONObject().put("ok", true)
                    .put("count", imported)
                    .put("imported", imported)
                    .put("skipped", skipped)
                    .toString()
            } else err(errorMsg)
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
        val latch = java.util.concurrent.CountDownLatch(1)
        var resData: Map<String, Any?> = emptyMap()
        var success = false
        var errorMsg = "Fjernkontrollfeil"
        AuraCortex.connect.call(deviceId, op, argsMap) { res ->
            success = res.ok
            if (success) {
                resData = res.data
            } else {
                errorMsg = res.message ?: res.error?.name ?: "Feil"
            }
            latch.countDown()
        }
        try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        if (!success) return JSONObject().put("ok", false).put("op", op).put("error", errorMsg).toString()

        val resp = JSONObject().put("ok", true).put("op", op)
        resData.forEach { (k, v) -> resp.put(k, v) }
        return resp.toString()
    }

    fun sendMediaServerSignIn(deviceId: String, serverJson: String?): String {
        if (serverJson.isNullOrBlank()) return err("No media server details provided.")
        return try {
            val obj = JSONObject(serverJson)
            val base = obj.optString("base", "http://192.168.10.1:32400")
            val serverMap = mapOf(
                "kind" to obj.optString("kind", obj.optString("type", "plex")).lowercase(),
                "serverId" to obj.optString("serverId", obj.optString("id", "server1")),
                "serverName"  to obj.optString("serverName", obj.optString("name", "Home Media Server")),
                "base" to base,
                "token" to obj.optString("token", "")
            )
            val latch = java.util.concurrent.CountDownLatch(1)
            var success = false
            var errorMsg = "Feil ved medieserver-innlogging"
            AuraCortex.connect.call(deviceId, "media.offer", serverMap) { res ->
                success = res.ok
                if (!success) {
                    errorMsg = res.message ?: res.error?.name ?: "Feil"
                }
                latch.countDown()
            }
            try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        if (success) JSONObject().put("ok", true).toString() else err(errorMsg)
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
}
