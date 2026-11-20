import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val releaseSigningEnvironment = listOf(
    "NOID_RELEASE_STORE_FILE",
    "NOID_RELEASE_STORE_PASSWORD",
    "NOID_RELEASE_KEY_ALIAS",
    "NOID_RELEASE_KEY_PASSWORD"
).associateWith { providers.environmentVariable(it).orNull }

val releaseSigningConfigured = releaseSigningEnvironment.values.all { !it.isNullOrBlank() }

android {
    namespace = "ai.noid.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.noid.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseSigningEnvironment.getValue("NOID_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningEnvironment.getValue("NOID_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("NOID_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("NOID_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Release signing is enabled only when all four values are injected
            // by the local/CI secret manager. No signing material belongs in Git.
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Compile app bytecode for Android-compatible Java 17 while KAPT reads
        // Java 21 class files shipped by the LiteRT-LM 0.18.1 dependency.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.documentfile:documentfile:1.0.1")
    // Fixed LiteRT-LM version; keep model weights in app-private storage, not the APK.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("androidx.room:room-testing:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Use a real JVM JSON implementation instead of Android's mocked org.json stubs.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
