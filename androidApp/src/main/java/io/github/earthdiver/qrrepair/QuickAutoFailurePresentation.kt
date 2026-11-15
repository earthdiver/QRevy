package io.github.earthdiver.qrrepair

import io.github.earthdiver.qrrepair.core.QuickAutoFailureReason

internal fun QuickAutoFailureReason.message(): String = when (this) {
    QuickAutoFailureReason.TIME_LIMIT -> localizedText("制限時間に達した処理があり、修復結果を確定できませんでした。",
        "Some processing reached its time limit before a repair result could be confirmed.")
    QuickAutoFailureReason.NO_INITIAL_GRID -> localizedText("QRコードの四隅・バージョンを推定できませんでした。詳細解析で指定できます。",
        "The QR corners and version could not be estimated. You can specify them in detailed analysis.")
    QuickAutoFailureReason.MULTIPLE_CANDIDATES -> localizedText("複数の候補が見つかり、結果を1件に絞れませんでした。",
        "Multiple candidates were found; a single result could not be established.")
    QuickAutoFailureReason.SEARCH_LIMIT -> localizedText("探索量またはメモリの上限に達し、検証を完了できませんでした。",
        "A search or memory limit prevented verification from finishing.")
    QuickAutoFailureReason.UNRESOLVED -> localizedText("未解決の検証が残り、修復結果を確定できませんでした。",
        "Unresolved checks remain, so a repair result could not be confirmed.")
    QuickAutoFailureReason.NO_CANDIDATE -> localizedText("今回試した条件では、検証を通る修復候補が見つかりませんでした。",
        "No verified repair candidate was found under the conditions tried.")
    QuickAutoFailureReason.INTERRUPTED -> localizedText("解析を中断しました。", "Analysis was stopped.")
    QuickAutoFailureReason.INTERNAL_ERROR -> localizedText("処理中にエラーが発生し、解析を完了できませんでした。",
        "A processing error prevented analysis from finishing.")
}
