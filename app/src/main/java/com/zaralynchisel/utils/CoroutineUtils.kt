package com.zaralynchisel.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Shared dispatchers for structured concurrency.
 * Batch operations use a dedicated thread pool to avoid starving the UI.
 */
object CoroutineDispatchers {
    val batch: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)
    val fileIO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
    val network: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
}

/**
 * Execute a suspend block on the file IO dispatcher.
 */
suspend fun <T> withFileIO(block: suspend () -> T): T =
    withContext(CoroutineDispatchers.fileIO) { block() }

/**
 * Execute a blocking operation on the batch dispatcher.
 */
suspend fun <T> withBatch(block: () -> T): T =
    withContext(CoroutineDispatchers.batch) { block() }