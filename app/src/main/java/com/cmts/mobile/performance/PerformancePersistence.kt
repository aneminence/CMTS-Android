package com.cmts.mobile.performance

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Serial disk operations, independent of both the capture thread and the UI/engine lock. */
internal class PerformancePersistence(
    private val repository: PerformanceRepository,
    private val onFailure: (Exception) -> Unit,
    sampleCapacity: Int = 4096,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "cmts-performance-io").apply { isDaemon = true } },
) : AutoCloseable {
    private val slots = Semaphore(sampleCapacity)

    class Pending internal constructor(val id: String, val dir: File) {
        internal val handle = CompletableFuture<AttemptHandle>()
        internal val failure = AtomicReference<Exception?>(null)
    }

    fun create(config: PerformanceConfig): Pending {
        val id = UUID.randomUUID().toString()
        val pending = Pending(id, repository.attemptPath(id))
        worker.execute {
            try { pending.handle.complete(repository.createAttempt(config, id)) }
            catch (e: Exception) { pending.handle.completeExceptionally(e); report(pending, e) }
        }
        return pending
    }

    fun append(pending: Pending, sample: SpeedSample) {
        pending.failure.get()?.let { throw it }
        if (!slots.tryAcquire()) {
            val e = IOException("速度样本写入队列已满，该次尝试不能发布完整成绩")
            report(pending, e)
            throw e
        }
        worker.execute {
            try {
                if (pending.failure.get() == null) repository.appendSample(pending.handle.join(), sample)
            } catch (e: Exception) { report(pending, e) }
            finally { slots.release() }
        }
    }

    /** Control work is never rejected because the sample backlog is full. */
    fun finish(pending: Pending, result: PerformanceResult?, onSaved: () -> Unit) {
        worker.execute {
            var handle: AttemptHandle? = null
            try {
                handle = pending.handle.join()
                pending.failure.get()?.let { throw it }
                if (result != null) { repository.writeResult(handle, result); onSaved() }
                else repository.closeSamples(handle)
            } catch (e: Exception) {
                report(pending, e)
                handle?.let { runCatching { repository.closeSamples(it) }.onFailure { closeError -> e.addSuppressed(closeError) } }
            }
        }
    }

    fun discard(pending: Pending, onRemoved: () -> Unit) {
        worker.execute {
            try { if (repository.discardAttempt(pending.handle.join())) onRemoved() }
            catch (e: Exception) { report(pending, e) }
        }
    }

    private fun report(pending: Pending, e: Exception) {
        if (pending.failure.compareAndSet(null, e)) onFailure(e)
    }

    override fun close() {
        worker.shutdown()
        check(worker.awaitTermination(30, TimeUnit.SECONDS)) { "performance persistence did not finish" }
    }
}
