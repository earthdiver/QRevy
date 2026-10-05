package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Shows repair phases and, only while applicable, priority assignments for the current grid. */
internal class RecoveryProgressDialog(activity: Activity, priorityCount: Int) {
    val stopped = AtomicBoolean(false)
    private val done = AtomicInteger(0)
    private val total = AtomicInteger(0)
    private val gsActive = AtomicBoolean(false)
    private val hasPriority = priorityCount > 0
    private val phase = AtomicReference(localizedText("復元を準備中", "Preparing repair"))
    private val started = SystemClock.elapsedRealtime()
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private val dialog = AlertDialog.Builder(activity)
        .setTitle(localizedText("修復の進捗", "Repair progress"))
        .setMessage("")
        .setCancelable(false)
        .setNegativeButton(localizedText("修復を中断", "Interrupt repair"), null)
        .create()
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000
            dialog.setMessage(buildString {
                append(phase.get())
                if (hasPriority && total.get() > 0) {
                    append("\n").append(localizedText(
                        "現在の格子の*探索: ${done.get()} / ${total.get()}通り",
                        "* combinations completed for current grid: ${done.get()} / ${total.get()}"))
                } else if (hasPriority && !gsActive.get()) {
                    append("\n").append(localizedText(
                        "通常処理を実行中（必要な場合に*探索を行います）",
                        "Running ordinary recovery; * search runs when needed"))
                }
                append("\n").append(localizedText("経過時間: ${seconds}秒", "Elapsed: ${seconds}s"))
            })
            handler.postDelayed(this, 250)
        }
    }
    init {
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                stopped.set(true)
                phase.set(localizedText("中断処理中…見つかった候補を保持します", "Interrupting… retaining candidates found so far"))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            }
        }
        dialog.show()
        handler.post(ticker)
    }
    fun priorityProgress(completed: Int, count: Int) {
        if (stopped.get()) return
        done.set(completed)
        total.set(count)
    }
    fun phase(message: String) {
        if (!stopped.get()) { gsActive.set(false); phase.set(message) }
    }
    fun gsProgress(elapsedMillis: Long, limitMillis: Long) {
        if (stopped.get()) return
        gsActive.set(true)
        phase.set(localizedText(
            "GSによる追加探索: 累計${elapsedMillis / 1000} / ${limitMillis / 1000}秒",
            "GS fallback: ${elapsedMillis / 1000} / ${limitMillis / 1000}s cumulative"))
    }
    fun close() {
        closed = true
        handler.removeCallbacks(ticker)
        dialog.dismiss()
    }
}
