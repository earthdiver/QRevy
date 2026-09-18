plugins {
    kotlin("jvm") version "2.4.10"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.boofcv:boofcv-recognition:1.1.4")
    implementation("com.google.code.gson:gson:2.13.1")
}
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDirs("../../shared/src/commonMain/kotlin", "../../androidApp/src/main/java/io/github/earthdiver/qrrepair/core")
        kotlin.exclude("**/ZxingCppGridDetector.kt") // Android JNI adapter is outside the desktop study.
        java.srcDir("../../androidApp/src/main/java/com/google/zxing/qrcode/decoder")
    }
}
application { mainClass.set("study.StudyKt") }
tasks.withType<JavaExec>().configureEach { maxHeapSize = "2g" }
dependencyLocking { lockAllConfigurations() }
