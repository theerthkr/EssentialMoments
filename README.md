# Essential Moments

Essential Moments is a modern Android gallery application that leverages on-device Machine Learning to enable semantic search through your personal photos. Designed with privacy in mind, all indexing and searching are performed entirely on your device—no internet connection or cloud processing required.

## Features

*   **Semantic Search**: Search your photos using natural language queries (e.g., "a cat sitting on a sofa", "sunset on the beach").
*   **On-Device Machine Learning**: Utilizes advanced on-device ML models to understand image content and text queries without sending your data to a server.
*   **Privacy-First**: Complete privacy as all image processing and embedding generation happens locally.
*   **Modern UI**: Beautiful, responsive, and intuitive user interface built entirely with Jetpack Compose.
*   **Background Indexing**: Efficiently indexes your photo library in the background using WorkManager.

## Tech Stack

*   **Kotlin**: The primary programming language used for development.
*   **Jetpack Compose**: Android's modern toolkit for building native UI.
*   **LiteRT (Google AI Edge)**: Used for running the on-device ML models efficiently.
*   **SigLIP2**: Employs SigLIP2 (vision and text models) for generating embeddings for images and search queries.
*   **Coil**: Image loading library for Android, backed by Kotlin Coroutines.
*   **Coroutines & Flow**: For asynchronous programming and reactive data handling.

## Requirements

*   Android Studio (latest version recommended)
*   Minimum SDK: 24 (Android 7.0)
*   Target SDK: 36

## Getting Started

1.  **Clone the repository**:
    ```bash
    git clone https://github.com/your-username/EssentialMoments.git
    cd EssentialMoments
    ```

2.  **Open in Android Studio**:
    Open the cloned project directory in Android Studio.

3.  **Sync Gradle**:
    Wait for Gradle to sync and download all necessary dependencies.

4.  **Run the App**:
    Select your target device or emulator and click the "Run" button in Android Studio, or use the command line:
    ```bash
    ./gradlew installDebug
    ```

## Architecture

The app uses an MVVM (Model-View-ViewModel) architecture.
*   **UI Layer**: Jetpack Compose components.
*   **ViewModel**: Manages UI state and business logic (e.g., `GalleryViewModel`, `SearchViewModel`).
*   **ML Layer**: Handles model initialization, preprocessing, and inference (`ImageEmbedder`, `TextEmbedder`, `SigLIPTokenizer`).

## Models

The application relies on the following LiteRT models, which should be placed in the `app/src/main/assets/` directory:
*   `siglip2_base_patch16-224_f16.tflite`
*   `siglip2_text_only.tflite`

*(Note: Ensure you have the models properly set up in the assets folder to enable semantic search functionality.)*

## License

This project is licensed under the MIT License - see the LICENSE file for details.
