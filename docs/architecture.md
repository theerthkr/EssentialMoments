# Architecture of EssentialMoments

EssentialMoments is a powerful and intelligent Android gallery application designed to provide advanced photo search and organization features using cutting-edge machine learning right on your device.

Here is a high-level overview of the architectural components that power the app:

## 1. Modern UI with Jetpack Compose
The user interface is entirely built using **Jetpack Compose**, Android's modern toolkit for building native UI. This allows for a declarative and highly responsive user experience.
The application adheres to the **MVVM (Model-View-ViewModel)** architectural pattern, ensuring a clean separation of concerns between UI components and business logic. State flows down from the ViewModels, and events flow up from the Compose UI.

## 2. On-Device Semantic Search with ML
At the core of EssentialMoments is an on-device machine learning pipeline powered by **LiteRT (TensorFlow Lite)**.
- **SigLIP2 Model**: The app employs the state-of-the-art **SigLIP2** (Sigmoid Loss for Language Image Pre-Training) model. This model seamlessly bridges text and images, computing highly accurate semantic embeddings for both natural language queries and the photos stored on your device.
- Because everything runs locally, users enjoy lightning-fast search without sacrificing privacy. No images or text are sent to the cloud.

## 3. Background Indexing
To ensure the app remains performant, photo indexing is handled in the background. As new photos are added, the app processes them to extract their visual embeddings seamlessly without blocking the main UI thread.

## 4. Native C++ Integration
EssentialMoments utilizes **CMake** to build and integrate C++ native components located in the `src/main/cpp` directory. This ensures high-performance execution of performance-critical tasks, such as specific ML preprocessing or LiteRT inferences, allowing the app to run complex models efficiently even on mobile devices.

---

The architecture of EssentialMoments is designed to be scalable, responsive, and secure—bringing state-of-the-art AI to a daily utility app.
