package io.github.earthdiver.qrrepair

private val workspaceSessionField = MainActivity::class.java.getDeclaredField("workspaceSession").apply {
    isAccessible = true
}

/** Keep Activity fixture reflection at the coordinator boundary. */
internal fun MainActivity.workspaceSessionForTest(): WorkspaceSessionCoordinator =
    workspaceSessionField.get(this) as WorkspaceSessionCoordinator
