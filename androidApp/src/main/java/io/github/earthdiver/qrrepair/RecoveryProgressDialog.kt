package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Displays completed priority assignments for the current grid, independently of solver threads. */
internal class RecoveryProgressDialog(activity: Activity, priorityCount: Int) {
    val stopped = AtomicBoolean(false)
    private val done = AtomicInteger(0)
    private val total = AtomicInteger(if (priorityCount == 0) 0 else 1 shl priorityCount)
    private val phase = AtomicReference(localizedText("復元を準備中", "Preparing repair"))
    private val started = SystemClock.elapsedRealtime()
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private val dialog = AlertDialog.Builder(activity)
        .setTitle(localizedText("修復の進捗", "Repair progress"))
        .setMessage("")
        .setCancelable(false)
        .setNegativeButton(localizedText("中断", "Interrupt"), null)
        .create()
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000
            dialog.setMessage(phase.get() + "\n" + localizedText(
                "現在の格子の*探索: ${done.get()} / ${total.get()}通り\n経過時間: ${seconds}秒",
                "* combinations completed for current grid: ${done.get()} / ${total.get()}\nElapsed: ${seconds}s"))
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
        done.set(completed)
        total.set(count)
    }
    fun phase(message: String) {
        if (!stopped.get()) phase.set(message)
    }
    fun close() {
        closed = true
        handler.removeCallbacks(ticker)
        dialog.dismiss()
    }
}
