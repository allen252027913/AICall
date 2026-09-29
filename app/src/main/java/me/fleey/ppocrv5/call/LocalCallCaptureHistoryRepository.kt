package me.fleey.ppocrv5.call

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CaptureHistoryEntry(
  val id: String,
  val capturedAtMillis: Long,
  val imageFileName: String,
  val ocrText: String,
  val sourcePackage: String,
)

/** A frame written to a private provisional file but not yet published in the history index. */
class StagedCaptureHistoryEntry internal constructor(
  internal val temporaryImage: File,
  internal val ocrText: String,
  internal val sourcePackage: String,
  internal val capturedAtMillis: Long,
)

/** An owned on-disk transaction which is still absent from the visible history index. */
class PreparedCaptureHistoryEntry internal constructor(
  internal val finalImage: File,
  internal val pendingIndex: File,
  internal val entry: CaptureHistoryEntry,
  internal val evictedEntries: List<CaptureHistoryEntry>,
  private val releaseTransaction: () -> Unit,
) {
  private val published = AtomicBoolean(false)
  private val released = AtomicBoolean(false)

  internal fun markPublished() {
    published.set(true)
  }

  internal fun isPublished(): Boolean = published.get()

  internal fun release() {
    if (released.compareAndSet(false, true)) releaseTransaction()
  }
}

/** A visible index update whose evicted image cleanup may run after publication. */
class CaptureHistoryPublication internal constructor(
  val entry: CaptureHistoryEntry,
  internal val evictedEntries: List<CaptureHistoryEntry>,
)

