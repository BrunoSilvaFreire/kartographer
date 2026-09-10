package io.kartographer.tasks.go

import io.kartographer.core.model.*

suspend fun <I : InputSet, O : OutputSet> Task<I, O>.execute(context: ExecutionContext): ExecutionResult<O> =
    execute(context, buildInputs(context))
