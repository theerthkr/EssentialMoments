import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Room's annotation processor runs through AGP's built-in Kotlin kapt
    alias(libs.plugins.kotlin.kapt)
}

/**
 * Where the app looks for the model manifest.
 *
 * Override without touching source (first match wins):
 *   1. ./gradlew assembleRelease -PmodelManifestUrl=https://example.com/models/model-manifest.json
 *   2. `modelManifestUrl=...` in gradle.properties or local.properties
 *   3. ORG_GRADLE_PROJECT_modelManifestUrl in the environment
 *
 * The manifest itself contains the URLs of the model files, so changing the
 * host only requires changing this one value.
 */
fun gradleOrLocalProperty(name: String): String? {
    (project.findProperty(name) as String?)?.let { return it }
    val local = rootProject.file("local.properties")
    if (!local.exists()) return null
    return runCatching {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty(name)
    }.getOrNull()
}

val modelManifestUrl: String =
    gradleOrLocalProperty("modelManifestUrl")
        ?: "https://gitlab.com/theerthkr211/essential-moments-assets/-/raw/main/model-manifest.json?inline=false"

/** Optional Base64 (X.509/SPKI) ECDSA P-256 key used to verify `model-manifest.json.sig`. */
val modelManifestPublicKey: String =
    gradleOrLocalProperty("modelManifestPublicKey") ?: ""

/** Debug-only convenience: bundle `models/` into debug APKs instead of downloading. */
val bundleModels: Boolean =
    gradleOrLocalProperty("bundleModels")?.toBoolean() ?: false

android {
    namespace = "com.theerthkr.essentialmoments"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.theerthkr.essentialmoments"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MODEL_MANIFEST_URL", "\"$modelManifestUrl\"")
        buildConfigField("String", "MODEL_MANIFEST_PUBLIC_KEY", "\"$modelManifestPublicKey\"")
        buildConfigField("boolean", "BUNDLE_MODELS", "$bundleModels")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        // Only debug builds may optionally bundle models for offline development:
        //   ./gradlew :app:installDebug -PbundleModels=true
        // Release APKs always download them from the manifest URL.
        getByName("debug") {
            if (bundleModels) {
                assets.srcDir(rootProject.layout.projectDirectory.dir("models"))
            }
        }
    }
    androidResources {
        // When bundling for development, keep the weights uncompressed so first
        // launch does not pay a large decompression cost.
        if (bundleModels) noCompress += listOf("onnx", "bin")
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.core.splashscreen)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
}

kotlin {
    jvmToolchain(21)
}

/**
 * Installs the debug build and precompiles it on the connected device.
 *
 * Debug APKs are installed in a JIT-only state: scrolling a Compose grid makes the
 * runtime compile hot methods *during* the scroll, which costs tens of milliseconds
 * per frame and looks like dropped frames. AOT-compiling the installed package
 * removes those stalls without changing the APK or losing debuggability.
 *
 *     ./gradlew :app:installDebugAot
 */
tasks.register<Exec>("installDebugAot") {
    group = "install"
    description = "Installs the debug APK and AOT-compiles it on the connected device."
    dependsOn("installDebug")
    doFirst {
        executable = androidComponents.sdkComponents.adb.get().asFile.absolutePath
        args(
            "shell", "cmd", "package", "compile", "-m", "speed", "-f",
            requireNotNull(android.defaultConfig.applicationId)
        )
    }
}
