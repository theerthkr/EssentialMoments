# Codebase Architecture Analysis & Model Decoupling Strategy

> **Context for Future Conversations / AI Agents:**
> This document captures the complete architectural analysis of **EssentialMoments** (an on-device semantic photo search gallery powered by SigLIP 2 LiteRT models and ObjectBox vector storage). It explains the current entanglements, why model execution and worker indexing are tightly coupled, the proposed target architecture, and the step-by-step refactoring plan.
>
> **Read this file first before starting any refactoring tasks related to model extraction, background indexing, or search.**

---

## 1. Domain & Codebase Overview

EssentialMoments provides offline, privacy-first natural language photo search on Android devices.
Photos on the device are converted into **768-dimensional L2-normalized embeddings** using a vision foundation model, stored in an ObjectBox vector database, and matched against natural language query embeddings via cosine similarity (which simplifies to a dot product because all vectors are unit length).

### Key Components Today

| Component | Path | Current Responsibility |
| :--- | :--- | :--- |
| **`SiglipImageProcessor`** | [`app/.../SiglipImageProcessor.kt`](file:///home/loser/AndroidStudioProjects/EssentialMoments/app/src/main/java/com/theerthkr/essentialmoments/SiglipImageProcessor.kt) | Compiles vision LiteRT model (`siglip2_base_patch16-224.tflite`), manages hardware fallback (`NPU -> GPU -> CPU`), allocates `TensorBuffer`s, rescales bitmaps to 224×224, converts ARGB to NCHW `[-1.0, 1.0]`, runs inference, L2-normalizes output. |
| **`SiglipTextProcessor`** | [`app/.../SiglipTextProcessor.kt`](file:///home/loser/AndroidStudioProjects/EssentialMoments/app/src/main/java/com/theerthkr/essentialmoments/SiglipTextProcessor.kt) | Initializes `SigLIPTokenizer` (Gemma 256k BPE, 64-token sequence), compiles text LiteRT model (`siglip2_text.tflite`), writes input token buffers, runs inference, L2-normalizes output. |
| **`IndexingWorker`** | [`app/.../IndexingWorker.kt`](file:///home/loser/AndroidStudioProjects/EssentialMoments/app/src/main/java/com/theerthkr/essentialmoments/IndexingWorker.kt) | An Android `CoroutineWorker` running as a Foreground Service. Queries `MediaStore` for all photo URIs, instantiates `SiglipImageProcessor`, queries ObjectBox to deduplicate existing images, runs embedding inference per photo, saves `ImageEntity` to ObjectBox, posts notification progress, and monitors `isStopped`. |
| **`SearchViewModel`** | [`app/.../SearchViewModel.kt`](file:///home/loser/AndroidStudioProjects/EssentialMoments/app/src/main/java/com/theerthkr/essentialmoments/SearchViewModel.kt) | Handles search UI state. When a search query is submitted, it cancels `IndexingWorker` via `WorkManager.cancelUniqueWork()`, instantiates `SiglipTextProcessor`, loads all image records from ObjectBox into RAM, computes cosine similarities sequentially in Kotlin, and sorts matches. |
| **`ImageEntity`** | [`app/.../ImageEntity.kt`](file:///home/loser/AndroidStudioProjects/EssentialMoments/app/src/main/java/com/theerthkr/essentialmoments/ImageEntity.kt) | ObjectBox entity storing photo URI string and 768-D `FloatArray` embedding. |
| **`ModelActivity`** | [`app/.../ModelActivity.kt`](file:///home/loser/AndroidStudioProjects/EssentialMoments/app/src/main/java/com/theerthkr/essentialmoments/ModelActivity.kt) | Diagnostic / manual test Activity triggering indexing and testing both vision and text embeddings interactively. |

---

## 2. Core Architectural Friction & Entanglements

### Friction 1: Model Execution & Hardware Lifecycle Leaking Across Callers
- **The Problem:** Model loading requires Android `Context`, asset managers, LiteRT native binaries, pre-allocated `TensorBuffer` lists, and explicit `.use { ... }` lifecycle management.
- **Why it hurts:**
  - Neither `IndexingWorker` nor `SearchViewModel` should care about LiteRT buffers, hardware accelerator fallbacks (`NPU -> GPU -> CPU`), or model compilation.
  - Model code cannot run on the host JVM for unit tests. Testing anything that uses an embedding currently requires an Android emulator or device with hardware drivers.

### Friction 2: The Worker is Overloaded (Fails the Deletion Test)
- **The Problem:** `IndexingWorker` combines 5 distinct responsibilities in 174 lines:
  1. Android Foreground Service & Notification lifecycle
  2. MediaStore cursor querying
  3. ObjectBox deduplication query
  4. LiteRT model invocation & bitmap recycling
  5. ObjectBox transaction writes
- **Why it hurts:**
  - If we ask the *deletion test* ("If we delete `IndexingWorker`, does complexity vanish or get scattered?"): deleting `IndexingWorker` destroys the core photo indexing business logic because it is trapped inside an Android `Worker` class.
  - The worker is shallow in leverage but bloated with procedural glue.

### Friction 3: Hardware Contention & Primitive Preemption
- **The Problem:** On mobile edge devices, running vision model inference (in the worker) and text model inference (in search) concurrently on NPU/GPU causes driver contention, out-of-memory errors, or severe UI freezes.
- **Current ad-hoc workaround:**
  ```kotlin
  // In SearchViewModel.kt:
  WorkManager.getInstance(appContext).cancelUniqueWork("image_indexing_task")
  ```
- **Why it hurts:**
  - The UI layer (`SearchViewModel`) directly manipulates background jobs (`WorkManager`) just to avoid hardware driver conflicts.
  - The indexing worker is abruptly terminated rather than cooperatively paused.

### Friction 4: Vector Storage & Math Mixed into Presentation
- **The Problem:** `SearchViewModel` does `imageBox.all`, pulls all embeddings into memory, iterates through every entity running a manual `dot += a[i] * b[i]` loop, and sorts.
- **Why it hurts:** Presentation is coupled to database retrieval and linear scan math. If database indexing (e.g. HNSW index or ObjectBox vector index) is added, the UI layer has to change.

---

## 3. The Target Architecture: Deep Modules & Clean Seams

To untangle model execution from indexing and search, we introduce two primary **deep modules** placed at explicit **seams**:

```
┌─────────────────────────────────────────────────────────────┐
│                       Callers / UI                          │
│        (SearchViewModel, GalleryViewModel, Activities)      │
└──────────────┬──────────────────────────────┬───────────────┘
               │                              │
               ▼                              ▼
┌──────────────────────────────┐ ┌─────────────────────────────┐
│    Photo Indexer Module      │ │    Search Retrieval Module  │
│  (MediaStore diffing, batch  │ │   (Vector scoring, ranking, │
│   orchestration, DB persist) │ │    ObjectBox vector queries)│
└──────────────┬───────────────┘ └────────────┬────────────────┘
               │                              │
               └──────────────┬───────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 Deep Embedding Module Seam                  │
│       interface: embedPhoto(Uri/Bitmap), embedText(String)  │
├──────────────────────────────┬──────────────────────────────┤
│ LiteRT Hardware Adapter      │ In-Memory / Fake JVM Adapter │
│ (NPU/GPU/CPU fallback,       │ (Predictable vectors, fast   │
│  tensor buffers, tokenizer)  │  unit testing without native)│
└──────────────────────────────┴──────────────────────────────┘
```

### Module 1: The Deep Embedding Module (`:core:embedding`)
- **Seam / Interface:**
  Presents a minimal, high-leverage interface that accepts high-level inputs (a photo reference or text string) and produces an `Embedding` (768-D normalized `FloatArray`).
  It does **not** expose:
  - `CompiledModel`, `TensorBuffer`, `AssetManager`, or LiteRT details
  - NCHW reshaping, normalization factors, or token IDs
- **Two Real Adapters:**
  1. **`LiteRtEmbeddingAdapter` (Android Runtime):** Encapsulates model compilation, NPU/GPU accelerator fallback, buffer reuse, and tokenizer loading.
  2. **`FakeEmbeddingAdapter` (Unit Tests / JVM):** Generates deterministic mock embeddings instantly on the JVM without loading native libraries.

### Module 2: The Deep Photo Indexer Module (`:core:indexer`)
- **What it does:** Encapsulates the entire domain pipeline of indexing:
  1. Inspects available device photos (via an abstracted media source).
  2. Diffs against existing indexed URIs in ObjectBox.
  3. Sends unindexed photos in batches through the Embedding Module.
  4. Persists new embeddings into ObjectBox.
  5. Emits progress events (`Indexed(current, total)`).
- **How `IndexingWorker` Reacts:**
  `IndexingWorker` is **demoted to a thin Android runtime adapter** (approx. 20–30 lines):
  - Its only job is being the Android `CoroutineWorker` entry point.
  - It binds the Android Foreground notification to the indexer's progress events.
  - It forwards cancellation (`isStopped`) to the indexer's coroutine cancellation.
  - It contains **zero** MediaStore queries, **zero** ObjectBox logic, and **zero** model logic.

### Module 3: Concurrency & Hardware Arbitration
- Rather than `SearchViewModel` calling `WorkManager.cancelUniqueWork()`, the Embedding Module or a lightweight `ModelCoordinator` handles priority:
  - Search queries are treated as **High Priority (Interactive)**.
  - Batch photo indexing is treated as **Low Priority (Background)**.
  - When a search request arrives, the coordinator yields/pauses background batches, completes the instant search query, and then background indexing resumes seamlessly.

---

## 4. Implementation Roadmap

When implementing this architecture in upcoming sessions, follow these phases:

### Phase 1: Establish the Embedding Module & Seam
1. Define the domain interface (e.g. `EmbeddingGenerator` or `PhotoEmbedder` / `TextEmbedder`).
2. Move `SiglipImageProcessor`, `SiglipTextProcessor`, and `SigLIPTokenizer` behind this seam inside a dedicated package or Gradle subproject (`:core:embedding` or `com.theerthkr.essentialmoments.embedding`).
3. Create the `FakeEmbeddingGenerator` for pure JVM testing.
4. Verify tests run on the JVM without Android dependencies.

### Phase 2: Extract the Photo Indexer & Demote `IndexingWorker`
1. Create `PhotoIndexer` encapsulating:
   - Media source abstraction (extracting MediaStore queries)
   - Store repository (diffing existing image URIs and saving new entities)
   - Calling the embedding seam
2. Refactor `IndexingWorker` to simply inject/call `PhotoIndexer.index()`.
3. Add JVM unit tests for `PhotoIndexer` using `FakeEmbeddingGenerator` and an in-memory repository.

### Phase 3: Unify Search Retrieval
1. Create a `SearchRepository` / `SemanticSearchEngine` module that accepts a query string, uses the embedding seam, queries ObjectBox, and returns ranked results.
2. Remove database iteration, dot-product math, and `WorkManager` cancellation from `SearchViewModel`.

---

## 5. Architectural Vocabulary Reference

*(Aligned with `references/codebase-design.md` and `GLOSSARY.md`)*

- **Module:** Anything with an interface and an implementation (e.g., Embedding module, Photo Indexer). Never call these "services" or "components".
- **Interface:** Everything a caller must know (types, parameters, invariants, error modes).
- **Implementation:** Code hidden behind the interface.
- **Seam:** The boundary where behaviour can be altered or substituted (e.g. production LiteRT vs. test Fake).
- **Adapter:** A concrete implementation satisfying an interface at a seam (`LiteRtEmbeddingAdapter`, `FakeEmbeddingAdapter`, `IndexingWorker` as Android WorkManager adapter).
- **Depth:** Leverage at the interface (small interface surface covering large internal complexity).
- **Locality:** Keeping changes, bugs, and hardware constraints in one place rather than spread across callers.