/** A bounded, app-private cache of successful screen captures. */
class LocalCallCaptureHistoryRepository(
  private val rootDirectory: File,
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
  private val jpegWriter: (Bitmap, File) -> Boolean = ::writeJpeg,
  private val metadataWriter: ((String) -> Unit)? = null,
) {
  private val preparedTransactionLock = ReentrantLock()

  init {
    require(maxEntries > 0) { "maxEntries must be positive" }
    ensureRootDirectory()
    recoverOwnedFiles()
  }

  fun save(
    bitmap: Bitmap,
    ocrText: String,
    sourcePackage: String,
    capturedAtMillis: Long = System.currentTimeMillis(),
  ): CaptureHistoryEntry {
    val staged = stage(bitmap, ocrText, sourcePackage, capturedAtMillis)
    return try {
      commit(staged)
    } catch (error: Throwable) {
      discard(staged)
      throw error
    }
  }

  /** Compresses a frame without making it visible to [load] or applying cache eviction. */
  fun stage(
    bitmap: Bitmap,
    ocrText: String,
    sourcePackage: String,
    capturedAtMillis: Long = System.currentTimeMillis(),
  ): StagedCaptureHistoryEntry {
    ensureRootDirectory()
    val temporaryImage = File.createTempFile(STAGED_IMAGE_PREFIX, STAGED_IMAGE_SUFFIX, rootDirectory)
    try {
      if (!jpegWriter(bitmap, temporaryImage) || temporaryImage.length() == 0L) {
        throw IOException("Unable to persist captured frame")
      }
    } catch (error: Throwable) {
      temporaryImage.delete()
      throw error
    }

    return StagedCaptureHistoryEntry(
      temporaryImage = temporaryImage,
      ocrText = ocrText.trim().ifBlank { EMPTY_OCR_TEXT },
      sourcePackage = sourcePackage.trim(),
      capturedAtMillis = capturedAtMillis,
    )
  }

  /**
   * Moves a staged image and writes the prospective index without changing the visible index.
   * A single prepared transaction owns the sequence until [publish] or [rollback] resolves it.
   */
  fun prepareCommit(staged: StagedCaptureHistoryEntry): PreparedCaptureHistoryEntry {
    preparedTransactionLock.lock()
    try {
      ensureRootDirectory()
      requireOwnedStagedFile(staged.temporaryImage)
      if (!staged.temporaryImage.isFile || staged.temporaryImage.length() == 0L) {
        throw IOException("Staged frame is no longer available")
      }
      val index = readIndex() ?: run {
        deleteManagedImages()
        HistoryIndex(nextSequence = 1L, entries = mutableListOf())
      }
      val sequence = index.nextSequence
      val imageFileName = "${staged.capturedAtMillis}_${sequence}.jpg"
      val imageFile = resolveImageFile(imageFileName) ?: throw IOException("Invalid image file name")
      replaceAtomically(staged.temporaryImage, imageFile)
      val entry = CaptureHistoryEntry(
        id = "${staged.capturedAtMillis}_$sequence",
        capturedAtMillis = staged.capturedAtMillis,
        imageFileName = imageFileName,
        ocrText = staged.ocrText,
        sourcePackage = staged.sourcePackage,
      )
      index.nextSequence = sequence + 1L
      index.entries += entry
      val evictedEntries = mutableListOf<CaptureHistoryEntry>()
      while (index.entries.size > maxEntries) evictedEntries += index.entries.removeAt(0)
      val pendingIndex = File(rootDirectory, "$INDEX_FILE_NAME.$sequence.pending")
      try {
        writePendingIndex(index, pendingIndex)
      } catch (error: Throwable) {
        pendingIndex.delete()
        imageFile.delete()
        throw error
      }
      return PreparedCaptureHistoryEntry(
        finalImage = imageFile,
        pendingIndex = pendingIndex,
        entry = entry,
        evictedEntries = evictedEntries,
        releaseTransaction = preparedTransactionLock::unlock,
      )
    } catch (error: Throwable) {
      preparedTransactionLock.unlock()
      throw error
    }
  }

  /**
   * Atomically makes a prepared frame visible in the index. Evicted images are retained until
   * [finishPublication], so a rejected transaction never destroys their recovery path.
   */
  fun publish(prepared: PreparedCaptureHistoryEntry): CaptureHistoryPublication {
    try {
      if (!preparedTransactionLock.isHeldByCurrentThread) {
        throw IllegalStateException("Prepared history transaction must publish on its owner thread")
      }
      if (!prepared.pendingIndex.isFile || !prepared.finalImage.isFile) {
        throw IOException("Prepared history transaction is no longer available")
      }
      replaceAtomically(prepared.pendingIndex, File(rootDirectory, INDEX_FILE_NAME))
      prepared.markPublished()
      return CaptureHistoryPublication(prepared.entry, prepared.evictedEntries)
    } finally {
      prepared.release()
    }
  }

  /** Completes a visible publication by reclaiming only images evicted by that publication. */
  fun finishPublication(publication: CaptureHistoryPublication) {
    publication.evictedEntries.forEach { evicted -> resolveImageFile(evicted.imageFileName)?.delete() }
  }

  /** Deletes an invisible prepared frame without changing the index or eviction order. */
  fun rollback(prepared: PreparedCaptureHistoryEntry): Boolean = try {
    if (prepared.isPublished()) {
      false
    } else {
      val pendingDeleted = !prepared.pendingIndex.exists() || prepared.pendingIndex.delete()
      val imageDeleted = !prepared.finalImage.exists() || prepared.finalImage.delete()
      pendingDeleted && imageDeleted
    }
  } finally {
    prepared.release()
  }

  /** Compatibility convenience for callers that do not need an externally controlled transaction. */
  fun commit(staged: StagedCaptureHistoryEntry): CaptureHistoryEntry {
    val prepared = prepareCommit(staged)
    return try {
      val publication = publish(prepared)
      finishPublication(publication)
      publication.entry
    } catch (error: Throwable) {
      rollback(prepared)
      throw error
    }
  }

  /** Deletes a provisional file without touching committed history or its eviction order. */
  @Synchronized
  fun discard(staged: StagedCaptureHistoryEntry): Boolean {
    requireOwnedStagedFile(staged.temporaryImage)
    return !staged.temporaryImage.exists() || staged.temporaryImage.delete()
  }

  fun delete(entry: CaptureHistoryEntry): Boolean = preparedTransactionLock.withLock {
    val index = readIndex() ?: return@withLock false
    val position = index.entries.indexOfFirst {
      it.id == entry.id && it.imageFileName == entry.imageFileName
    }
    if (position < 0) return@withLock false
    val removed = index.entries.removeAt(position)
    writeIndex(index)
    resolveImageFile(removed.imageFileName)?.delete()
    true
  }

  @Synchronized
  fun load(): List<CaptureHistoryEntry> = readIndex()?.entries.orEmpty()
    .filter { imageFile(it) != null }
    .sortedWith(compareByDescending<CaptureHistoryEntry> { it.capturedAtMillis }.thenByDescending { it.id })

  @Synchronized
  fun imageFile(entry: CaptureHistoryEntry): File? =
    resolveImageFile(entry.imageFileName)?.takeIf(File::isFile)

  private fun readIndex(): HistoryIndex? {
    val indexFile = File(rootDirectory, INDEX_FILE_NAME)
    if (!indexFile.isFile) return null
    return runCatching {
      val text = indexFile.readText()
      val version = VERSION_PATTERN.find(text)?.groupValues?.get(1)?.toIntOrNull()
      if (version != INDEX_VERSION) return null
      val nextSequence = NEXT_SEQUENCE_PATTERN.find(text)?.groupValues?.get(1)?.toLongOrNull()
        ?: return null
      val entries = ENTRY_PATTERN.findAll(text).mapNotNull { match ->
        val (id, capturedAt, imageName, ocrText, sourcePackage) = match.destructured
        val safeImage = resolveImageFile(imageName) ?: return@mapNotNull null
        CaptureHistoryEntry(
          id = decode(id),
          capturedAtMillis = capturedAt.toLong(),
          imageFileName = safeImage.name,
          ocrText = decode(ocrText).ifBlank { EMPTY_OCR_TEXT },
          sourcePackage = decode(sourcePackage),
        )
      }.sortedBy { it.id.substringAfterLast('_').toLongOrNull() ?: Long.MAX_VALUE }.toMutableList()
      HistoryIndex(nextSequence.coerceAtLeast(entries.size.toLong() + 1L), entries)
    }.getOrNull()
  }

  private fun writeIndex(index: HistoryIndex) {
    replaceIndexFile(indexJson(index))
  }

  private fun writePendingIndex(index: HistoryIndex, pendingIndex: File) {
    val json = indexJson(index)
    metadataWriter?.invoke(json)
    pendingIndex.writeText(json)
  }

  private fun indexJson(index: HistoryIndex): String = buildString {
    append("{\"version\":").append(INDEX_VERSION)
    append(",\"nextSequence\":").append(index.nextSequence)
    append(",\"entries\":[")
    index.entries.forEachIndexed { position, entry ->
      if (position > 0) append(',')
      append("{\"id\":\"").append(encode(entry.id))
      append("\",\"capturedAtMillis\":").append(entry.capturedAtMillis)
      append(",\"imageFileName\":\"").append(entry.imageFileName)
      append("\",\"ocrText\":\"").append(encode(entry.ocrText))
      append("\",\"sourcePackage\":\"").append(encode(entry.sourcePackage)).append("\"}")
    }
    append("]}")
  }

  private fun replaceIndexFile(json: String) {
    ensureRootDirectory()
    val temporaryIndex = File(rootDirectory, "$INDEX_FILE_NAME.tmp")
    temporaryIndex.writeText(json)
    replaceAtomically(temporaryIndex, File(rootDirectory, INDEX_FILE_NAME))
  }

  private fun deleteManagedImages() {
    rootDirectory.listFiles().orEmpty().forEach { file ->
      if (IMAGE_NAME_PATTERN.matches(file.name)) file.delete()
    }
  }

  private fun recoverOwnedFiles() {
    deleteOwnedPendingFiles()
    val indexedImages = readIndex()?.entries?.mapTo(mutableSetOf(), CaptureHistoryEntry::imageFileName)
      .orEmpty()
    rootDirectory.listFiles().orEmpty().forEach { file ->
      if (IMAGE_NAME_PATTERN.matches(file.name) && file.name !in indexedImages) file.delete()
    }
  }

  private fun deleteOwnedPendingFiles() {
    rootDirectory.listFiles().orEmpty().forEach { file ->
      if ((file.name.startsWith(STAGED_IMAGE_PREFIX) && file.name.endsWith(STAGED_IMAGE_SUFFIX)) ||
        (file.name.startsWith("$INDEX_FILE_NAME.") && file.name.endsWith(".pending"))
      ) {
        file.delete()
      }
    }
  }

  private fun resolveImageFile(name: String): File? = runCatching {
    if (!IMAGE_NAME_PATTERN.matches(name)) return null
    val root = rootDirectory.canonicalFile
    File(root, name).canonicalFile.takeIf { it.parentFile == root }
  }.getOrNull()

  private fun requireOwnedStagedFile(file: File) {
    val root = rootDirectory.canonicalFile
    val candidate = file.canonicalFile
    if (candidate.parentFile != root ||
      !candidate.name.startsWith(STAGED_IMAGE_PREFIX) ||
      !candidate.name.endsWith(STAGED_IMAGE_SUFFIX)
    ) {
      throw IOException("Invalid staged image file")
    }
  }

  private fun ensureRootDirectory() {
    if (!rootDirectory.exists() && !rootDirectory.mkdirs() && !rootDirectory.isDirectory) {
      throw IOException("Unable to create local call history directory")
    }
    if (!rootDirectory.isDirectory) throw IOException("Local call history path is not a directory")
  }

  private fun replaceAtomically(temporary: File, target: File) {
    try {
      Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
    } catch (error: IOException) {
      throw IOException("Unable to atomically replace ${target.name}", error)
    }
  }

  private data class HistoryIndex(
    var nextSequence: Long,
    val entries: MutableList<CaptureHistoryEntry>,
  )

  private companion object {
    const val INDEX_VERSION = 1
    const val INDEX_FILE_NAME = "index.json"
    const val DEFAULT_MAX_ENTRIES = 50
    const val EMPTY_OCR_TEXT = "No text recognized in this capture"
    const val JPEG_QUALITY = 85
    const val STAGED_IMAGE_PREFIX = "capture_"
    const val STAGED_IMAGE_SUFFIX = ".jpg.pending"
    val VERSION_PATTERN = Regex("\\\"version\\\"\\s*:\\s*(\\d+)")
    val NEXT_SEQUENCE_PATTERN = Regex("\\\"nextSequence\\\"\\s*:\\s*(\\d+)")
    val IMAGE_NAME_PATTERN = Regex("[0-9]+_[0-9]+\\.jpg")
    val ENTRY_PATTERN = Regex(
      """\{\"id\":\"([^\"]*)\",\"capturedAtMillis\":([0-9]+),\"imageFileName\":\"([^\"]*)\",\"ocrText\":\"([^\"]*)\",\"sourcePackage\":\"([^\"]*)\"\}""",
    )

    fun writeJpeg(bitmap: Bitmap, target: File): Boolean =
      FileOutputStream(target).use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream) }

    fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value))
  }
}
