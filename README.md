# 📸 Essential Moments

[![Kotlin](https://img.shields.io/badge/kotlin-v1.9.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Essential Moments** is a modern, privacy-first Android gallery application that leverages cutting-edge on-device Machine Learning to enable semantic search through your personal photos.

Instead of searching by tags or dates, you can search your gallery using natural language (e.g., *"a cat sitting on a sofa"*, *"sunset at the beach"*, or *"birthday cake"*). All indexing and processing are performed entirely on your device—**no internet connection or cloud servers required**, ensuring your memories remain 100% private.

---

## ✨ Features

*   🔍 **Semantic Search**: Find photos using natural language queries powered by advanced vision-language models.
*   🧠 **On-Device Machine Learning**: Utilizes LiteRT (TensorFlow Lite) and SigLIP2 models to understand image content locally.
*   🔒 **Privacy-First Architecture**: Your photos never leave your device. All embeddings are generated and stored locally.
*   🎨 **Modern UI/UX**: A beautiful, responsive, and intuitive interface built entirely from the ground up with Jetpack Compose.
*   ⚡ **Background Indexing**: Efficiently indexes your photo library in the background using Android WorkManager without draining your battery.
*   🚀 **High Performance**: Native C++ integration (via JNI) for fast and optimized model inference.

## 🛠️ Tech Stack

*   **Language**: [Kotlin](https://kotlinlang.org/) (Primary), C++ (JNI for ML)
*   **UI Toolkit**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
*   **Machine Learning**: [LiteRT (Google AI Edge)](https://ai.google.dev/edge/litert)
*   **Models**: [SigLIP2](https://huggingface.co/docs/transformers/model_doc/siglip2) (Vision and Text Encoders)
*   **Image Loading**: [Coil](https://coil-kt.github.io/coil/)
*   **Concurrency**: Kotlin Coroutines & Flow
*   **Architecture**: MVVM (Model-View-ViewModel)

## 📋 Requirements

*   **Android Studio**: Latest stable version (Koala or newer recommended)
*   **Minimum SDK**: 24 (Android 7.0 Nougat)
*   **Target SDK**: 36 (Android 15)
*   **Device**: A physical device or emulator with a decent amount of RAM is recommended due to the ML models.

## 🚀 Getting Started

Follow these instructions to get a copy of the project up and running on your local machine for development and testing purposes.

### 1. Clone the repository

```bash
git clone https://github.com/your-username/EssentialMoments.git
cd EssentialMoments
```

### 2. Download the Models (Crucial Step)

For the semantic search to function, you need to place the pre-trained SigLIP2 models in the `app/src/main/assets/` directory.

Ensure the following files are present in `app/src/main/assets/`:
*   `siglip2_base_patch16-224_f16.tflite`
*   `siglip2_text_only.tflite`

*(If you don't have these models, the app will still compile but semantic search and indexing will fail).*

### 3. Build and Run

1. Open the cloned project directory in **Android Studio**.
2. Wait for Gradle to sync and download all necessary dependencies.
3. Select your target device or emulator.
4. Click the "Run" button ▶️ in Android Studio, or execute the following from the terminal:

```bash
./gradlew installDebug
```

## 🏗️ Architecture Overview

The app follows the recommended Android architecture guidelines using the **MVVM** pattern:

*   **UI Layer**: Composed of stateless Jetpack Compose functions that react to state emitted by the ViewModels.
*   **ViewModel Layer**: Manages the UI state, user actions, and bridges the gap between the UI and the underlying logic (`GalleryViewModel`, `SearchViewModel`).
*   **ML & Data Layer**:
    *   `ImageEmbedder` & `TextEmbedder`: Handle model initialization, preprocessing, and inference.
    *   `SigLIPTokenizer`: Processes text input for the ML models.
    *   `EmbeddingStore`: Manages the storage and retrieval of the generated vector embeddings.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome!
Feel free to check the [issues page](../../issues).

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---
*Built with ❤️ for privacy and photography enthusiasts.*
