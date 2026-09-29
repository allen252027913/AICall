package me.fleey.ppocrv5.call

import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Process-owned resource, prepared ahead of its first use. */
class PreloadedResource<T : Any>(private val executor: Executor, private val factory: () -> T) {
  private val initializationLock = Any()
  private val usageLock = Any()
  private var initialization: FutureTask<T>? = null

  /** Only queues work; never waits for model loading on the Activity thread. */
  fun preload() { initializationTask() }

  /** Call from a worker. Sharing an instance also serializes its native operations. */
  fun <R> withResource(block: (T) -> R): R {
    val resource = try {
      initializationTask().get()
    } catch (error: ExecutionException) {
      throw (error.cause ?: error)
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
      throw error
    }
    return synchronized(usageLock) { block(resource) }
  }

  private fun initializationTask(): FutureTask<T> = synchronized(initializationLock) {
    initialization?.let { return@synchronized it }
    val task = FutureTask {
      try {
        factory()
      } catch (error: Throwable) {
        synchronized(initializationLock) { initialization = null }
        throw error
      }
    }
    initialization = task
    try {
      executor.execute(task)
    } catch (error: RuntimeException) {
      initialization = null
      throw error
    }
    task
  }
}
