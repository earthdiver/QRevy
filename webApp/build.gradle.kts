import org.teavm.gradle.api.JSModuleType

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.teavm") version "0.16.0"
}

repositories { mavenCentral() }
dependencies {
    implementation(teavm.libs.jso)
    testImplementation(kotlin("test-junit"))
}
kotlin { jvmToolchain(17) }

val repository = layout.projectDirectory.dir("..").asFile.canonicalFile
fun sourceList(name: String) = file(name).readLines()
    .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
val sharedSources = sourceList("shared-sources.txt")
val sharedTests = sourceList("shared-tests.txt")
layout.buildDirectory.set(repository.resolve("build/webApp"))

sourceSets {
    main {
        kotlin.srcDirs(repository.resolve("shared/src/commonMain/kotlin"),
            repository.resolve("androidApp/src/main/java/io/github/earthdiver/qrrepair"))
        kotlin.exclude { entry ->
            val path = entry.file.relativeTo(repository).invariantSeparatorsPath
            !entry.isDirectory && !path.startsWith("webApp/") && path !in sharedSources
        }
        java.srcDirs(repository.resolve("androidApp/src/main/java/com/google/zxing/qrcode/decoder"), "vendor/zxing")
    }
    test {
        kotlin.srcDirs(repository.resolve("shared/src/commonTest/kotlin"),
            repository.resolve("androidApp/src/test/java"))
        kotlin.exclude { entry ->
            val path = entry.file.relativeTo(repository).invariantSeparatorsPath
            !entry.isDirectory && !path.startsWith("webApp/") && path !in sharedTests
        }
    }
}

val checkSourceOverrides = tasks.register<Exec>("checkSourceOverrides") {
    workingDir(projectDir)
    commandLine("node", "scripts/check-source-overrides.mjs")
}
tasks.named("compileKotlin") { dependsOn(checkSourceOverrides) }

teavm.js {
    mainClass = "qrevy.web.Bridge"
    moduleType = JSModuleType.ES2015
    targetFileName = "engine.js"
    outputDir = layout.projectDirectory.dir("web/engine")
    obfuscated = true
}
