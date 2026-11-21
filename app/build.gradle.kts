import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Signaturdaten liegen lokal in keystore.properties (nicht in Git).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = !keystoreProperties.isEmpty

android {
    namespace = "com.example.kanban"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.kanban"
        minSdk = 26 // Jetpack Ink benötigt API 26+
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Gleicher Schlüssel wie Release: USB-Testversionen und Obtainium-Updates
            // lassen sich gegenseitig überinstallieren, ohne dass Daten verloren gehen.
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            // R8-Optimierung bewusst aus: Die Teil-Optimierung von kotlin.** hat
            // Klassen versteckt, von denen das Onyx SDK erbt (IllegalAccessError
            // beim Start, v1.0). Das Onyx SDK nutzt viel Reflection – ohne
            // Optimierung ist die App größer, aber zuverlässig.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // Onyx SDK und Jetpack Ink bringen teils dieselben nativen Libs mit.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

configurations.configureEach {
    // Onyx SDK zieht die alte Support-Library mit, die mit AndroidX kollidiert.
    exclude(group = "com.android.support")
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Jetpack Ink (Handschrift)
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.nativeloader)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.ink.storage)
    implementation(libs.androidx.ink.authoring.android)
    implementation(libs.androidx.ink.authoring.compose)
    implementation(libs.androidx.ink.brush.compose)
    implementation(libs.androidx.ink.geometry.compose)
    implementation(libs.androidx.input.motionprediction)

    // Room (Speicherung)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Onyx Boox Pen SDK (verzögerungsfreies Schreiben auf E-Ink)
    implementation(libs.onyx.sdk.pen)
    implementation(libs.onyx.sdk.device)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
