plugins {
    id("com.android.application")
}

// Gradle's JVM properties are not inherited by forked test JVMs. Forward only this
// supported control and declare it as an input so changing the count reruns tests.
val recoveryFuzzIterations = providers.systemProperty("qr.fuzz.iterations").orElse("24")
// Full coverage is the default. Quick coverage is for intermediate feedback;
// app coverage is selected only for changes outside recovery/build configuration.
val changeCheckScope = providers.gradleProperty("qr.testScope").orElse("full")
require(changeCheckScope.get() in setOf("full", "app", "quick")) { "qr.testScope must be full, app, or quick" }
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.property("qr.fuzz.iterations", recoveryFuzzIterations)
    systemProperty("qr.fuzz.iterations", recoveryFuzzIterations.get())
    // Extended CI runs expose the completed-count message as well as retaining it in test reports.
    testLogging.showStandardStreams = providers.systemProperty("qr.fuzz.iterations").isPresent
    if (name == "testDebugUnitTest" && changeCheckScope.get() != "full") {
        filter {
            if (changeCheckScope.get() == "app") {
                excludeTestsMatching("io.github.earthdiver.qrrepair.core.GeometryNeighborhoodDisplacementRegressionTest.equalDisplacementSweepRecoversSyntheticQrAndKeepsTransformsComparable")
            }
            excludeTestsMatching("io.github.earthdiver.qrrepair.core.GeometryNeighborhoodExpandedRegressionTest.exactWidthCorrectionsAndOneFinderRotationRecoverSupportedPerturbations")
        }
    }
}

android {
    namespace = "io.github.earthdiver.qrrepair"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.earthdiver.qrrepair"
        minSdk = 26
        targetSdk = 37
        versionCode = 52
        versionName = "0.19.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // The app switches between Japanese and English offline at runtime. Keep both resource sets
    // installed instead of letting an App Bundle deliver only the device's initial language.
    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        htmlReport = true
        textReport = true
        sarifReport = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.github.zxing-cpp:android:3.1.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
