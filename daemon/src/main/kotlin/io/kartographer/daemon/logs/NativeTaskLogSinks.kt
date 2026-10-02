package io.kartographer.daemon.logs

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.WinNT
import io.kartographer.core.logs.TaskLogRecord
import io.kartographer.core.logs.TaskLogSink
import java.nio.file.Files
import java.nio.file.Paths

/**
 * OS-native task-log sinks.
 *
 * Both are best-effort: availability is probed before use, and [TaskLogObserver] contains any
 * error so a native logging failure never affects a run. The message on every backend is the
 * exact same line the file store writes, so grep and documentation stay consistent.
 */

/**
 * Forwards records to systemd-journald via `sd_journal_sendv`.
 *
 * This is the only way to attach structured fields (`KARTO_RUN_ID`, `KARTO_TASK`, ...) on Linux;
 * journald's native protocol is a datagram unix socket that the JDK cannot write to directly.
 */
class JournaldSink private constructor(private val systemd: Systemd) : TaskLogSink {

    override fun write(record: TaskLogRecord) {
        val fields = mutableListOf(
            "MESSAGE=${record.format()}",
            "SYSLOG_IDENTIFIER=karto",
            "PRIORITY=${if (record.failed) 3 else 6}",
            "KARTO_RUN_ID=${record.runId}",
            "KARTO_TASK=${record.task}",
            "KARTO_INVOCATION_ID=${record.invocationId}",
            "KARTO_STREAM=${record.stream.label}"
        )
        record.exitCode?.let { fields.add("KARTO_EXIT_CODE=$it") }
        record.durationMs?.let { fields.add("KARTO_DURATION_MS=$it") }
        send(fields)
    }

    override fun close() = Unit

    private fun send(fields: List<String>) {
        // journald's native datagram protocol separates fields with a newline (not NUL); each
        // field is a `KEY=value\n` byte array. The Memory instances must outlive the native
        // call, hence the list.
        val buffers = fields.map { field ->
            val bytes = "$field\n".toByteArray(Charsets.UTF_8)
            Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
        }
        @Suppress("UNCHECKED_CAST")
        val iovecs = Iovec().toArray(fields.size) as Array<Iovec>
        for (i in fields.indices) {
            iovecs[i].iov_base = buffers[i]
            iovecs[i].iov_len = NativeLong(buffers[i].size())
            iovecs[i].write()
        }
        // TODO: check the negative-errno return and throw, so TaskLogObserver's one-time warning surfaces dropped journald records.
        systemd.sd_journal_sendv(iovecs, fields.size)
    }

    interface Systemd : Library {
        fun sd_journal_sendv(iov: Array<Iovec>, n: Int): Int
    }

    @Structure.FieldOrder("iov_base", "iov_len")
    class Iovec : Structure {
        @JvmField var iov_base: Pointer? = null
        @JvmField var iov_len: NativeLong = NativeLong(0)

        constructor() : super()
        constructor(base: Pointer, len: Long) : super() {
            iov_base = base
            iov_len = NativeLong(len)
        }
    }

    companion object {
        private const val SOCKET = "/run/systemd/journal/socket"

        /** The library loads and journald's datagram socket is present. */
        fun isAvailable(): Boolean {
            if (!Files.exists(Paths.get(SOCKET))) return false
            return runCatching { load() }.isSuccess
        }

        fun open(): JournaldSink? =
            if (!isAvailable()) null else runCatching { JournaldSink(load()) }.getOrNull()

        private fun load(): Systemd = Native.load("systemd", Systemd::class.java)
    }
}

/**
 * Forwards records to the Windows Event Log under the `Kartographer` source.
 *
 * The source is not registered (no message DLL), so Windows shows a "description not found"
 * preamble; the message text is still present. A registry/message-DLL installer is future work.
 */
class EventLogSink private constructor(private val handle: WinNT.HANDLE) : TaskLogSink {

    override fun write(record: TaskLogRecord) {
        val type = if (record.failed) WinNT.EVENTLOG_ERROR_TYPE else WinNT.EVENTLOG_INFORMATION_TYPE
        Advapi32.INSTANCE.ReportEvent(handle, type, 0, 0, null, 1, 0, arrayOf(record.format()), null)
    }

    override fun close() {
        runCatching { Advapi32.INSTANCE.DeregisterEventSource(handle) }
    }

    companion object {
        private const val SOURCE = "Kartographer"

        /** Windows and the source can be opened. */
        fun isAvailable(): Boolean {
            if (!isWindows()) return false
            return runCatching {
                val handle = Advapi32.INSTANCE.RegisterEventSource(null, SOURCE)
                if (isValid(handle)) {
                    Advapi32.INSTANCE.DeregisterEventSource(handle)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
        }

        fun open(): EventLogSink? {
            if (!isWindows()) return null
            return runCatching {
                val handle = Advapi32.INSTANCE.RegisterEventSource(null, SOURCE)
                if (isValid(handle)) EventLogSink(handle) else null
            }.getOrNull()
        }

        private fun isValid(handle: WinNT.HANDLE?): Boolean =
            handle != null && handle.pointer != Pointer.NULL

        private fun isWindows(): Boolean =
            System.getProperty("os.name")?.lowercase()?.contains("win") == true
    }
}
