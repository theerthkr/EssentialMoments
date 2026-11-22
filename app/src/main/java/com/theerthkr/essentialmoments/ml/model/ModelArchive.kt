package com.theerthkr.essentialmoments.ml.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moves installed models in and out of the app sandbox as a single zip.
 *
 * The archive is `model-manifest.json` plus the three files listed by that
 * manifest. Import verifies the manifest, every file size, and every SHA-256
 * before anything is marked ready. Staged files are swapped into the live
 * version directory as a unit, so a failed import leaves the current models
 * untouched.
 *
 * This is deliberately a separate path from [ModelDownloadWorker]: it never
 * downloads, and it is only used when the user explicitly exports or imports.
 */
internal object ModelArchive {

    private const val TAG = "ModelArchive"
    private const val MANIFEST_ENTRY = "model-manifest.json"
    private const val BUFFER_SIZE = 128 * 1024
    private const val REPORT_INTERVAL = 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 1_000_000L

    /** Writes the installed manifest and every installed model file to [uri]. */
    suspend fun export(
        context: Context,
        uri: Uri,
        manifest: ModelManifest,
        onProgress: (bytes: Long, totalBytes: Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val store = ModelStore(context)
        val specs = ModelRole.entries.map { role ->
            manifest.file(role)
                ?: throw IOException("The installed model is missing ${role.displayName}.")
        }
        specs.forEach { spec ->
            val file = store.finalFile(manifest, spec)
            if (!file.exists() || file.length() != spec.sizeBytes) {
                throw IOException("${spec.path} is missing or incomplete.")
            }
        }

        val totalBytes = manifest.totalBytes
        var written = 0L
        var lastReported = 0L

        openOutput(context, uri).use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                // Model weights are already compressed binaries; storing them
                // avoids spending CPU on a pointless deflate pass.
                zip.setLevel(Deflater.NO_COMPRESSION)

                val manifestBytes = manifest.toJson().toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestBytes)
                zip.closeEntry()

                val buffer = ByteArray(BUFFER_SIZE)
                for (spec in specs) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(spec.path))
                    store.finalFile(manifest, spec).inputStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                            written += read
                            if (written - lastReported >= REPORT_INTERVAL || written == totalBytes) {
                                lastReported = written
                                onProgress(written, totalBytes)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        onProgress(written, totalBytes)
        Log.d(TAG, "Exported ${specs.size} model files ($written bytes)")
    }

