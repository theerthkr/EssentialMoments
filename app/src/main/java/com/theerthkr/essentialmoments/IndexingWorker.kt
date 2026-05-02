package com.theerthkr.essentialmoments

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel


class IndexingWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "EssentialMoments_Indexing"

    private val notificationId = 101
    private val channelId = "indexing_channel"

    override suspend fun doWork(): Result {

        setForeground(createForegroundInfo())

        Log.d("EssentialMoments_Indexing", "Worker promoted to Foreground Service.")
        Log.d(TAG, "Worker started: Preparing to process images.")

        val indexAll = inputData.getBoolean("KEY_INDEX_ALL", false)
        val providedUris = inputData.getStringArray("KEY_IMAGE_URIS")?.toList() ?: emptyList()

        // Determine the final list of images to process
        val finalUriStrings = if (indexAll) {
            Log.d(TAG, "Indexing mode: ALL IMAGES on device.")
            fetchAllImagesFromMediaStore() // We will write this helper below
        } else {
            Log.d(TAG, "Indexing mode: SELECTED IMAGES only (${providedUris.size}).")
            providedUris
        }


        val processor = SiglipImageProcessor() //[cite: 1]
        val imageBox = ObjectBox.store.boxFor(ImageEntity::class.java) //

        Log.d(TAG, "Loading AI Model on NPU...")
        val model = CompiledModel.create(
            applicationContext.assets,
            "siglip2_base_patch16-224.tflite",
            CompiledModel.Options(Accelerator.NPU)
        ) //

        try {
            for ((index, uriString) in finalUriStrings.withIndex()) {
                // IMPORTANT: Check if the user paused the worker (e.g., started the Text Model)
                if (isStopped) {
                    Log.w(TAG, "Worker stopped signal received. Pausing at image $index.")
                    return Result.retry()
                }

                Log.d(TAG, "Checking if image is already indexed: $uriString")


                val query = imageBox.query(ImageEntity_.uri.equal(uriString)).build()

                // 2. Try to find the first match[cite: 4]
                val existingImage = query.findFirst()

                // 3. Close the query to save memory (Important in a loop!)[cite: 4]
                query.close()

                if (existingImage != null) {
                    Log.d(TAG, "Skipping: Image already exists in ObjectBox.")
                    continue // Skip to the next image in the loop
                }

                Log.d(TAG, "Processing image ${index + 1}/${finalUriStrings.size}: $uriString")

                // 2. Preprocess using your custom SiglipImageProcessor[cite: 1]
                val bitmap = uriToBitmap(applicationContext, Uri.parse(uriString)) ?: continue
                val resized = processor.resize(bitmap, 224, 224)
                val hwcPixels = processor.extractHwcPixels(resized)
                val rescaled = processor.rescale(hwcPixels, 255.0f)
                val normalized = processor.normalize(rescaled, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
                val nchwFloatArray = processor.transposeHwcToNchw(normalized, 224, 224)

                // 3. Run Inference to get the embedding[cite: 2]
                val inputBuffers = model.createInputBuffers()
                val outputBuffers = model.createOutputBuffers()
                inputBuffers[0].writeFloat(nchwFloatArray)
                model.run(inputBuffers, outputBuffers)

                val rawOutput = outputBuffers[0].readFloat()
                val finalEmbedding = l2Normalize(rawOutput) // Normalize for semantic search[cite: 2]
                val preview = finalEmbedding.take(5).joinToString(", ")
                Log.d(TAG, "Embedding Generated! Size: ${finalEmbedding.size} | Preview: [$preview...]")

                // 4. Save to ObjectBox[cite: 4]
                imageBox.put(ImageEntity(uri = uriString, embedding = finalEmbedding))
                Log.i(TAG, "Successfully indexed image ${index + 1}: $uriString")
                updateNotification(index + 1, finalUriStrings.size)
                bitmap.recycle() // Keep memory usage low
            }

            Log.d(TAG, "Indexing complete! Processed all ${finalUriStrings.size} images.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Critical error during indexing: ${e.message}")
            return Result.retry()
        } finally {
            model.close() // Always release AI resources![cite: 2]
        }

    }
    private fun fetchAllImagesFromMediaStore(): List<String> {
        val uriList = mutableListOf<String>()
        val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
        val query = applicationContext.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                uriList.add(contentUri.toString())
            }
        }
        return uriList
    }
    private fun createForegroundInfo(): ForegroundInfo {
        val intent = Intent(applicationContext, StopIndexingReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        // Create Channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(channelId, "Image Indexing", NotificationManager.IMPORTANCE_LOW)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Indexing in progress")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Your app logo
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingIntent)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            // THIS IS THE FIX: Explicitly passing the service type
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    }

    private fun updateNotification(current: Int, total: Int) {
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Indexing in progress")
            .setContentText("Processing image $current of $total")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            // Keep the Stop button visible during updates
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                android.app.PendingIntent.getBroadcast(
                    applicationContext, 0,
                    android.content.Intent(applicationContext, StopIndexingReceiver::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setProgress(total, current, false) // Shows a progress bar
            .build()

        val manager = applicationContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(notificationId, notification)
    }
}