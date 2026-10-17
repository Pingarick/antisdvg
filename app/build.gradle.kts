import java.time.LocalDate

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/** Reads a value from local.properties (gitignored), null when absent. */
fun localProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    val properties = Properties()
    file.inputStream().use { properties.load(it) }
    return properties.getProperty(name)
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

        // DeepSeek API key: taken from local.properties (machine-local, gitignored)
        // or from -PDEEPSEEK_API_KEY on the command line.
        //
        // It is still baked into the APK — the app has to carry it to work out of
        // the box — but it is no longer in the sources or in git history: this
        // repository has a public remote, and a key in the sources is a key
        // published. A machine without the key still builds; the app then says so
        // and the key can be pasted into its settings.
        val deepSeekKey = localProperty("DEEPSEEK_API_KEY")
            ?: project.gradle.startParameter.projectProperties["DEEPSEEK_API_KEY"]
            ?: ""
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
