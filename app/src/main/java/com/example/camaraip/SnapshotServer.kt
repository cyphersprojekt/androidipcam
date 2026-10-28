package com.example.camaraip

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo, sin dependencias externas.
 *
 *   GET /photo  (o /photo.jpg) -> JPEG del cuadro actual de la cámara
 *       ?width=640    achica la imagen a ese ancho (nunca la agranda)
 *       ?quality=70   calidad JPEG 1-100
 *   GET /                      -> texto de estado (sirve como health check)
 */
class SnapshotServer(
    private val port: Int,
    private val takePhoto: (query: Map<String, String>) -> ByteArray,
) {
    /** Lanzala desde [takePhoto] para responder con un código HTTP específico. */
    class HttpException(val status: Int, message: String) : Exception(message)

    @Volatile private var serverSocket: ServerSocket? = null
    private var workers: ExecutorService? = null

    /** Lanza IOException si el puerto no está disponible. */
    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS)
        serverSocket = socket
        workers = pool

        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    pool.execute { handle(client) }
                } catch (e: SocketException) {
                    break // socket cerrado desde stop()
                } catch (e: Exception) {
                    Log.w(TAG, "accept() falló", e)
                }
            }
        }, "http-accept").apply { isDaemon = true }.start()

        Log.i(TAG, "Servidor escuchando en el puerto $port")
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        workers?.shutdownNow()
        workers = null
    }

    private fun handle(client: Socket) = client.use { socket ->
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return@use
            // Los headers no nos interesan: se descartan hasta la línea vacía.
            while (!reader.readLine().isNullOrEmpty()) Unit

            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "" }
            val target = parts.getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val out = socket.getOutputStream()

            when {
                method != "GET" -> sendText(out, 405, "Solo se admite GET\n")
                path == "/photo" || path == "/photo.jpg" -> servePhoto(out, query)
                path == "/" -> sendText(
                    out, 200,
                    "Camara IP activa. GET /photo para obtener una foto JPEG " +
                        "(opcional: ?width=640&quality=70).\n",
                )
                else -> sendText(out, 404, "No encontrado. Usar GET /photo\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error atendiendo a ${socket.inetAddress}", e)
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&')
            .filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }

    private fun servePhoto(out: OutputStream, query: Map<String, String>) {
        val jpeg = try {
            takePhoto(query)
        } catch (e: HttpException) {
            sendText(out, e.status, "${e.message}\n")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Falló la captura", e)
            sendText(out, 500, "Error al capturar: ${e.message}\n")
            return
        }
        send(out, 200, "image/jpeg", jpeg)
    }

    private fun sendText(out: OutputStream, status: Int, text: String) =
        send(out, status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun send(out: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status ${reasonPhrase(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        // Header y cuerpo en un solo write: evita la demora de Nagle + delayed ACK.
        val headerBytes = header.toByteArray(Charsets.ISO_8859_1)
        out.write(headerBytes + body)
        out.flush()
    }

    private fun reasonPhrase(status: Int) = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Internal Server Error"
    }

    private companion object {
        const val TAG = "SnapshotServer"
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_CONCURRENT_REQUESTS = 4
    }
}
