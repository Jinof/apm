package com.jinof.apm

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class ParallelAnnotationRunnerTest {
    @Test fun oneTwoAndEightWorkersOverlapWithoutExceedingBound() {
        for (limit in listOf(1, 2, 8)) {
            val entered = CountDownLatch(limit)
            val release = CountDownLatch(1)
            val running = AtomicInteger()
            val peak = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val completed = mutableMapOf<Int, Int>()
            val coordinator = thread {
                try {
                    val owner = Thread.currentThread()
                    ParallelAnnotationRunner(limit).run((0..16).toList(),
                        onStart = { assertSame(owner, Thread.currentThread()) },
                        work = { item, stage ->
                            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                            try {
                                entered.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                                stage("request")
                                Thread.sleep(5)
                                item * 2
                            } finally { running.decrementAndGet() }
                        },
                        onStage = { _, _ -> assertSame(owner, Thread.currentThread()) },
                        onComplete = { item, result ->
                            assertSame(owner, Thread.currentThread())
                            assertNull(completed.put(item, result.getOrThrow()))
                        })
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(limit, running.get())
            } finally { release.countDown() }
            coordinator.join(5000)
            assertFalse(coordinator.isAlive)
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(limit, peak.get())
            assertEquals((0..16).associateWith { it * 2 }, completed)
        }
    }

    @Test fun slowFirstResponseDoesNotBlockRefillAndFailureKeepsIdentity() {
        val releaseFirst = CountDownLatch(1)
        val laterDone = CountDownLatch(8)
        val order = mutableListOf<Int>()
        val values = mutableMapOf<Int, Result<Int>>()
        val failure = AtomicReference<Throwable?>()
        val coordinator = thread {
            try {
                ParallelAnnotationRunner(2).run((0..8).toList(), {}, { item, _ ->
                    if (item == 0) check(releaseFirst.await(5, TimeUnit.SECONDS))
                    if (item == 3) error("controlled failure")
                    item * 10
                }, { _, _ -> }, { item, result ->
                    order += item; values[item] = result
                    if (item != 0) laterDone.countDown()
                })
            } catch (error: Throwable) { failure.set(error) }
        }
        try { assertTrue(laterDone.await(5, TimeUnit.SECONDS)) }
        finally { releaseFirst.countDown() }
        coordinator.join(5000)
        failure.get()?.let { throw AssertionError(it) }
        assertFalse(coordinator.isAlive)
        assertEquals(0, order.last())
        assertEquals(9, values.size)
        assertTrue(values.getValue(3).isFailure)
        for (item in (0..8).filter { it != 3 }) assertEquals(item * 10, values.getValue(item).getOrThrow())
    }

    @Test fun cancellationStopsDispatchAndInterruptsInFlightWorkers() {
        val entered = CountDownLatch(2)
        val exited = CountDownLatch(2)
        val started = Collections.synchronizedList(mutableListOf<Int>())
        val completed = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val coordinator = thread {
            try {
                ParallelAnnotationRunner(2).run((0..20).toList(), { started += it }, { _, _ ->
                    entered.countDown()
                    try { CountDownLatch(1).await(); 1 } finally { exited.countDown() }
                }, { _, _ -> }, { _, _ -> completed.incrementAndGet() })
                failure.set(AssertionError("Expected cancellation"))
            } catch (_: InterruptedException) { }
            catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        coordinator.interrupt()
        coordinator.join(5000)
        assertFalse(coordinator.isAlive)
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(listOf(0, 1), started)
        assertEquals(0, completed.get())
    }
}
