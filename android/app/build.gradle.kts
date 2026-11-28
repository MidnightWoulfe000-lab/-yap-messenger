plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.yapmessenger.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.yapmessenger.app"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so every published debug build is newer than the last.
        versionCode = System.getenv("YAP_BUILD_NUMBER")?.toIntOrNull()?.let { 1000 + it } ?: 2
        versionName = "0.2.0-debug"
        buildConfigField("String", "UPDATE_REPO", "\"MidnightWoulfe000-lab/-yap-messenger\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // CI signs with a private key supplied through environment variables (see docs/signing.md).
    // Without them, a local build uses Android's own per-machine debug key. Such a build cannot
    // update an install that CI signed, and the other way round.
    val signingKeystore = System.getenv("YAP_SIGNING_KEYSTORE_PATH")
    if (!signingKeystore.isNullOrBlank()) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("YAP_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("YAP_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("YAP_SIGNING_KEY_PASSWORD")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

tasks.named("preBuild") {
    doFirst {
        listOf("arm64-v8a", "x86_64").forEach { abi ->
            check(file("src/main/jniLibs/$abi/libyap_android.so").isFile) {
                "Build native messaging first: scripts/build-native.ps1 or scripts/build-native.sh"
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's own org.json is a stub in JVM unit tests
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
