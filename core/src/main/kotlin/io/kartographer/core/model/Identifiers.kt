package io.kartographer.core.model

@JvmInline
value class TaskId(val value: String) {
    override fun toString() = value
}

@JvmInline
value class WorkerId(val value: String) {
    override fun toString() = value
}
