plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
}

// Keep generated files from every module under one repository-level directory.
val centralizedBuildRoot = layout.projectDirectory.dir("build")

allprojects {
    val projectBuildDirectory = if (this == rootProject) {
        "root"
    } else {
        path.removePrefix(":").replace(':', '/')
    }
    layout.buildDirectory.set(centralizedBuildRoot.dir(projectBuildDirectory))
}
