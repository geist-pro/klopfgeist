import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Upload key for Play App Signing. Lives outside the repo; see play/SUBMISSION.md.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Form factors and layout checks shared with the Android workspace's apps (sources of `de.kaikunze.screentest`).
// -Pscreentest=<dir> or MARGINVOICE_SCREENTEST; by default the workspace next to the home directory's `work/areas`.
// Without it the screenshot sweep (src/screenTest: ScreenshotTest) is left out; every other test runs.
val screentest = file(
    (findProperty("screentest") as String?) ?: System.getenv("MARGINVOICE_SCREENTEST")
        ?: "${System.getProperty("user.home")}/work/areas/android/studio/screentest",
)

android {
    namespace = "de.kaikunze.marginvoice"
    compileSdk = 37

    defaultConfig {
        applicationId = "pro.geist.klopfgeist"
        // Android 13: the speech recognizer takes audio from a file and biasing strings.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Vosk ships native code for every ABI; phones are arm64, emulators x86_64.
        // The beta APK is phones only: -Pabi=arm64-v8a (tools/release-beta.sh).
        ndk { abiFilters += (findProperty("abi") as String?)?.split(",") ?: listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore.properties the release is debug-signed: fine for testing R8 on a device, rejected by Play.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric runs the Compose UI tests and the screenshot sweep on the JVM.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
    }

    // The device UI tests also run under Robolectric (testDebugUnitTest).
    sourceSets["test"].kotlin.directories.add("src/androidTest/java")
    // ScreenshotTest lives in src/screenTest, since it only compiles with the screentest sources.
    if (screentest.exists()) {
        sourceSets["test"].kotlin.directories.add(screentest.path)
        sourceSets["test"].kotlin.directories.add("src/screenTest/java")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":session"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.vosk.android)
    implementation(libs.pdfbox.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
