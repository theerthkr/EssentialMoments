package com.theerthkr.essentialmoments.ml.model

import android.util.Base64
import com.theerthkr.essentialmoments.BuildConfig
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** The three files that make up the on-device embedding stack. */
enum class ModelRole(val wireName: String, val displayName: String) {
    VISION("vision", "Photo understanding"),
    TEXT("text", "Search understanding"),
    VOCAB("vocab", "Search vocabulary");

    companion object {
        fun fromWireName(value: String): ModelRole? = entries.firstOrNull { it.wireName == value }
    }
}

/** One downloadable file described by the remote manifest. */
data class ModelFileSpec(
    val role: ModelRole,
    val path: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

/**
 * The remote `model-manifest.json` contract.
 *
 * File URLs may be absolute or relative to the manifest URL, which lets a GitHub
 * release host both the manifest and the weights side by side:
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "modelId": "siglip2_base_patch16_224_onnx_int8",
 *   "version": 1,
 *   "displayName": "SigLIP 2 Base (INT8)",
 *   "license": "Apache-2.0",
 *   "files": [
 *     { "role": "vision", "path": "vision_model_int8.onnx",
 *       "url": "vision_model_int8.onnx", "sizeBytes": 94553333, "sha256": "..." }
 *   ]
 * }
 * ```
 */
data class ModelManifest(
    val schemaVersion: Int,
    val modelId: String,
    val version: Int,
    val displayName: String,
    val license: String?,
    val files: List<ModelFileSpec>
) {
    fun file(role: ModelRole): ModelFileSpec? = files.firstOrNull { it.role == role }

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** ONNX weights are required for indexing; the text files are required for search. */
    val hasVision: Boolean get() = file(ModelRole.VISION) != null
    val hasText: Boolean get() = file(ModelRole.TEXT) != null && file(ModelRole.VOCAB) != null

    fun toJson(): String = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("modelId", modelId)
        put("version", version)
        put("displayName", displayName)
        license?.let { put("license", it) }
        put("files", org.json.JSONArray().apply {
            files.forEach { spec ->
                put(JSONObject().apply {
                    put("role", spec.role.wireName)
                    put("path", spec.path)
                    put("url", spec.url)
                    put("sizeBytes", spec.sizeBytes)
                    put("sha256", spec.sha256)
                })
            }
        })
    }.toString()

    companion object {
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Parses and validates a manifest. [manifestUrl] is used to resolve relative
         * file URLs; pass an empty string when re-parsing a cached manifest whose
         * URLs are already absolute.
         */
        fun parse(json: String, manifestUrl: String): ModelManifest {
            val root = JSONObject(json)
            val schemaVersion = root.optInt("schemaVersion", 0)
            require(schemaVersion == 1) { "Unsupported model manifest schema: $schemaVersion" }
            val modelId = root.optString("modelId").trim()
            require(modelId.isNotEmpty()) { "Manifest is missing modelId" }
            val version = root.optInt("version", 0)
            require(version > 0) { "Manifest version must be positive" }

            val array = root.optJSONArray("files")
                ?: throw IllegalArgumentException("Manifest is missing files")
            require(array.length() > 0) { "Manifest has no files" }

            val baseUrl = manifestUrl.substringBeforeLast('/', missingDelimiterValue = "")
            val files = ArrayList<ModelFileSpec>(array.length())
            val seenRoles = HashSet<ModelRole>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val role = ModelRole.fromWireName(item.optString("role"))
                    ?: throw IllegalArgumentException("Unknown model role at files[$i]")
                require(seenRoles.add(role)) { "Duplicate model role: ${role.wireName}" }

                val path = item.optString("path").trim()
                require(path.isNotEmpty()) { "Missing path for ${role.wireName}" }
                require(!path.contains('/') && !path.contains('\\') && path != "..") {
                    "Model path must be a plain file name: $path"
                }

                val rawUrl = item.optString("url").trim()
                require(rawUrl.isNotEmpty()) { "Missing url for ${role.wireName}" }
                val url = when {
                    rawUrl.startsWith("https://") || rawUrl.startsWith("http://") -> rawUrl
                    baseUrl.isNotEmpty() -> "$baseUrl/$rawUrl"
                    else -> throw IllegalArgumentException("Relative url for ${role.wireName} without a base url")
                }
                require(BuildConfig.DEBUG || url.startsWith("https://")) {
                    "Model download URLs must use HTTPS: $url"
                }

                val size = item.optLong("sizeBytes", -1L)
                require(size > 0) { "Invalid sizeBytes for ${role.wireName}" }

                val sha = item.optString("sha256").trim().lowercase()
                require(SHA256.matches(sha)) { "Invalid sha256 for ${role.wireName}" }

                files += ModelFileSpec(role, path, url, size, sha)
            }

            val manifest = ModelManifest(
                schemaVersion = schemaVersion,
                modelId = modelId,
                version = version,
                displayName = root.optString("displayName", modelId),
                license = root.optString("license").takeIf { it.isNotBlank() },
                files = files
            )
            require(manifest.hasVision) { "Manifest must include a vision model" }
            return manifest
        }
    }
}

/** Verifies the detached ECDSA P-256 signature (`model-manifest.json.sig`). */
object ModelManifestVerifier {
    fun verify(jsonBytes: ByteArray, signatureBytes: ByteArray, publicKeyBase64: String): Boolean {
        // Signature verification is opt-in. When no key is configured we still
        // require HTTPS + per-file SHA-256 checks, which protects against corruption.
        if (publicKeyBase64.isBlank()) return true
        return try {
            val keyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(jsonBytes)
            verifier.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }
}
