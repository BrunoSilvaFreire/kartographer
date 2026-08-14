package io.kartographer.core.daemon

import io.kartographer.daemon.proto.LocalRequest
import io.kartographer.daemon.proto.LocalResponse
import java.io.Closeable
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * Length-prefixed protobuf frames over a plain JDK Unix socket.
 *
 * This exists because gRPC is too expensive to *start*. Measured on a warm daemon, a CLI
 * invocation spent ~276 ms in Netty's epoll native load, ~111 ms building the channel and
 * ~243 ms on the first RPC — against ~23 ms of actual work on the daemon. Framing the same
 * generated messages ourselves over `java.nio` costs ~60 ms and needs no native library, no
 * event loop and no thread pool.
 *
 * gRPC is still the transport for genuinely remote workers over TCP, where connection setup
 * is amortised and its features earn their cost.
 */
object LocalProtocol {

    /** Frames larger than this indicate a desynchronised stream rather than a real message. */
    private const val MAX_FRAME_BYTES = 64 * 1024 * 1024

    fun writeFrame(channel: SocketChannel, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(payload)
        buffer.flip()
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    /** Returns null on a clean end of stream, meaning the peer closed between frames. */
    fun readFrame(channel: SocketChannel): ByteArray? {
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        if (!readFully(channel, header)) return null

        val length = header.flip().int
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IllegalStateException("Refusing a $length byte frame; the stream is out of sync")
        }

        val payload = ByteBuffer.allocate(length)
        // A partial frame is a truncation, not a clean shutdown — distinguish the two so a
        // killed daemon reads as an error rather than as an empty answer.
        if (!readFully(channel, payload)) throw EOFException("Connection closed mid-frame")
        return payload.array()
    }

    private fun readFully(channel: SocketChannel, buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) return !buffer.hasRemaining()
        }
        return true
    }

    /**
     * A client connection to the local daemon. One instance is one conversation; close it
     * when the command is done.
     */
    class Client(private val channel: SocketChannel) : Closeable {

        fun send(request: LocalRequest) = writeFrame(channel, request.toByteArray())

        /** Null when the daemon closed the connection, which ends a streaming response. */
        fun receive(): LocalResponse? = readFrame(channel)?.let { LocalResponse.parseFrom(it) }

        /** Sends one request and reads its single response. */
        fun call(request: LocalRequest): LocalResponse {
            send(request)
            return receive() ?: throw EOFException("Daemon closed the connection without responding")
        }

        /** Signals "no more requests" while leaving the response direction open. */
        fun halfClose() {
            runCatching { channel.shutdownOutput() }
        }

        override fun close() {
            runCatching { channel.close() }
        }

        companion object {
            fun connect(socketPath: Path): Client {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                return try {
                    channel.connect(UnixDomainSocketAddress.of(socketPath))
                    Client(channel)
                } catch (e: Exception) {
                    runCatching { channel.close() }
                    throw e
                }
            }
        }
    }
}
