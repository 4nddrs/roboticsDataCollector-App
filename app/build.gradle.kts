plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.roboticsdatacollector"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.roboticsdatacollector"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // Keep the MediaPipe .task model uncompressed so the runtime can mmap it.
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Corrutinas para I/O de IMU y el guardián de calidad en segundo plano
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // CameraX: vista previa, grabación MP4 y análisis de frames (guardian)
    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Official MediaPipe Hands (HandLandmarker) for live CameraX analysis
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Official float16 Hand Landmarker. Place the file at:
//   app/src/main/assets/hand_landmarker.task
// Download:
//   https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
val handLandmarkerAsset = file("src/main/assets/hand_landmarker.task")
tasks.register("downloadHandLandmarkerModel") {
    group = "mediapipe"
    description = "Downloads hand_landmarker.task into src/main/assets if it is missing"
    onlyIf { !handLandmarkerAsset.exists() }
    doLast {
        val url = uri(
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        )
        handLandmarkerAsset.parentFile.mkdirs()
        url.toURL().openStream().use { input ->
            handLandmarkerAsset.outputStream().use { output -> input.copyTo(output) }
        }
        println("Saved MediaPipe model to ${handLandmarkerAsset.absolutePath}")
    }
}
