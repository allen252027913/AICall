package me.fleey.ppocrv5.call

import java.io.File
import java.io.OutputStream

class GalleryCopyTransaction<T>(
  private val create: () -> T,
  private val open: (T) -> OutputStream,
  private val publish: (T) -> Unit,
  private val remove: (T) -> Unit,
) {
  fun copy(source: File): T = source.inputStream().use { input ->
    val destination = create()
    try {
      open(destination).use { output -> input.copyTo(output) }
      publish(destination)
      destination
    } catch (error: Throwable) {
      try { remove(destination) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
      throw error
    }
  }
}
