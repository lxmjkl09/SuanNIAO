package dev.suanniao

import java.nio.ByteBuffer

/**
 * Extract AVC configuration NAL units from the first complete Annex-B AU.
 * H264 decoders are not required to infer SPS/PPS from ordinary frame data.
 */
internal object H264ParameterSets {
    data class Sets(val sps: ByteBuffer, val pps: ByteBuffer)

    fun extract(frame: ByteArray): Sets? {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < frame.size - 3) {
            val four = i + 3 < frame.size && frame[i] == 0.toByte() &&
                frame[i + 1] == 0.toByte() && frame[i + 2] == 0.toByte() && frame[i + 3] == 1.toByte()
            val three = !four && frame[i] == 0.toByte() &&
                frame[i + 1] == 0.toByte() && frame[i + 2] == 1.toByte()
            if (four || three) {
                starts.add(i to if (four) 4 else 3)
                i += if (four) 4 else 3
            } else i++
        }
        var sps: ByteBuffer? = null
        var pps: ByteBuffer? = null
        for (j in starts.indices) {
            val (start, prefix) = starts[j]
            val payload = start + prefix
            val end = if (j + 1 < starts.size) starts[j + 1].first else frame.size
            if (payload >= end) continue
            val type = frame[payload].toInt() and 31
            if (type == 7 || type == 8) {
                val bytes = byteArrayOf(0, 0, 0, 1) + frame.copyOfRange(payload, end)
                if (type == 7 && sps == null) sps = ByteBuffer.wrap(bytes)
                if (type == 8 && pps == null) pps = ByteBuffer.wrap(bytes)
            }
        }
        return if (sps != null && pps != null) Sets(sps, pps) else null
    }
}
