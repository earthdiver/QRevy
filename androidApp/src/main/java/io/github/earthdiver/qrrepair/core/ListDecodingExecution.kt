package io.github.earthdiver.qrrepair.core

import io.github.earthdiver.qrrepair.localizedText

enum class ListDecodingStage { WITHOUT_ASSIGNMENTS, ADDITIONAL }

enum class ListDecodingStatus {
    NOT_REACHED, RUNNING, COMPLETED, INELIGIBLE, DEFERRED, TIME_LIMIT, WORKING_MEMORY_LIMIT, SEARCH_LIMIT,
    INTERRUPTED, INTERNAL_ERROR, ORIGINAL_RS_SUCCEEDED, ZERO_ERROR_LIMIT, INPUT_CONSTRAINT,
}

/** Decisions survive candidate eviction and are aggregated independently of decoder trial counts. */
data class ListDecodingExecution(
    val stages: Map<ListDecodingStage, Set<ListDecodingStatus>> = emptyMap(),
    val newTrials: Map<ListDecodingStage, Int> = emptyMap(),
) {
    fun description(): String = stages.entries.filter { (stage, _) ->
        newTrials.getOrDefault(stage, 0) > 0
    }.sortedBy { it.key.ordinal }.joinToString("\n") { (stage, statuses) ->
        val label = when (stage) {
            ListDecodingStage.WITHOUT_ASSIGNMENTS -> localizedText("仮置きなしのリスト復号", "List decoding without tentative assignments")
            ListDecodingStage.ADDITIONAL -> localizedText("仮置きも使うリスト復号", "List decoding including tentative assignments")
        }
        val scope = if (statuses.size > 1) localizedText("（複数の読み取り試行の集計）", " (across sampled inputs)") else ""
        label + scope + ": " + statuses.sortedBy { it.ordinal }.joinToString("; ") { status -> when (status) {
            ListDecodingStatus.NOT_REACHED -> localizedText("この段階には到達していません", "this stage was not reached")
            ListDecodingStatus.RUNNING -> localizedText("実行中", "running")
            ListDecodingStatus.COMPLETED -> localizedText("適用条件の確認と、適用可能な探索を実行しました", "eligibility checks and applicable searches completed")
            ListDecodingStatus.INELIGIBLE -> localizedText("適用条件を満たさないため、リスト復号の計算は実行しませんでした。", "List-decoding calculations were not run because the eligibility conditions were not met.")
            ListDecodingStatus.DEFERRED -> localizedText("先行枠の時間上限に達し、残りを追加探索へ回しました", "the initial time allowance was reached; remaining work was deferred to additional search")
            ListDecodingStatus.TIME_LIMIT -> localizedText("リスト復号全体の時間上限により未完了です", "unfinished at the cumulative list-decoding time limit")
            ListDecodingStatus.WORKING_MEMORY_LIMIT -> localizedText("計算用メモリの上限により未完了です", "unfinished at the working-memory limit")
            ListDecodingStatus.SEARCH_LIMIT -> localizedText("所定の探索範囲で終了し、未検査の組合せがあります", "stopped at the search boundary; some combinations remain unchecked")
            ListDecodingStatus.INTERRUPTED -> localizedText("利用者の中断により未完了、または未開始です", "unfinished or not started because the user interrupted repair")
            ListDecodingStatus.INTERNAL_ERROR -> localizedText("内部エラーにより未完了、または未開始です", "unfinished or not started because of an internal error")
            ListDecodingStatus.ORIGINAL_RS_SUCCEEDED -> localizedText("未実行：元の観測値に対する通常RSで検証済み候補を得たためです。正解の証明ではありません", "not run: conventional RS on the original observations produced a validated candidate; this is not proof of correctness")
            ListDecodingStatus.ZERO_ERROR_LIMIT -> localizedText("未実行：データ・ECCの誤り上限が0bitのためです", "not run: the data/ECC error limit is zero bits")
            ListDecodingStatus.INPUT_CONSTRAINT -> localizedText("未実行：入力と指定条件が両立しないためです", "not run: the input conflicts with the selected conditions")
        } }
    }

    companion object {
        fun combine(parts: List<ListDecodingExecution>): ListDecodingExecution? = if (parts.isEmpty()) null else
            ListDecodingExecution(ListDecodingStage.entries.mapNotNull { stage ->
                parts.flatMapTo(linkedSetOf()) { it.stages[stage].orEmpty() }.takeIf { it.isNotEmpty() }?.let { stage to it }
            }.toMap(), newTrials = ListDecodingStage.entries.associateWith { stage ->
                parts.sumOf { it.newTrials.getOrDefault(stage, 0) }
            })
    }
}
