package app.rly3h.yakumo.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class ModelManifest(val models: List<ModelSpec>)

@Serializable
data class ModelSpec(
  val id: String,
  val dir: String,
  val checkFile: String,
  val files: List<FileSpec> = emptyList(),
  val archive: ArchiveSpec? = null,
)

@Serializable data class FileSpec(val name: String, val url: String)

@Serializable data class ArchiveSpec(val url: String, val format: String)

/** Thrown by [Models.ensure] when a `cancel` callback returns true mid-transfer. */
class ModelCancelled : Exception("download cancelled")

/**
 * First-run model provisioning: reads assets/models.json and downloads each
 * model into internal storage (filesDir). Replaces the old adb-push workflow.
 * Internal storage is required because the NDK's raw open() is denied on
 * external Android/data on some OEMs (see docs/architecture.md §12).
 */
object Models {
  private val json = Json { ignoreUnknownKeys = true }

  fun manifest(context: Context): ModelManifest {
    val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
    return json.decodeFromString(text)
  }

  fun spec(context: Context, id: String): ModelSpec =
    manifest(context).models.first { it.id == id }

  fun dir(context: Context, id: String): File =
    File(context.filesDir, spec(context, id).dir)

  fun isPresent(context: Context, id: String): Boolean {
    val s = spec(context, id)
    return File(File(context.filesDir, s.dir), s.checkFile).exists()
  }

  /**
   * Downloads/extracts the model if missing. Blocking — call from an IO thread.
   * `cancel` is polled during transfer/extraction; returning true aborts with
   * [ModelCancelled] and removes the partial download.
   */
  fun ensure(
    context: Context,
    id: String,
    onProgress: (String) -> Unit = {},
    cancel: () -> Boolean = { false },
  ) {
    val s = spec(context, id)
    val target = File(File(context.filesDir, s.dir), s.checkFile)
    if (target.exists()) {
      onProgress("$id: present")
      return
    }
    val archive = s.archive
    if (archive != null) {
      // tar.bz2 contains a top-level folder == s.dir. Extract into a staging dir
      // first and only swap it into place once complete, so a cancelled/failed
      // extraction never leaves a half-written model that looks "present".
      val tmp = File(context.cacheDir, "$id-archive")
      val staging = File(context.filesDir, ".staging-$id")
      onProgress("$id: downloading…")
      download(archive.url, tmp, cancel) { b -> onProgress("$id: ${b / 1_000_000} MB") }
      onProgress("$id: extracting…")
      try {
        staging.deleteRecursively()
        staging.mkdirs()
        extractTarBz2(tmp, staging, cancel) { b -> onProgress("$id: extracting… ${b / 1_000_000} MB") }
        val extracted = File(staging, s.dir)
        check(extracted.isDirectory) { "archive missing top-level dir ${s.dir}" }
        val finalDir = File(context.filesDir, s.dir)
        finalDir.deleteRecursively()
        check(extracted.renameTo(finalDir)) { "failed to move ${s.dir} into place" }
      } finally {
        staging.deleteRecursively()
        tmp.delete()
      }
    } else {
      val dir = File(context.filesDir, s.dir).apply { mkdirs() }
      for (f in s.files) {
        // Skip files already on disk so adding one new file (e.g. a swapped
        // decoder) doesn't re-download the unchanged ones.
        if (File(dir, f.name).exists()) {
          onProgress("$id: ${f.name} present")
          continue
        }
        onProgress("$id: ${f.name}…")
        download(f.url, File(dir, f.name), cancel) { b -> onProgress("$id: ${f.name} ${b / 1_000_000} MB") }
      }
    }
    onProgress("$id: done")
  }

  // Streaming download that manually follows redirects (HF/GitHub CDN hops).
  private fun download(urlStr: String, dest: File, cancel: () -> Boolean, onProgress: (Long) -> Unit) {
    var url = urlStr
    var hops = 0
    while (true) {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 30_000
        readTimeout = 60_000
      }
      val code = conn.responseCode
      if (code in 300..399) {
        val loc = conn.getHeaderField("Location") ?: error("redirect without Location")
        conn.disconnect()
        url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
        if (++hops > 8) error("too many redirects")
        continue
      }
      if (code != 200) {
        conn.disconnect()
        error("HTTP $code for $url")
      }
      val part = File(dest.parentFile, dest.name + ".part")
      var cancelled = false
      conn.inputStream.use { input ->
        part.outputStream().use { out ->
          val buf = ByteArray(1 shl 16)
          var total = 0L
          while (true) {
            if (cancel()) {
              cancelled = true
              break
            }
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            onProgress(total)
          }
        }
      }
      conn.disconnect()
      if (cancelled) {
        part.delete()
        throw ModelCancelled()
      }
      if (dest.exists()) dest.delete()
      check(part.renameTo(dest)) { "rename ${part.name} -> ${dest.name} failed" }
      return
    }
  }

  private fun extractTarBz2(
    archive: File,
    destDir: File,
    cancel: () -> Boolean,
    onProgress: (Long) -> Unit,
  ) {
    val destRoot = destDir.canonicalFile
    var written = 0L
    archive.inputStream().buffered().use { fin ->
      BZip2CompressorInputStream(fin).use { bz ->
        TarArchiveInputStream(bz).use { tar ->
          var entry = tar.nextEntry
          while (entry != null) {
            if (cancel()) throw ModelCancelled()
            val out = File(destDir, entry.name).canonicalFile
            require(out.path.startsWith(destRoot.path)) { "tar entry escapes dest: ${entry.name}" }
            if (entry.isDirectory) {
              out.mkdirs()
            } else {
              out.parentFile?.mkdirs()
              out.outputStream().use { os ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                  if (cancel()) throw ModelCancelled()
                  val n = tar.read(buf)
                  if (n < 0) break
                  os.write(buf, 0, n)
                  written += n
                  onProgress(written)
                }
              }
            }
            entry = tar.nextEntry
          }
        }
      }
    }
  }
}
