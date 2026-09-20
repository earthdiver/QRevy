plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.earthdiver.qrrepair"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.earthdiver.qrrepair"
        minSdk = 26
        targetSdk = 37
        versionCode = 45
        versionName = "0.16.0"
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
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
