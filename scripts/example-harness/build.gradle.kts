plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.code.gson:gson:2.13.1")
}

kotlin { jvmToolchain(17) }

sourceSets {
    main {
        kotlin.srcDirs("../../shared/src/commonMain/kotlin",
            "../../androidApp/src/main/java/io/github/earthdiver/qrrepair/core")
        // Desktop verification uses the Java geometry adapter; Android JNI stays in the app.
        kotlin.exclude("**/ZxingCppGridDetector.kt")
        java.srcDir("../../androidApp/src/main/java/com/google/zxing/qrcode/decoder")
    }
}

application { mainClass.set("evaluation.SampleStudyKt") }
dependencyLocking { lockAllConfigurations() }
