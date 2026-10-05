plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apkrepacker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.apkrepacker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/**"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // --- APK processing toolchain (all pure-Java/Kotlin, runs on-device) ---

    // Google's official APK signing + verification library (APK Sig Scheme v1/v2/v3/v4).
    implementation("com.android.tools.build:apksig:8.7.3")

    // smali/baksmali dexlib2: DEX-aware reading and rewriting of class/method/field
    // references. Pure Java, runs on Android. Used for conservative namespace rewrites.
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")

    // Binary AndroidManifest.xml (AXML) decode is implemented in-tree against the
    // documented chunk format (com.apkrepacker.apk.axml) — no native tooling needed.

    testImplementation("junit:junit:4.13.2")
    // Test-only: generate an ephemeral self-signed signing key at runtime, so no keystore
    // is committed to the repo. Not shipped in the app.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
