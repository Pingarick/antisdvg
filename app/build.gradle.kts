import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.antisdvg"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antisdvg"
        minSdk = 24
        targetSdk = 34
        // Build numbering: git commit count + date, so every APK is distinguishable.
        // Installed app keeps working: versionCode only grows.
        val gitCommits = try {
            providers.exec {
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText.get().trim().toInt()
        } catch (_: Exception) {
            0
        }
        val buildDate = LocalDate.now().toString().replace("-", "")
        versionCode = 100 + gitCommits
        versionName = "1.0.0-$gitCommits ($buildDate)"
        setProperty("archivesBaseName", "anti-sdvg-1.0.0-${gitCommits}-${buildDate}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // DeepSeek API key, baked into the app.
        //
        // Until 2026-10-17 the key came only from ~/.gradle/gradle.properties so
        // the secret never reached the repo. The owner then asked for it to ship
        // inside the app ("пока что это только для меня, поэтому включай сразу
        // этот ключ в приложение, потом если что уберём") — so the constant below
        // is what the build uses, and it IS a secret in the sources and in git
        // history. Override a build with -PDEEPSEEK_API_KEY=... ; the value in
        // ~/.gradle is deliberately not consulted, it still holds the older key.
        val builtInDeepSeekKey = "REDACTED_IN_HISTORY"
        val deepSeekKey = project.gradle.startParameter.projectProperties["DEEPSEEK_API_KEY"]
            ?: builtInDeepSeekKey
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekKey\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Core & UI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle / ViewModel / LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Navigation component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room (local database)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Networking (DeepSeek API)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Speech recognition: uses built-in Android SpeechRecognizer (no extra dependency)

    // Glide for book covers
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")

    // CameraX + ML Kit barcode scanning (add book by scanning ISBN on the cover)
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
