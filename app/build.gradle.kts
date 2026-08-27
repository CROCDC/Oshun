plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    jacoco
}

android {
    namespace = "com.oshun.gpsbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oshun.gpsbridge"
        minSdk = 26
        targetSdk = 35
        // Every published build gets a higher number than the one before it: CI passes the
        // run number with -PversionCode. Android refuses to install an older build over a
        // newer one, which is the behaviour you want from a "latest" download.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Which build is this? The version name never moves between debug builds, so the
        // commit is what tells you whether the phone in your hand is up to date. CI passes
        // it with -PgitSha; a local build says so.
        val gitSha = (project.findProperty("gitSha") as String?)?.take(7) ?: "local"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "RELEASES_URL", "\"https://github.com/CROCDC/Oshun/releases/tag/debug-latest\"")
    }

    /**
     * A fixed signing key, kept in the repository on purpose.
     *
     * Without it, Gradle signs debug builds with the throwaway keystore it generates on
     * whatever machine is building — and CI builds on a fresh machine every time, so every
     * APK carried a different signature. Android refuses to update an app whose signature
     * changed, which is why installing a new build over the old one always failed with a
     * flat "app not installed" and the only way through was to uninstall first.
     *
     * The password is here in plain sight because this key protects nothing: it identifies
     * a sideloaded debug build, and the store it would matter for does not exist. What it
     * does buy is that an update installs over the previous one and keeps your settings.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("oshun-debug.keystore")
            storePassword = "oshun-debug"
            keyAlias = "oshun"
            keyPassword = "oshun-debug"
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/**
 * The AIS feed's parsing tests do not run here, and that is deliberate.
 *
 * They exercise org.json, which lives in the Android framework — and a unit test in this
 * module compiles against the stubbed android.jar, where `isReturnDefaultValues` makes every
 * one of those methods hand back null. The tests would be measuring the stub, not the parser.
 *
 * They run for real in the `verify` module, on a plain JVM with the actual implementation,
 * from these very same source files. Nothing is skipped; it just happens over there.
 */
tasks.withType<Test>().configureEach {
    exclude("**/AisStreamMessagesTest*")
    exclude("**/AisSubscriptionTest*")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    // The AIS feed is a WebSocket; the platform has no client for one.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Unit tests (JVM + Robolectric)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Instrumented tests (emulator)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

jacoco {
    toolVersion = "0.8.12"
}

// Merged coverage over unit (Robolectric) + instrumented (emulator) execution data.
// Run after testDebugUnitTest and/or connectedDebugAndroidTest; it uses whatever
// execution data is present, so it works in both the fast job and the emulator job.
tasks.register<JacocoReport>("jacocoMergedReport") {
    group = "verification"
    description = "Merged unit + instrumented coverage for the app module."

    val excludes = listOf(
        "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*Test*.*", "android/**/*.*", "**/*\$Lambda\$*.*", "**/*Companion*.*",
        "**/*_Factory*.*", "**/databinding/**", "**/*ComposableSingletons*.*",
        // LocationSource is a thin Play Services / real-GPS wrapper that cannot be
        // exercised on a JVM or a headless emulator; excluded from the denominator.
        "**/location/LocationSource*.*",
        // Crash UI/IO glue (screen, handler, file store) and the Application entry
        // point are exercised only when the process actually crashes; the pure
        // report formatter lives in core/ and is unit-tested.
        "**/crash/**",
        "**/OshunApp*.*",
    )
    val kotlinClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(excludes) }
    val javaClasses = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) { exclude(excludes) }

    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files(kotlinClasses, javaClasses))
    // Match execution data wherever AGP puts it (unit .exec + instrumented .ec),
    // across AGP versions, so the report never silently skips.
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("**/*.exec", "**/*.ec") }
    )
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/merged.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/mergedHtml"))
    }
}
