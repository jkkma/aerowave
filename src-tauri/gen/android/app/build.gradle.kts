import java.util.Properties
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

fun versionCodeFor(versionName: String): Int {
    val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matchEntire(versionName)
        ?: throw GradleException("Android releases require a stable major.minor.patch version; found '$versionName'.")
    val major = match.groupValues[1].toLong()
    val minor = match.groupValues[2].toLong()
    val patch = match.groupValues[3].toLong()
    if (minor > 999 || patch > 999) {
        throw GradleException("Android minor and patch versions must each be at most 999.")
    }
    val code = major * 1_000_000L + minor * 1_000L + patch
    if (code !in 1..2_100_000_000L) {
        throw GradleException("The derived Android version code $code is outside the supported range.")
    }
    return code.toInt()
}

val aerowaveVersionName = tauriProperties.getProperty("tauri.android.versionName", "")
val aerowaveVersionCode = versionCodeFor(aerowaveVersionName)
tauriProperties.getProperty("tauri.android.versionCode")?.toIntOrNull()?.let { generatedCode ->
    if (generatedCode != aerowaveVersionCode) {
        throw GradleException(
            "Tauri generated Android version code $generatedCode, but $aerowaveVersionName reproducibly maps to $aerowaveVersionCode."
        )
    }
}

val releaseSigningEnvironment = mapOf(
    "storeFile" to System.getenv("AEROWAVE_ANDROID_KEYSTORE_FILE"),
    "storePassword" to System.getenv("AEROWAVE_ANDROID_STORE_PASSWORD"),
    "keyAlias" to System.getenv("AEROWAVE_ANDROID_KEY_ALIAS"),
    "keyPassword" to System.getenv("AEROWAVE_ANDROID_KEY_PASSWORD"),
)
val releaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val missingReleaseSigning = releaseSigningEnvironment.filterValues { it.isNullOrEmpty() }.keys
if (releaseTaskRequested && missingReleaseSigning.isNotEmpty()) {
    throw GradleException(
        "Release signing is incomplete (${missingReleaseSigning.joinToString()}). Use tools/android-build.ps1 -Release."
    )
}

android {
    compileSdk = 36
    namespace = "com.aerowave.radio"
    defaultConfig {
        // Ordinary radio uses the private loopback relay; some broadcasters
        // also offer their public HLS segments over HTTP.
        manifestPlaceholders["usesCleartextTraffic"] = "true"
        applicationId = "com.aerowave.radio"
        minSdk = 26
        targetSdk = 36
        versionCode = aerowaveVersionCode
        versionName = aerowaveVersionName
    }
    signingConfigs {
        if (missingReleaseSigning.isEmpty()) {
            create("aerowaveRelease") {
                storeFile = file(releaseSigningEnvironment.getValue("storeFile")!!)
                storePassword = releaseSigningEnvironment.getValue("storePassword")
                keyAlias = releaseSigningEnvironment.getValue("keyAlias")
                keyPassword = releaseSigningEnvironment.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            // Keep native symbols in Cargo's target directory rather than
            // shipping hundreds of megabytes of debug information to a phone.
            isJniDebuggable = false
            isMinifyEnabled = false
            packaging {
                jniLibs.useLegacyPackaging = true
            }
        }
        getByName("release") {
            if (missingReleaseSigning.isEmpty()) {
                signingConfig = signingConfigs.getByName("aerowaveRelease")
            }
            isMinifyEnabled = true
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = "tauri.build.gradle.kts")
