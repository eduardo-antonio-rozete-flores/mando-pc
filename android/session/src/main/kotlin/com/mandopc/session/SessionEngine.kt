package com.mandopc.session

import com.mandopc.contracts.PlayerState

fun interface FrameSink {
    fun send(frame: ByteArray)
}

enum class SessionPhase {
    Disconnected,
    Handshaking,
    Streaming,
    Error,
}

class SessionEngine(
    private val uuid: ByteArray,
    private val sink: FrameSink,
    private val model: String = "Android",
    private val capabilities: Int = 0b1111,
    private val protoMin: Int = FrameCodec.VERSION,
    private val protoMax: Int = FrameCodec.VERSION,
    rateHz: Int = 1000,
    private val pingIntervalUs: Long = 1_000_000L,
    private val helloRetryUs: Long = 250_000L,
    private val helloTimeoutUs: Long = 2_000_000L,
) {

    init {
        require(uuid.size == 16) { "uuid must be 16 bytes" }
        require(rateHz in 1..1000) { "rateHz must be in 1..1000" }
        require(pingIntervalUs > 0) { "pingIntervalUs must be positive" }
        require(helloRetryUs > 0) { "helloRetryUs must be positive" }
        require(helloTimeoutUs > 0) { "helloTimeoutUs must be positive" }
    }

    private val inputIntervalUs: Long = 1_000_000L / rateHz

    var phase: SessionPhase = SessionPhase.Disconnected
        private set

    var sessionId: Int = 0
        private set

    var assignedSlot: Int = 0
        private set

    var rttMs: Int = 0
        private set

    var hostName: String = ""
        private set

    var hostProto: Int = 0
        private set

    var maxPlayers: Int = 0
        private set

    var lastError: String? = null
        private set

    var sequence: Int = 0
        private set

    var requestedSlot: Int = 0
        private set

    var onRumble: ((Rumble) -> Unit)? = null

    private var nextHelloAtUs: Long = 0L
    private var helloDeadlineUs: Long = 0L
    private var lastInputAtUs: Long = 0L
    private var lastPingAtUs: Long = 0L
    private var retryAtUs: Long = 0L
    private var hasSentInput: Boolean = false

    fun setRequestedSlot(slot: Int, nowUs: Long) {
        require(slot in 0..4) { "slot must be 0..4" }
        if (requestedSlot == slot) return
        requestedSlot = slot
        if (phase != SessionPhase.Disconnected) beginHandshake(nowUs)
    }

    fun beginHandshake(nowUs: Long) {
        phase = SessionPhase.Handshaking
        sessionId = 0
        assignedSlot = 0
        rttMs = 0
        hostName = ""
        hostProto = 0
        maxPlayers = 0
        lastError = null
        sequence = 0
        hasSentInput = false
        lastInputAtUs = 0L
        lastPingAtUs = 0L
        retryAtUs = 0L
        helloDeadlineUs = nowUs + helloTimeoutUs
        sendHello(nowUs)
    }

    fun disconnect() {
        if (phase == SessionPhase.Streaming) {
            runCatching {
                sink.send(FrameCodec.buildBye(BYE_REASON_APP_CLOSED, sessionId, sequence))
            }
        }
        phase = SessionPhase.Disconnected
        sessionId = 0
        assignedSlot = 0
        rttMs = 0
        hostName = ""
        hostProto = 0
        maxPlayers = 0
        lastError = null
        sequence = 0
        hasSentInput = false
        retryAtUs = 0L
    }

    fun tick(nowUs: Long, state: PlayerState) {
        when (phase) {
            SessionPhase.Handshaking -> {
                if (nowUs >= helloDeadlineUs) {
                    fail("HELLO timeout", nowUs, retry = true)
                    return
                }
                if (nowUs >= nextHelloAtUs) sendHello(nowUs)
            }

            SessionPhase.Streaming -> {
                if (!hasSentInput || nowUs - lastInputAtUs >= inputIntervalUs) {
                    sendInput(nowUs, state)
                    hasSentInput = true
                    lastInputAtUs = nowUs
                }
                if (nowUs - lastPingAtUs >= pingIntervalUs) {
                    sink.send(FrameCodec.buildPing(nowUs, sessionId, 0))
                    lastPingAtUs = nowUs
                }
            }

            SessionPhase.Error -> {
                if (retryAtUs > 0L && nowUs >= retryAtUs) beginHandshake(nowUs)
            }

            SessionPhase.Disconnected -> Unit
        }
    }

    fun onFrame(frame: ByteArray, nowUs: Long) {
        val parsed = try {
            FrameCodec.parse(frame)
        } catch (_: IllegalArgumentException) {
            return
        }
        when (parsed.type) {
            FrameCodec.HELLO_ACK -> {
                if (phase == SessionPhase.Handshaking) handleHelloAck(parsed, nowUs)
            }

            FrameCodec.PONG -> {
                if (phase == SessionPhase.Streaming && parsed.sessionId == sessionId) {
                    handlePong(parsed, nowUs)
                }
            }

            FrameCodec.RUMBLE -> {
                if (phase == SessionPhase.Streaming && parsed.sessionId == sessionId) {
                    onRumble?.invoke(FrameCodec.parseRumble(parsed))
                }
            }

            FrameCodec.ERROR -> {
                phase = SessionPhase.Error
                lastError = "host error"
                retryAtUs = 0L
            }

            FrameCodec.BYE -> {
                phase = SessionPhase.Disconnected
                sessionId = 0
                assignedSlot = 0
            }
        }
    }

    private fun sendHello(nowUs: Long) {
        sink.send(
            FrameCodec.buildHello(
                timestampUs = nowUs,
                uuid = uuid,
                requestedSlot = requestedSlot,
                capabilities = capabilities,
                model = model,
                protoMin = protoMin,
                protoMax = protoMax,
            ),
        )
        nextHelloAtUs = nowUs + helloRetryUs
    }

    private fun handleHelloAck(frame: Frame, nowUs: Long) {
        val ack = FrameCodec.parseHelloAck(frame)
        if (ack.status != STATUS_OK) {
            when (ack.status) {
                STATUS_SLOT_BUSY -> {
                    requestedSlot = 0
                    nextHelloAtUs = nowUs + helloRetryUs
                    helloDeadlineUs = nowUs + helloTimeoutUs
                }

                STATUS_VERSION_MISMATCH -> fail("versiones incompatibles", nowUs, retry = false)
                STATUS_HOST_FULL -> fail("host lleno", nowUs, retry = true)
                else -> fail("peticion invalida", nowUs, retry = false)
            }
            return
        }
        if (frame.sessionId == 0) {
            fail("HELLO_ACK sin session_id", nowUs, retry = false)
            return
        }
        if (ack.hostProto !in protoMin..protoMax) {
            fail("host_proto no soportado", nowUs, retry = false)
            return
        }
        if (ack.assignedSlot !in 1..4) {
            fail("slot invalido", nowUs, retry = false)
            return
        }
        sessionId = frame.sessionId
        assignedSlot = ack.assignedSlot
        hostProto = ack.hostProto
        maxPlayers = ack.maxPlayers
        hostName = ack.name
        lastError = null
        retryAtUs = 0L
        sequence = 1
        hasSentInput = false
        lastInputAtUs = 0L
        lastPingAtUs = nowUs
        phase = SessionPhase.Streaming
    }

    private fun handlePong(frame: Frame, nowUs: Long) {
        val pong = FrameCodec.parsePong(frame)
        val rttUs = nowUs - pong.clientTsUs
        if (rttUs < 0L) return
        rttMs = (rttUs / 1000L).coerceIn(0L, 255L).toInt()
    }

    private fun sendInput(nowUs: Long, state: PlayerState) {
        sink.send(
            FrameCodec.buildInput(
                timestampUs = nowUs,
                sessionId = sessionId,
                seq = sequence,
                state = state,
                rttMs = rttMs,
            ),
        )
        sequence += 1
    }

    private fun fail(message: String, nowUs: Long, retry: Boolean) {
        phase = SessionPhase.Error
        lastError = message
        retryAtUs = if (retry) nowUs + helloTimeoutUs else 0L
    }

    companion object {
        const val STATUS_OK: Int = 0
        const val STATUS_SLOT_BUSY: Int = 1
        const val STATUS_VERSION_MISMATCH: Int = 2
        const val STATUS_HOST_FULL: Int = 3
        const val STATUS_BAD_REQUEST: Int = 4

        const val BYE_REASON_NORMAL: Int = 0
        const val BYE_REASON_APP_CLOSED: Int = 1
    }
}
