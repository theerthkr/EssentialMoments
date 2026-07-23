# 🏗️ Architecture and Technical Details

This document outlines the architecture, tech stack, and implementation details for **Essential Moments**.

## 🛠️ Tech Stack

*   **Language**: [Kotlin](https://kotlinlang.org/) (Primary), C++ (JNI for ML)
*   **UI Toolkit**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
*   **Machine Learning**: [LiteRT (Google AI Edge)](https://ai.google.dev/edge/litert)
*   **Models**: [SigLIP2](https://huggingface.co/docs/transformers/model_doc/siglip2) (Vision and Text Encoders)
*   **Image Loading**: [Coil](https://coil-kt.github.io/coil/)
*   **Concurrency**: Kotlin Coroutines & Flow
*   **Architecture**: MVVM (Model-View-ViewModel)

## 🏗️ Architecture Overview

The app follows the recommended Android architecture guidelines using the **MVVM** pattern:

*   **UI Layer**: Composed of stateless Jetpack Compose functions that react to state emitted by the ViewModels.
*   **ViewModel Layer**: Manages the UI state, user actions, and bridges the gap between the UI and the underlying logic (`GalleryViewModel`, `SearchViewModel`).
*   **ML & Data Layer**:
    *   `ImageEmbedder` & `TextEmbedder`: Handle model initialization, preprocessing, and inference.
    *   `SigLIPTokenizer`: Processes text input for the ML models.
    *   `EmbeddingStore`: Manages the storage and retrieval of the generated vector embeddings.
