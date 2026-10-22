plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.minicam.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.minicam.mobile"
        minSdk = 26
        targetSdk = 37
        // CI passes -PversionCode=<run number> so each published APK upgrades the previous one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.2.0"
    }

    // Release key comes from the environment (CI secrets, docs/adr/0003). Without it, release
    // builds are signed with the debug key so they stay installable for local testing.
    val releaseKeystore = System.getenv("MINICAM_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MINICAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MINICAM_KEY_ALIAS")
                keyPassword = System.getenv("MINICAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Suggests androidx.core-ktx helpers; not worth a dependency (docs/DEPENDENCIES.md).
        disable += "UseKtx"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Lockfile committed (docs/DEPENDENCIES.md): ./gradlew dependencies --write-locks
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.browser)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}
