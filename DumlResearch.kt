package com.freefcc.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lito X1 / RC2 research helpers for FreeFCC.
 *
 * Design goals:
 *  - no root / bootloader changes
 *  - no arbitrary raw-command console
 *  - read-only telemetry capture by default
 *  - optional FCC apply uses the exact upstream fcc.json profile and adds
 *    response/ACK accounting; it does not claim to prove the final RF region
 */
object DumlResearch {
    private const val HOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 700
    private val KNOWN_PORTS = listOf(
        DumlTransport.PORT,
        DumlTransport.PORT_LED,
        DumlTransport.PORT_ALT_1,
        DumlTransport.PORT_ALT_2,
        DumlTransport.PORT_ALT_3,
        DumlTransport.PORT_ALT_4
    )

    data class ObservedFrame(
        val offset: Int,
        val wrapped: Boolean,
        val validCrc: Boolean,
        val sender: Int,
        val dst: Int,
        val sequence: Int,
        val cmdType: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray
    ) {
        fun oneLine(): String {
            val type = if ((cmdType and 0x80) != 0) "RESP" else "REQ"
            val crc = if (validCrc) "CRC=OK" else "CRC=BAD"
            val wrap = if (wrapped) " WRAP" else ""
            val payloadHex = payload.take(32).joinToString("") { "%02X".format(it) } +
                if (payload.size > 32) "…" else ""
            return "@$offset $type$wrap $crc src=$sender dst=$dst seq=$sequence set=$cmdSet id=$cmdId len=${payload.size} p=$payloadHex"
        }
    }

    data class CaptureResult(
        val port: Int,
        val durationMs: Long,
        val byteCount: Int,
        val frames: List<ObservedFrame>,
        val error: String? = null
    ) {
        fun report(maxFrames: Int = 120): String = buildString {
            appendLine("Passive DUML telemetry capture")
            appendLine("Port: $port")
            appendLine("Duration: ${durationMs} ms")
            appendLine("Bytes: $byteCount")
            appendLine("Parsed frames: ${frames.size}")
            val good = frames.count { it.validCrc }
            appendLine("CRC-valid: $good/${frames.size}")
            if (!error.isNullOrBlank()) appendLine("Error: $error")
            appendLine()
            frames.take(maxFrames).forEachIndexed { index, f ->
                appendLine("${index + 1}. ${f.oneLine()}")
            }
            if (frames.size > maxFrames) {
                appendLine("… ${frames.size - maxFrames} more frames omitted from UI report")
            }
        }
    }

    data class AckCheckResult(
        val port: Int,
        val attempts: Int,
        val validAcks: Int,
        val elapsedMs: Long,
        val details: List<String>,
        val error: String? = null
    ) {
        fun report(): String = buildString {
            appendLine("FCC apply + ACK diagnostic")
            appendLine("Port: $port")
            appendLine("Attempts: $attempts")
            appendLine("Valid matching DUML ACKs: $validAcks/$attempts")
            appendLine("Elapsed: ${elapsedMs} ms")
            appendLine("Interpretation: ACK count checks protocol responses only; it does NOT independently prove the final CE/FCC RF region.")
            if (!error.isNullOrBlank()) appendLine("Error: $error")
            appendLine()
            details.forEach { appendLine(it) }
        }
    }

    /** Finds which known DJI local DUML TCP endpoints are reachable. Read-only. */
    fun scanPorts(): String {
        val lines = mutableListOf<String>()
        lines += "DUML port scan"
        for (port in KNOWN_PORTS) {
            val start = System.nanoTime()
            val ok = canConnect(port)
            val ms = (System.nanoTime() - start) / 1_000_000.0
            lines += "%5d  %-6s  %.1f ms".format(Locale.US, port, if (ok) "OPEN" else "closed", ms)
        }
        return lines.joinToString("\n")
    }

