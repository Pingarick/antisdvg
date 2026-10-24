import java.io.File
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

        // DeepSeek API key. Machine-local, never in git: environment variable,
        // local.properties, or -PDEEPSEEK_API_KEY, in that order.
        //
        // It is still baked into the APK — the app has to carry it to work out of
        // the box — but it is no longer in the sources or in git history: this
        // repository has a public remote, and a key in the sources is a key
        // published. A machine without the key still builds; the app then says so
        // and the key can be pasted into its settings.
        // Lookup order: environment variable -> local.properties -> -P flag.
        // The env var keeps the key out of the project tree entirely (useful on a
        // shared machine and in CI); local.properties stays the convenient default.
        val deepSeekKey = System.getenv("DEEPSEEK_API_KEY")?.takeUnless { it.isBlank() }
            ?: localProperty("DEEPSEEK_API_KEY")
            ?: project.gradle.startParameter.projectProperties["DEEPSEEK_API_KEY"]
            ?: ""
        // A key with a quote or a backslash would otherwise break the generated Java.
        fun javaString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        buildConfigField("String", "DEEPSEEK_API_KEY", javaString(deepSeekKey))

    // Google Books API key: optional. Without it the free lookup quota is shared
    // with everyone on the same IP and runs out (HTTP 429), and a scanned ISBN
    // cannot be resolved. A key is free and gives this app its own quota.
    val googleBooksKey = System.getenv("GOOGLE_BOOKS_KEY")?.takeUnless { it.isBlank() }
        ?: localProperty("GOOGLE_BOOKS_KEY")
        ?: project.gradle.startParameter.projectProperties["GOOGLE_BOOKS_KEY"]
        ?: ""
    buildConfigField("String", "GOOGLE_BOOKS_KEY", javaString(googleBooksKey))
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

/**
 * The installable artefact always lives in one place: `release/` in the project
 * root. `gradlew :app:apk` builds the debug APK and copies it there twice — a
 * stable name and a dated snapshot, so an older build is never overwritten
 * without a trace.
 */
val apkOutputDir = layout.buildDirectory.dir("outputs/apk/debug")
val apkReleaseDir = rootProject.layout.projectDirectory.dir("release")
val apkBaseName = "Анти-СДВГ"

tasks.register("apk") {
    group = "build"
    description = "Builds the debug APK and copies it to release/."
    dependsOn("assembleDebug")

    doLast {
        val builtDir = apkOutputDir.get().asFile
        val built = builtDir.listFiles { file -> file.isFile && file.extension == "apk" }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No APK in ${builtDir.absolutePath}")
        val targetDir = apkReleaseDir.asFile.apply { mkdirs() }
        val stable = File(targetDir, "$apkBaseName.apk")
        val dated = File(targetDir, "$apkBaseName-${LocalDate.now()}.apk")
        built.copyTo(stable, overwrite = true)
        built.copyTo(dated, overwrite = true)
        logger.lifecycle("APK: ${stable.absolutePath}")
        logger.lifecycle("APK: ${dated.absolutePath}")
    }
}
