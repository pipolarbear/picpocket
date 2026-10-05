plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.picpocket.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.picpocket.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libbarhopper_v3.so",
                "**/libimage_processing_util_jni.so",
                "**/libmlkit_google_ocr_pipeline.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    sourceSets {
        getByName("test") {
            java.srcDir("src/sharedTest/java")
        }
        getByName("androidTest") {
            java.srcDir("src/sharedTest/java")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("sh.calvin.reorderable:reorderable:2.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ML Kit Document Scanner (Play Services)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    // ML Kit Text Recognition
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PDF parsing, text extraction, and page copy/merge (born-digital imports)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // The project already ships bcprov-jdk18on; PdfBox's older copy clashes on resources.
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }

    // QR Code generation / scanning
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // CameraX for QR scanning
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Google Drive Auth
    implementation("com.google.android.gms:play-services-auth:21.1.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Security / Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Biometric auth
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Argon2 key derivation (via Bouncy Castle)
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    // Testing — JVM unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")

    // Testing — Instrumentation tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.50")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.50")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val alignScript = rootProject.file("tmp/align_elf.py")
if (alignScript.exists()) {
    val libsToAlign = setOf(
        "libandroidx.graphics.path.so",
        "libbarhopper_v3.so",
        "libimage_processing_util_jni.so",
        "libmlkit_google_ocr_pipeline.so",
    )

    afterEvaluate {
        val alignDebugTask = tasks.register("alignDebugNativeLibs") {
            dependsOn(tasks.named("mergeDebugNativeLibs"))
            doLast {
                val mergedDir = file("build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out")
                fileTree(mergedDir).matching { include("**/*.so") }.forEach { so ->
                    if (so.name in libsToAlign) {
                        ProcessBuilder("python3", alignScript.absolutePath, so.absolutePath)
                            .directory(project.projectDir)
                            .inheritIO()
                            .start()
                            .waitFor()
                    }
                }
            }
        }
        tasks.named("stripDebugDebugSymbols") { dependsOn(alignDebugTask) }
        tasks.named("packageDebug") { dependsOn(alignDebugTask) }
    }
}
