# Hosting and configuring the AI models

The app no longer ships the ONNX weights inside the APK. On first run it downloads
them from a manifest, keeps them in app-private storage (`noBackupFilesDir/ml_models/`),
and verifies every file before use.

## 1. Where the app downloads from

The single source of truth is the **manifest URL**, compiled into the app as
`BuildConfig.MODEL_MANIFEST_URL`:

```kotlin
// app/build.gradle.kts (default)
"https://github.com/theerthkr/EssentialMoments-models/releases/latest/download/model-manifest.json"
```

Override it without editing source:

```bash
./gradlew :app:assembleRelease \
  -PmodelManifestUrl=https://cdn.example.com/models/model-manifest.json
```

You can also put the value in `gradle.properties` (committed) or the untracked
`local.properties` (machine-specific, recommended):

```properties
modelManifestUrl=https://cdn.example.com/models/model-manifest.json
modelManifestPublicKey=MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...
bundleModels=false
```

Precedence: `-P` / environment (`ORG_GRADLE_PROJECT_modelManifestUrl`) → Gradle
properties → `local.properties` → built-in default.

The manifest lists the files and **relative or absolute** URLs:

```json
{
  "role": "vision",
  "path": "vision_model_int8.onnx",
  "url": "vision_model_int8.onnx",
  "sizeBytes": 94553333,
  "sha256": "0dd31785..."
}
```

A relative `url` is resolved against the manifest's directory, so keeping the
manifest and weights in the **same GitHub release** needs no per-file URLs.
You can also point every file at a CDN with absolute `https://` URLs.

## 2. Publishing a GitHub release

Create a dedicated repository (recommended: `EssentialMoments-models`) so that
`releases/latest` only ever refers to model releases. Then:

```bash
cd models
sha256sum onnx_int8/vision_model_int8.onnx \
          onnx_int8/text_model_int8.onnx \
          onnx_int8/siglip2_vocab.bin

gh release create models-v1 \
  --repo theerthkr/EssentialMoments-models \
  --title "SigLIP 2 INT8 v1" \
  --notes "Initial on-device embedding models" \
  model-manifest.json \
  onnx_int8/vision_model_int8.onnx \
  onnx_int8/text_model_int8.onnx \
  onnx_int8/siglip2_vocab.bin
```

GitHub serves release assets with redirects and `Range` support, so the
downloader can pause/resume and resume after a process death.

The manifest in `models/model-manifest.json` already contains the correct sizes
and SHA-256 hashes for the current files. Upload it as an asset named exactly
`model-manifest.json`.

## 3. Optional manifest signing

Without a key, the app trusts HTTPS plus the per-file SHA-256 hashes. To protect
against a compromised host or a tampered manifest, sign the manifest with an
ECDSA P-256 key and ship the public key:

```bash
openssl ecparam -name prime256v1 -genkey -noout -out model-signing-key.pem
openssl ec -in model-signing-key.pem -pubout -out model-signing-pub.pem

openssl dgst -sha256 -sign model-signing-key.pem \
  -out model-manifest.json.sig model-manifest.json

# Public key as Base64 (X.509/SPKI), used at build time
openssl pkey -pubin -in model-signing-pub.pem -outform DER | base64 -w0
```

Build with:

```bash
./gradlew :app:assembleRelease \
  -PmodelManifestPublicKey='MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...'
```

Upload `model-manifest.json.sig` to the same release. Keep the private key out
of the repository. Rotating the key requires an app update.

## 4. Local development without re-downloading

Bundle the local `models/` folder into **debug** builds only:

```bash
./gradlew :app:installDebug -PbundleModels=true
```

This adds `models/` as a debug asset source set (`models/onnx_int8/...`) and sets
`BuildConfig.BUNDLE_MODELS = true`. On first use the debug build stages the
assets once into `ml_models/dev/`. Release builds never include them.

## 5. Storage layout

```
noBackupFilesDir/
  ml_models/
    <modelId>/<version>/vision_model_int8.onnx
    <modelId>/<version>/text_model_int8.onnx
    <modelId>/<version>/siglip2_vocab.bin
    staging/<version>/<file>.part
    dev/<file>                     # debug bundling only
```

`noBackupFilesDir` keeps ~380 MB out of cloud backup. Download progress and
install state are tracked in the `model_store` and `model_manager` shared
preferences.

## 6. Bumping a model version

1. Upload new files under a new tag (for example `models-v2`).
2. Set `"version": 2` in the manifest and keep `"modelId"` unchanged if the
   embedding space is identical (existing photo embeddings stay valid).
3. If the embedding space changes, also change `"modelId"` so old vectors are
   never compared against new ones.
4. The app shows "Update available" in Settings and downloads the new version;
   after success, older files are pruned.

If you use the `releases/latest/download/model-manifest.json` URL, publishing the
new release is enough. Otherwise update `modelManifestUrl` or publish a new app.
