package com.jinof.apm

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue

/** Only pixel preparation and HTTP run in workers. All callbacks run on the calling coordinator. */
internal class ParallelAnnotationRunner(private val concurrency: Int) {
    init { require(concurrency in 1..8) }

    private data class Event<R>(val index: Int, val stage: String? = null, val result: Result<R>? = null)

    fun <T, R> run(
        items: List<T>,
        onStart: (T) -> Unit,
        work: (T, (String) -> Unit) -> R,
        onStage: (T, String) -> Unit,
        onComplete: (T, Result<R>) -> Unit,
    ) {
        if (items.isEmpty()) return
        val events = LinkedBlockingQueue<Event<R>>()
        val workers = Executors.newFixedThreadPool(minOf(concurrency, items.size))
        val pending = mutableMapOf<Int, Future<*>>()
        var next = 0
        fun dispatch() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val index = next++
            val item = items[index]
            onStart(item)
            pending[index] = workers.submit {
                val result = runCatching {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    work(item) { stage -> events.put(Event(index, stage = stage)) }
                }
                events.offer(Event(index, result = result))
            }
        }
        try {
            repeat(minOf(concurrency, items.size)) { dispatch() }
            while (pending.isNotEmpty()) {
                val event = events.take()
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val item = items[event.index]
                if (event.stage != null) onStage(item, event.stage)
                else {
                    onComplete(item, requireNotNull(event.result))
                    pending.remove(event.index)
                    if (next < items.size) dispatch()
                }
            }
        } finally {
            pending.values.forEach { it.cancel(true) }
            workers.shutdownNow()
        }
    }
}