    /**
     * Opens the detected DUML proxy and only reads unsolicited traffic.
     * It never writes a DUML command.
     */
    fun capturePassive(durationMs: Int = 5000, maxBytes: Int = 512 * 1024): CaptureResult {
        val port = KNOWN_PORTS.firstOrNull { canConnect(it) } ?: -1
        if (port <= 0) {
            return CaptureResult(-1, 0, 0, emptyList(), "No known DUML TCP port is reachable")
        }

        var socket: Socket? = null
        val acc = ByteArrayOutputStream()
        val started = System.currentTimeMillis()
        var error: String? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = 180
            val input = socket.getInputStream()
            val buf = ByteArray(8192)
            val deadline = started + durationMs
            while (System.currentTimeMillis() < deadline && acc.size() < maxBytes) {
                try {
                    val n = input.read(buf)
                    if (n > 0) {
                        val allowed = minOf(n, maxBytes - acc.size())
                        acc.write(buf, 0, allowed)
                    } else if (n < 0) {
                        break
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Expected: keep listening until the requested capture window ends.
                }
            }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }

        val raw = acc.toByteArray()
        return CaptureResult(
            port = port,
            durationMs = System.currentTimeMillis() - started,
            byteCount = raw.size,
            frames = parseFrames(raw),
            error = error
        )
    }

