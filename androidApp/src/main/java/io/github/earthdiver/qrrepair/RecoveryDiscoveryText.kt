package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.RecoveryDiscoveryCount

internal fun discoveryCountText(count: RecoveryDiscoveryCount, japanese: Boolean): String = when (count) {
    is RecoveryDiscoveryCount.Exact -> if (japanese) "見つかった候補: ${count.value}件" else "Candidates found: ${count.value}"
    is RecoveryDiscoveryCount.AtLeast -> if (japanese) "見つかった候補: ${count.value}件以上" else "Candidates found: at least ${count.value}"
}