    /**
     * Verifies [uri] and installs its models. Returns the manifest that was
     * imported so the caller can refresh state and prune old versions.
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        onProgress: (bytes: Long, totalBytes: Long) -> Unit
    ): ModelManifest = withContext(Dispatchers.IO) {
        val store = ModelStore(context)
        val manifest = readManifest(context, uri)
        require(manifest.hasVision && manifest.hasText) {
            "The archive must contain all three model files."
        }

        val totalBytes = manifest.totalBytes
        val required = (totalBytes * ModelStore.STORAGE_FACTOR).toLong()
        val free = store.freeBytes()
        if (free in 1 until required) {
            throw IOException(
                "Not enough storage. Need ${asMb(required)} MB free, but only " +
                    "${asMb(free)} MB is available."
            )
        }

        val staging = store.importStagingDir(manifest.version)
        staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw IOException("Could not prepare the import directory.")
        }

        val expected = manifest.files.associateBy { it.path }
        val seen = HashSet<String>()
        var importedBytes = 0L
        var lastReported = 0L

        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        if (entry.name == MANIFEST_ENTRY) {
                            zip.closeEntry()
                            continue
                        }

                        val spec = expected[entry.name]
                            ?: throw IOException("Unexpected file in archive: ${entry.name}")
                        if (!seen.add(entry.name)) {
                            throw IOException("${entry.name} appears more than once.")
                        }

                        val target = File(staging, spec.path)
                        target.parentFile?.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L

                        FileOutputStream(target).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = zip.read(buffer)
                                if (read < 0) break
                                size += read
                                if (size > spec.sizeBytes) {
                                    throw IOException("${spec.path} is larger than expected.")
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)

                                val done = importedBytes + size
                                if (done - lastReported >= REPORT_INTERVAL ||
                                    done == totalBytes
                                ) {
                                    lastReported = done
                                    onProgress(done, totalBytes)
                                }
                            }
                        }

                        if (size != spec.sizeBytes) {
                            throw IOException("${spec.path} has the wrong size.")
                        }
                        if (!digest.digest().toHex().equals(spec.sha256, ignoreCase = true)) {
                            throw IOException("${spec.path} failed checksum verification.")
                        }
                        importedBytes += size
                        zip.closeEntry()
                    }
                }
            } ?: throw IOException("Could not open the archive.")

            val missing = expected.keys.filterNot { it in seen }
            if (missing.isNotEmpty()) {
                throw IOException("The archive is missing: ${missing.joinToString()}.")
            }

            replaceInstalledFiles(store, manifest, staging)
            store.markImportedFilesReady(manifest, expected.values)
            staging.deleteRecursively()
            onProgress(totalBytes, totalBytes)
            Log.d(TAG, "Imported ${expected.size} model files ($importedBytes bytes)")
            manifest
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    /**
     * Swaps the verified staging directory into place. The previous version is
     * moved aside first and restored if the swap fails.
     */
    private fun replaceInstalledFiles(
        store: ModelStore,
        manifest: ModelManifest,
        staging: File
    ) {
        val finalDir = store.installDir(manifest)
        finalDir.parentFile?.mkdirs()

        val previous = File(staging.parentFile, "previous-${manifest.version}")
        previous.deleteRecursively()
        val hadPrevious = finalDir.exists()
        if (hadPrevious) {
            if (!finalDir.renameTo(previous) && !copyDirectory(finalDir, previous)) {
                throw IOException("Could not prepare the installed model for replacement.")
            }
            if (finalDir.exists()) finalDir.deleteRecursively()
        }

        if (!staging.renameTo(finalDir) && !copyDirectory(staging, finalDir)) {
            finalDir.deleteRecursively()
            if (hadPrevious) {
                previous.renameTo(finalDir)
            }
            throw IOException("Could not install the imported models.")
        }
        staging.deleteRecursively()
        previous.deleteRecursively()
    }

    /** Reads and validates the manifest entry without loading model weights. */
    private fun readManifest(context: Context, uri: Uri): ModelManifest {
        val json = context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use zip@{ zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (entry.name == MANIFEST_ENTRY) {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_MANIFEST_BYTES) {
                                throw IOException("The archive manifest is too large.")
                            }
                            output.write(buffer, 0, read)
                        }
                        zip.closeEntry()
                        return@zip output.toString(Charsets.UTF_8.name())
                    }
                    zip.closeEntry()
                }
                null
            }
        } ?: throw IOException("The archive does not contain $MANIFEST_ENTRY.")

        return try {
            ModelManifest.parse(json, "")
        } catch (e: Exception) {
            throw IOException("The archive manifest is invalid: ${e.message}", e)
        }
    }

    private fun openOutput(context: Context, uri: Uri): OutputStream {
        val truncated = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            null
        }
        return truncated
            ?: context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the destination file.")
    }

    private fun copyDirectory(source: File, destination: File): Boolean {
        destination.deleteRecursively()
        if (!destination.mkdirs()) return false
        val children = source.listFiles() ?: return false
        for (child in children) {
            if (!child.isFile) return false
            try {
                child.copyTo(File(destination, child.name), overwrite = true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy ${child.name}", e)
                return false
            }
        }
        return true
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val chars = CharArray(size * 2)
        for (i in indices) {
            val value = this[i].toInt() and 0xFF
            chars[i * 2] = digits[value ushr 4]
            chars[i * 2 + 1] = digits[value and 0x0F]
        }
        return String(chars)
    }

    private fun asMb(bytes: Long): Long = (bytes / (1024 * 1024)).coerceAtLeast(0)
}
