import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    // Keep a plain JVM target for fast, Android-SDK-independent algorithm tests.
    jvm()

    // Dedicated Android-KMP library target. The Android application consumes this variant.
    android {
        namespace = "io.github.earthdiver.qrrepair.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val xcf = XCFramework("QRRepairCore")
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64(),
    )
    iosTargets.forEach { target ->
        target.binaries.framework {
            baseName = "QRRepairCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Keep commonMain stdlib-only while the cross-platform boundary stabilizes.
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