    /**
     * Human-readable audit of the exact fcc.json bundled with the installed app.
     * Read-only: no socket and no writes.
     */
    fun auditFccProfile(context: Context): String {
        val text = context.assets.open("profiles/fcc.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(text)
        val frames = obj.getJSONArray("frames")
        return buildString {
            appendLine("FCC profile audit")
            appendLine("Name: ${obj.optString("name", "fcc.json")}")
            appendLine("Description: ${obj.optString("description", "")}")
            appendLine("Sender: ${obj.optInt("sender")}")
            appendLine("Cmd type: ${obj.optInt("cmd_type")}")
            appendLine("Rounds: ${obj.optInt("rounds", 1)}")
            appendLine("Inter-frame: ${obj.optLong("inter_frame_delay_ms", 0)} ms")
            appendLine("Inter-round: ${obj.optLong("inter_round_delay_ms", 0)} ms")
            appendLine("Read window: ${obj.optInt("read_window_ms", 80)} ms")
            appendLine("Frames/round: ${frames.length()}")
            appendLine("Total sends: ${frames.length() * obj.optInt("rounds", 1)}")
            appendLine()
            for (i in 0 until frames.length()) {
                val f = frames.getJSONObject(i)
                append("%02d. set=%-3d id=%-3d dst=%-3d p=%s".format(
                    Locale.US,
                    i + 1,
                    f.optInt("s"),
                    f.optInt("i"),
                    f.optInt("d"),
                    f.optString("p", "")
                ))
                val note = f.optString("note", "")
                if (note.isNotBlank()) append("  // $note")
                appendLine()
            }
        }
    }

    /**
     * Applies the installed FCC profile, but uses sendAndReceive() so every
     * request is checked for a structurally valid matching DUML response.
     *
     * IMPORTANT: this is an active radio-region write. It is equivalent in
     * intent to tapping Enable FCC, with added diagnostics. It does not send
     * any command that is not already present in the installed fcc.json.
     */
    fun applyFccWithAckCheck(context: Context, onProgress: (Float) -> Unit = {}): AckCheckResult {
        val transport = DumlTransport()
        val profile = try {
            Profiles.load(context, "fcc.json")
        } catch (e: Exception) {
            return AckCheckResult(-1, 0, 0, 0, emptyList(), "Profile load failed: ${e.message}")
        }

        if (!transport.connect()) {
            return AckCheckResult(-1, 0, 0, 0, emptyList(), "DUML proxy is not reachable")
        }
        val port = if (profile.port == DumlTransport.PORT) {
            transport.getDetectedPort().takeIf { it > 0 } ?: DumlTransport.PORT
        } else profile.port

        val total = profile.frames.size * profile.rounds
        var done = 0
        var acked = 0
        val detail = mutableListOf<String>()
        val started = System.currentTimeMillis()

        try {
            repeat(profile.rounds) { round ->
                profile.frames.forEachIndexed { index, frame ->
                    val cmdSet = if (frame.size > 10) frame[9].toInt() and 0xFF else -1
                    val cmdId = if (frame.size > 10) frame[10].toInt() and 0xFF else -1
                    val dst = if (frame.size > 5) frame[5].toInt() and 0xFF else -1
                    val t0 = System.nanoTime()
                    val response = transport.sendAndReceive(
                        frame = frame,
                        readWindowMs = maxOf(profile.readWindowMs, 80),
                        port = port
                    )
                    val latencyMs = (System.nanoTime() - t0) / 1_000_000.0
                    val ok = response != null
                    if (ok) acked++
                    detail += "R${round + 1} F${index + 1} set=$cmdSet id=$cmdId dst=$dst ${if (ok) "ACK" else "NO_ACK"} ${"%.1f".format(Locale.US, latencyMs)}ms" +
                        if (ok && response!!.isNotEmpty()) " payload=${response.take(24).joinToString("") { "%02X".format(it) }}" else ""
                    done++
                    onProgress(if (total > 0) done.toFloat() / total else 1f)
                    if (profile.interFrameDelay > 0) Thread.sleep(profile.interFrameDelay)
                }
                if (round < profile.rounds - 1 && profile.interRoundDelay > 0) {
                    Thread.sleep(profile.interRoundDelay)
                }
            }
        } catch (e: Exception) {
            return AckCheckResult(
                port = port,
                attempts = done,
                validAcks = acked,
                elapsedMs = System.currentTimeMillis() - started,
                details = detail,
                error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            )
        }

        return AckCheckResult(
            port = port,
            attempts = done,
            validAcks = acked,
            elapsedMs = System.currentTimeMillis() - started,
            details = detail
        )
    }

    fun buildHeader(context: Context, controllerModel: String, aircraftSerial: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("FreeFCC Lito X1 Lab report")
            appendLine("Generated: $stamp")
            appendLine("App package: ${context.packageName}")
            appendLine("Controller: ${controllerModel.ifBlank { "unknown" }}")
            val maskedSerial = when {
                aircraftSerial.isBlank() -> "not detected"
                aircraftSerial.length <= 8 -> "***"
                else -> aircraftSerial.take(4) + "…" + aircraftSerial.takeLast(4)
            }
            appendLine("Aircraft S/N: $maskedSerial")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.DEVICE}")
            appendLine()
        }
    }

    private fun canConnect(port: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
            true
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /** Parses direct DUML frames and 55 CC 30 75 wrapped frames from a byte stream. */
    internal fun parseFrames(data: ByteArray): List<ObservedFrame> {
        val out = mutableListOf<ObservedFrame>()
        var i = 0
        while (i < data.size) {
            // DJI wrapper: 55 CC 30 75 + uint32 LE inner length + inner DUML frame.
            if (i + 8 <= data.size &&
                data[i] == 0x55.toByte() &&
                data[i + 1] == 0xCC.toByte() &&
                data[i + 2] == 0x30.toByte() &&
                data[i + 3] == 0x75.toByte()
            ) {
                val innerLen = readUInt32LE(data, i + 4)
                val innerStart = i + 8
                if (innerLen in 13..1023 && innerStart + innerLen <= data.size) {
                    parseOne(data, innerStart, wrapped = true)?.let(out::add)
                    i = innerStart + innerLen
                    continue
                }
            }

            if (data[i] == 0x55.toByte()) {
                val parsed = parseOne(data, i, wrapped = false)
                if (parsed != null) {
                    out += parsed
                    val len = encodedLength(data, i)
                    i += if (len >= 13) len else 1
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun parseOne(data: ByteArray, offset: Int, wrapped: Boolean): ObservedFrame? {
        if (offset + 13 > data.size || data[offset] != 0x55.toByte()) return null
        val len = encodedLength(data, offset)
        if (len !in 13..1023 || offset + len > data.size) return null

        val frame = data.copyOfRange(offset, offset + len)
        val headerCrcOk = DumlBuilder.crc8(frame, 0, 3) == (frame[3].toInt() and 0xFF)
        val expected16 = DumlBuilder.crc16(frame, 0, len - 2)
        val actual16 = (frame[len - 2].toInt() and 0xFF) or ((frame[len - 1].toInt() and 0xFF) shl 8)
        val payload = if (len > 13) frame.copyOfRange(11, len - 2) else ByteArray(0)

        return ObservedFrame(
            offset = offset,
            wrapped = wrapped,
            validCrc = headerCrcOk && expected16 == actual16,
            sender = frame[4].toInt() and 0xFF,
            dst = frame[5].toInt() and 0xFF,
            sequence = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8),
            cmdType = frame[8].toInt() and 0xFF,
            cmdSet = frame[9].toInt() and 0xFF,
            cmdId = frame[10].toInt() and 0xFF,
            payload = payload
        )
    }

    private fun encodedLength(data: ByteArray, offset: Int): Int {
        if (offset + 3 > data.size) return -1
        return (data[offset + 1].toInt() and 0xFF) or
            ((data[offset + 2].toInt() and 0x03) shl 8)
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
