package com.example.camaraip

/** Utilidades para H.264 en formato Annex-B (el que entrega MediaCodec). */
object H264 {
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    fun nalType(nal: ByteArray) = nal[0].toInt() and 0x1F

    /** Separa un buffer Annex-B (start codes 00 00 01 / 00 00 00 01) en NAL units sin start code. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)

        return starts.mapIndexedNotNull { n, start ->
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
            // Un NAL nunca termina en 0x00: esos ceros son parte del start code siguiente.
            while (end > start && data[end - 1] == ZERO) end--
            if (end > start) data.copyOfRange(start, end) else null
        }
    }

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}
