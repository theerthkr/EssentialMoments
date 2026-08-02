package com.theerthkr.essentialmoments

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object LaptopModuleUtils {

    suspend fun exportEmbeddingsToJson(context: Context, targetUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val boxStore = ObjectBox.store
                val imageBox = boxStore.boxFor(ImageEntity::class.java)
                val allEntities = imageBox.all

                val jsonArray = JSONArray()
                for (entity in allEntities) {
                    val jsonObject = JSONObject()
                    jsonObject.put("uri", entity.uri)

                    val embeddingArray = JSONArray()
                    for (value in entity.embedding) {
                        embeddingArray.put(value)
                    }
                    jsonObject.put("embedding", embeddingArray)

                    jsonArray.put(jsonObject)
                }

                context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    outputStream.write(jsonArray.toString(2).toByteArray())
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
