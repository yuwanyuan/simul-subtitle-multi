plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.korenkrita.simulsub"
    compileSdk = 35

    defaultConfig {
        // The fork's own id: the upstream builds sign with the author's keystore and this fork signs with the CI's
        // cached one, so the same id would mean an install that cannot be upgraded by the other side (see AGENTS.md
        // 签名). A distinct id also lets the two coexist. namespace (the code package) stays upstream's: renaming it
        // would touch every file and turn each upstream sync into a conflict.
        applicationId = "io.github.yuwanyuan.simulsub"
        minSdk = 29 // AudioPlaybackCapture
        targetSdk = 35
        // The fork's own code, above the installed upstream value (27): the in-app updater compares versionCode
        // only, so staying at 27 would keep every release "已是最新" forever. Formula (AGENTS.md 版本号): the
        // upstream code × 100 + this fork's release number, so the next fork releases count 2702, 2703… and an
        // upstream sync to code 28 resumes at 2801.
        versionCode = 2703
        // The fork's own scheme (AGENTS.md 版本号): the upstream version it follows, then -multi.<this fork's own
        // release number>, counted from 1 and reset when the upstream part changes.
        versionName = "1.0.7-multi.3"
        ndk { abiFilters += "arm64-v8a" }
    }

    // the speech models in assets/models (setup.sh): stored, so sherpa-onnx reads them from the APK without inflating
    androidResources { noCompress += "onnx" }

    signingConfigs {
        // One key signs every build — local, any machine, CI — so an APK always upgrades over another build of this
        // applicationId. The key itself is NOT in the repo (keystore/ is gitignored): locally it is the file below,
        // generated once with keytool (see AGENTS.md 签名); on CI the same path is restored from the
        // SIMULSUB_KEYSTORE_B64 secret. A build without it fails at signing instead of quietly using another key —
        // that is how local and CI APKs stopped upgrading over each other on 2026-10-17/18 (three debug keys in two
        // days: the CI keystore cache reported a hit every run and the runner still signed with something else).
        getByName("debug") {
            val key = rootProject.file("keystore/fork-debug.keystore")
            if (!key.isFile) logger.warn("签名 keystore 不在 ${key.path}：打包会失败。本地用 keytool 生成一把，CI 由 SIMULSUB_KEYSTORE_B64 还原（见 AGENTS.md 签名）")
            storeFile = key
            storePassword = System.getenv("SIMULSUB_KEYSTORE_PASSWORD") ?: "simulsub-debug"
            keyAlias = "fork-debug"
            keyPassword = System.getenv("SIMULSUB_KEYSTORE_PASSWORD") ?: "simulsub-debug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // The sherpa-onnx AAR also ships its C and C++ API libraries; the Kotlin API only loads libsherpa-onnx-jni.so, which links none of their symbols.
    // useLegacyPackaging: .so files are stored compressed in the APK (release APK 13.6 MB at d83a627) and extracted
    // on install, so the installed footprint is larger (APK + extracted libraries) and the first install is slower.
    packaging {
        jniLibs {
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Jev and online MT: a Call can be cancelled before it runs, so stop never lets a request go out afterwards
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android but stubbed out in local unit tests; the reference implementation stands in
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
