# Essential Moments: On-Device Semantic Photo Search for Android

[![Kotlin](https://img.shields.io/badge/kotlin-v1.9.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub release](https://img.shields.io/badge/release-APK_Download-blue)](../../releases)

> **Essential Moments** is a 100% offline, privacy-first Android photo organizer and semantic search engine. Search your personal photo gallery using natural language queries (e.g., *"birthday cake with candles"*, *"dog playing on grass"*, *"coffee mug on wooden desk"*) instead of relying on manually tagged metadata or EXIF data.

---

## ✨ Key Features

- **🔍 Natural Language Semantic Search**: Search images based on visual content and contextual meaning rather than rigid filenames or tags.
- **🔒 100% On-Device & Private**: Operates entirely offline without sending any data to cloud servers. All neural embedding generation and similarity lookups happen on your device.
- **⚡ Neural Inference with LiteRT**: Powered by Google AI Edge LiteRT and SigLIP2 vision/text encoders for fast, hardware-accelerated mobile inference.
- **📱 Modern Android Architecture**: Crafted in Kotlin with Jetpack Compose, Material 3, and Kotlin Coroutines/Flow following modern MVVM practices.

---

## 🚀 Quickstart & Installation

### Option 1: Install the Pre-built APK (Recommended)
1. Download the latest `.apk` from the [Releases](../../releases) section.
2. Allow installation from unknown sources on your Android device when prompted.
3. Launch the app and grant media permissions to initiate local image indexing.

### Option 2: Build from Source

#### Prerequisites
- Android Studio Ladybug or newer
- JDK 17
- Android SDK (API Level 24+ supported)

```bash
# Clone the repository
git clone https://github.com/theerthkr/EssentialMoments.git
cd EssentialMoments

# Build debug APK via Gradle
./gradlew assembleDebug
```

---

## 🏗️ Architecture & How It Works

Essential Moments converts both images and text queries into high-dimensional vector representations to match meaning rather than keywords:

```text
[ Local Photo Library ] ──► [ LiteRT SigLIP2 Vision Encoder ] ──► [ Local Vector Store ]
                                                                             │
[ Query: "sunset beach" ] ──► [ LiteRT SigLIP2 Text Encoder ] ──► [ Cosine Similarity ] ──► [ Ranked Image Results ]
```

- **ML Layer**: Implements Google AI Edge LiteRT with C++ JNI bindings for quantized SigLIP2 model execution.
- **Data Layer**: Stores generated image embeddings locally in `EmbeddingStore` for fast query retrieval.
- **UI Layer**: Declarative Jetpack Compose components bound reactively to `SearchViewModel` and `GalleryViewModel`.

For comprehensive details on model initialization and data flows, read the [Architecture and Technical Guide](docs/architecture.md).

---

## 🤝 Contributing

Contributions, bug reports, and feature proposals are warmly welcome!

1. Check existing issues or submit a new one on the [Issues](../../issues) page.
2. Read the [Contributing Guidelines](.github/CONTRIBUTING.md) and [Code of Conduct](.github/CODE_OF_CONDUCT.md).
3. Fork the repository and create your feature branch:
   ```bash
   git checkout -b feature/amazing-feature
   ```
4. Commit your changes and submit a Pull Request.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
