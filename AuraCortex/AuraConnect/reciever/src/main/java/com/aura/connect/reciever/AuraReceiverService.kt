package com.aura.connect.reciever

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class AuraReceiverService(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    fun startServer(port: Int = 47121, onMediaReceived: (String, String) -> Unit) {
        if (isRunning) return
        isRunning = true
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i("AuraReceiver", "AuraConnect receiver server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket, onMediaReceived)
                }
            } catch (e: Exception) {
                Log.e("AuraReceiver", "Receiver server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket, onMediaReceived: (String, String) -> Unit) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
            val line = reader.readLine() ?: return
            
            if (line.contains("/aura-connect/pair")) {
                val code = SystemPairingManager.getOrGenerateSystemCode(context)
                val response = JSONObject().put("ok", true).put("code", code).toString()
                writeResponse(socket, response)
            } else if (line.contains("/aura-connect/stream")) {
                onMediaReceived("title", "url")
                writeResponse(socket, "{\"ok\":true}")
            }
        } catch (e: Exception) {
            Log.w("AuraReceiver", "Client handling failed: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun writeResponse(socket: Socket, json: String) {
        val data = json.toByteArray(Charsets.UTF_8)
        val out = socket.getOutputStream()
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.flush()
    }

    fun stopServer() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
