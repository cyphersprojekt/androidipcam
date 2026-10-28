package com.example.camaraip

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Servidor RTSP mínimo (RFC 2326) que publica un único stream de video H.264 (RTP según RFC 6184).
 * Acepta RTP sobre UDP y sobre TCP ("interleaved"), que cubre NVRs, VLC, ffmpeg y OpenCV.
 *
 *   rtsp://<ip>:8554/live   (cualquier ruta sirve)
 *
 * El video lo aporta quien lo use: [hasClients] indica si hace falta codificar, [setConfig]
 * recibe SPS/PPS del encoder y [sendFrame] cada cuadro codificado.
 */
class RtspServer(
    private val port: Int,
    private val onPlayingCountChanged: (Int) -> Unit,
    private val onKeyFrameNeeded: () -> Unit,
) {
    private class StreamConfig(val sps: ByteArray, val pps: ByteArray)

    private class Request(val method: String, val uri: String, val headers: Map<String, String>) {
        val cseq: String get() = headers["cseq"] ?: "0"
    }

    @Volatile private var config: StreamConfig? = null
    private val configLock = ReentrantLock()
    private val configReady = configLock.newCondition()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var workers: ExecutorService? = null
    private val connections = CopyOnWriteArrayList<Connection>()
    private val playing = CopyOnWriteArrayList<Connection>()

    private val ssrc = Random.nextInt()
    private val timestampBase = Random.nextInt()
    private var sequence = Random.nextInt(0x10000) // solo desde el hilo del encoder

    /** Hay alguien conectado: hace falta tener el encoder andando. */
    val hasClients: Boolean get() = connections.isNotEmpty()

    /** Lanza IOException si el puerto no está disponible. */
    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        val pool = Executors.newCachedThreadPool()
        serverSocket = socket
        workers = pool

        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    if (connections.size >= MAX_CONNECTIONS) {
                        client.close()
                        continue
                    }
                    client.tcpNoDelay = true
                    val connection = Connection(client)
                    connections += connection
                    pool.execute { connection.run() }
                } catch (e: SocketException) {
                    break // socket cerrado desde stop()
                } catch (e: Exception) {
                    Log.w(TAG, "accept() falló", e)
                }
            }
        }, "rtsp-accept").apply { isDaemon = true }.start()

        Log.i(TAG, "Servidor RTSP escuchando en el puerto $port")
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        connections.forEach { it.close() }
        workers?.shutdownNow()
        workers = null
    }

    fun setConfig(sps: ByteArray, pps: ByteArray) = configLock.withLock {
        config = StreamConfig(sps, pps)
        configReady.signalAll()
    }

    /** El encoder se detuvo: el próximo cliente tiene que esperar SPS/PPS nuevos. */
    fun clearConfig() {
        config = null
    }

    /** Un cuadro codificado (NAL units sin start code). Se llama desde el hilo del encoder. */
    fun sendFrame(nals: List<ByteArray>, ptsUs: Long, keyFrame: Boolean) {
        if (playing.isEmpty()) return
        val timestamp = timestampBase + (ptsUs * 90 / 1000).toInt() // reloj RTP de 90 kHz
        val cfg = config
        // Algunos encoders no repiten SPS/PPS: se agregan antes de cada keyframe para que un
        // cliente que se suma a mitad del stream pueda empezar a decodificar.
        val units = if (keyFrame && cfg != null && nals.none { H264.nalType(it) == H264.NAL_SPS }) {
            listOf(cfg.sps, cfg.pps) + nals
        } else {
            nals
        }
        units.forEachIndexed { i, nal ->
            packetize(nal, timestamp, lastOfFrame = i == units.lastIndex) { packet ->
                for (connection in playing) connection.sendRtp(packet)
            }
        }
    }

    private fun awaitConfig(timeoutMs: Long): StreamConfig? = configLock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (config == null && nanos > 0) nanos = configReady.awaitNanos(nanos)
        config
    }

    /** RFC 6184: NAL chico en un solo paquete; NAL grande partido en fragmentos FU-A. */
    private fun packetize(nal: ByteArray, timestamp: Int, lastOfFrame: Boolean, emit: (ByteArray) -> Unit) {
        if (nal.size <= MAX_RTP_PAYLOAD) {
            emit(rtpPacket(timestamp, lastOfFrame, EMPTY, nal, 0, nal.size))
            return
        }
        val header = nal[0].toInt()
        val fuIndicator = ((header and 0xE0) or FU_A).toByte()
        var offset = 1 // el header original del NAL viaja dentro del FU header
        while (offset < nal.size) {
            val size = minOf(MAX_RTP_PAYLOAD - 2, nal.size - offset)
            val isStart = offset == 1
            val isEnd = offset + size == nal.size
            val fuHeader = ((if (isStart) 0x80 else 0) or (if (isEnd) 0x40 else 0) or (header and 0x1F)).toByte()
            emit(rtpPacket(timestamp, lastOfFrame && isEnd, byteArrayOf(fuIndicator, fuHeader), nal, offset, size))
            offset += size
        }
    }

    private fun rtpPacket(
        timestamp: Int, marker: Boolean, prefix: ByteArray, data: ByteArray, offset: Int, length: Int,
    ): ByteArray {
        val packet = ByteArray(RTP_HEADER_SIZE + prefix.size + length)
        ByteBuffer.wrap(packet)
            .put(0x80.toByte()) // versión 2
            .put(((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte())
            .putShort(sequence.toShort())
            .putInt(timestamp)
            .putInt(ssrc)
        sequence = (sequence + 1) and 0xFFFF
        prefix.copyInto(packet, RTP_HEADER_SIZE)
        data.copyInto(packet, RTP_HEADER_SIZE + prefix.size, offset, offset + length)
        return packet
    }

    private fun hex32(value: Int) = (value.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()

    /** Una conexión RTSP (un cliente). El video sale por su propio hilo, para que un cliente lento no frene al resto. */
    private inner class Connection(private val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private val sessionId = hex32(Random.nextInt()) + hex32(Random.nextInt())
        private val queue = ArrayBlockingQueue<ByteArray>(SEND_QUEUE_PACKETS)
        private val closed = AtomicBoolean(false)
        private var senderStarted = false

        // Transporte elegido en SETUP: UDP (socket + destino) o TCP interleaved (canal).
        @Volatile private var udpSocket: DatagramSocket? = null
        @Volatile private var udpTarget: InetSocketAddress? = null
        @Volatile private var interleavedChannel = -1

        fun run() {
            try {
                while (!closed.get()) {
                    val request = readRequest() ?: break
                    handle(request)
                }
            } catch (e: IOException) {
                // El cliente cerró la conexión o venció la sesión UDP.
            } catch (e: Exception) {
                Log.w(TAG, "Error en la conexión RTSP con ${socket.inetAddress}", e)
            } finally {
                close()
            }
        }

        fun sendRtp(packet: ByteArray) {
            if (!queue.offer(packet)) {
                Log.w(TAG, "El cliente RTSP ${socket.inetAddress} no da abasto; se lo desconecta")
                close()
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            connections.remove(this)
            if (playing.remove(this)) onPlayingCountChanged(playing.size)
            runCatching { socket.close() }
            udpSocket?.close()
        }

        private fun handle(request: Request) {
            when (request.method) {
                "OPTIONS" -> respond(
                    request, 200,
                    "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER",
                )
                "DESCRIBE" -> describe(request)
                "SETUP" -> setup(request)
                "PLAY" -> play(request)
                "PAUSE" -> {
                    if (playing.remove(this)) onPlayingCountChanged(playing.size)
                    respond(request, 200, sessionHeader())
                }
                "TEARDOWN" -> {
                    respond(request, 200, sessionHeader())
                    close()
                }
                "GET_PARAMETER", "SET_PARAMETER" -> respond(request, 200, sessionHeader()) // keepalive
                else -> respond(request, 501)
            }
        }

        private fun describe(request: Request) {
            // Al conectarse el cliente arranca el encoder; se espera a que entregue SPS/PPS.
            val cfg = awaitConfig(DESCRIBE_TIMEOUT_MS)
            if (cfg == null) {
                respond(request, 503)
                return
            }
            val base = if (request.uri.endsWith("/")) request.uri else "${request.uri}/"
            respond(request, 200, "Content-Base: $base\r\nContent-Type: application/sdp", buildSdp(cfg))
        }

        private fun buildSdp(cfg: StreamConfig): String {
            val local = socket.localAddress
            val addressType = if (local is Inet6Address) "IP6" else "IP4"
            val profileLevelId = if (cfg.sps.size >= 4) {
                cfg.sps.copyOfRange(1, 4).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            } else {
                "42e01f"
            }
            val parameterSets = base64(cfg.sps) + "," + base64(cfg.pps)
            return "v=0\r\n" +
                "o=- ${System.currentTimeMillis()} 1 IN $addressType ${local.hostAddress}\r\n" +
                "s=Camara IP\r\n" +
                "c=IN $addressType ${if (local is Inet6Address) "::" else "0.0.0.0"}\r\n" +
                "t=0 0\r\n" +
                "a=control:*\r\n" +
                "m=video 0 RTP/AVP $PAYLOAD_TYPE\r\n" +
                "a=rtpmap:$PAYLOAD_TYPE H264/90000\r\n" +
                "a=fmtp:$PAYLOAD_TYPE packetization-mode=1;profile-level-id=$profileLevelId;" +
                "sprop-parameter-sets=$parameterSets\r\n" +
                "a=control:$TRACK\r\n"
        }

        private fun setup(request: Request) {
            val transport = request.headers["transport"]
            if (transport == null) {
                respond(request, 400)
                return
            }
            val session = "${sessionHeader()};timeout=$SESSION_TIMEOUT_S"
            if (transport.contains("TCP", ignoreCase = true) || transport.contains("interleaved=")) {
                val match = Regex("interleaved=(\\d+)(?:-(\\d+))?").find(transport)
                val rtp = match?.groupValues?.get(1)?.toInt() ?: 0
                val rtcp = match?.groupValues?.get(2)?.toIntOrNull() ?: (rtp + 1)
                interleavedChannel = rtp
                respond(request, 200, "Transport: RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp;ssrc=${hex32(ssrc)}\r\n$session")
            } else {
                val match = Regex("client_port=(\\d+)(?:-(\\d+))?").find(transport)
                if (match == null || transport.contains("multicast", ignoreCase = true)) {
                    respond(request, 461)
                    return
                }
                val clientRtp = match.groupValues[1].toInt()
                val clientRtcp = match.groupValues[2].toIntOrNull() ?: (clientRtp + 1)
                val udp = DatagramSocket()
                udpSocket?.close()
                udpTarget = InetSocketAddress(socket.inetAddress, clientRtp)
                udpSocket = udp
                // Por UDP no hay forma de saber si el cliente sigue ahí: si deja de mandar
                // keepalives por la conexión RTSP, se corta.
                socket.soTimeout = UDP_SESSION_TIMEOUT_MS
                respond(
                    request, 200,
                    "Transport: RTP/AVP;unicast;client_port=$clientRtp-$clientRtcp;" +
                        "server_port=${udp.localPort}-${udp.localPort + 1};ssrc=${hex32(ssrc)}\r\n$session",
                )
            }
        }

        private fun play(request: Request) {
            if (interleavedChannel < 0 && udpSocket == null) {
                respond(request, 455)
                return
            }
            // Primero la respuesta: con TCP interleaved no puede llegar video antes que el 200 OK.
            respond(request, 200, "${sessionHeader()}\r\nRange: npt=0.000-")
            if (!senderStarted) {
                val pool = workers ?: return close()
                senderStarted = true
                pool.execute { sendLoop() }
            }
            if (!playing.contains(this)) {
                playing += this
                onPlayingCountChanged(playing.size)
            }
            onKeyFrameNeeded() // así el cliente no espera hasta el próximo keyframe
        }

        private fun sendLoop() {
            try {
                while (!closed.get()) {
                    val packet = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    val udp = udpSocket
                    if (udp != null) {
                        udp.send(DatagramPacket(packet, packet.size, udpTarget))
                    } else {
                        val frame = ByteArray(4 + packet.size)
                        frame[0] = '$'.code.toByte()
                        frame[1] = interleavedChannel.toByte()
                        frame[2] = (packet.size shr 8).toByte()
                        frame[3] = packet.size.toByte()
                        packet.copyInto(frame, 4)
                        write(frame)
                    }
                }
            } catch (e: Exception) {
                // El cliente se fue.
            } finally {
                close()
            }
        }

        private fun sessionHeader() = "Session: $sessionId"

        private fun respond(request: Request, status: Int, headers: String = "", body: String = "") {
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("RTSP/1.0 $status ${reasonPhrase(status)}\r\n")
                append("CSeq: ${request.cseq}\r\n")
                append("Server: CamaraIP\r\n")
                if (headers.isNotEmpty()) append(headers).append("\r\n")
                if (bodyBytes.isNotEmpty()) append("Content-Length: ${bodyBytes.size}\r\n")
                append("\r\n")
            }
            write(head.toByteArray(Charsets.UTF_8) + bodyBytes)
        }

        /** Respuestas RTSP y video interleaved comparten el socket: nunca se escriben mezclados. */
        private fun write(bytes: ByteArray) = synchronized(output) {
            output.write(bytes)
            output.flush()
        }

        /** Lee el próximo pedido RTSP, descartando los paquetes RTCP que el cliente intercala por TCP. */
        private fun readRequest(): Request? {
            var requestLine: String
            while (true) {
                val first = input.read()
                if (first < 0) return null
                if (first == '$'.code) {
                    input.read() // canal
                    skip((input.read() shl 8) or input.read())
                    continue
                }
                if (first == '\r'.code || first == '\n'.code) continue
                requestLine = first.toChar() + (readLine() ?: return null)
                break
            }
            val parts = requestLine.split(' ')
            if (parts.size < 3) throw IOException("Pedido RTSP inválido: $requestLine")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            headers["content-length"]?.toIntOrNull()?.let { skip(it) }
            return Request(parts[0].uppercase(), parts[1], headers)
        }

        private fun readLine(): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return line.trimEnd('\r').toString()
                if (line.length >= MAX_LINE_LENGTH) throw IOException("Línea RTSP demasiado larga")
                line.append(b.toChar())
            }
        }

        private fun skip(count: Int) {
            repeat(count) { if (input.read() < 0) throw IOException("Conexión cerrada") }
        }
    }

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun reasonPhrase(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        455 -> "Method Not Valid in This State"
        461 -> "Unsupported Transport"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    private companion object {
        const val TAG = "RtspServer"
        const val TRACK = "trackID=0"
        const val PAYLOAD_TYPE = 96
        const val FU_A = 28
        const val RTP_HEADER_SIZE = 12
        const val MAX_RTP_PAYLOAD = 1400
        const val MAX_CONNECTIONS = 8
        const val SEND_QUEUE_PACKETS = 2000 // ~10 s de video a 2 Mbps
        const val DESCRIBE_TIMEOUT_MS = 5_000L
        const val SESSION_TIMEOUT_S = 60
        const val UDP_SESSION_TIMEOUT_MS = 2 * SESSION_TIMEOUT_S * 1000
        const val MAX_LINE_LENGTH = 8192
        val EMPTY = ByteArray(0)
    }
}
