package io.github.earthdiver.qrrepair

import android.app.Activity
import android.app.Dialog
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat

/** Bounds available to app controls, in screen coordinates; never subtract bars twice. */
internal fun safeWindowBounds(activity: Activity): Rect {
    if (Build.VERSION.SDK_INT >= 30) {
        val metrics = activity.windowManager.currentWindowMetrics
        val bars = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
            .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return Rect(metrics.bounds).apply {
            left += bars.left
            top += bars.top
            right -= bars.right
            bottom -= bars.bottom
        }
    }
    // Older floating windows already fit the visible display frame. This also
    // follows split-screen bounds without subtracting their system bars again.
    return Rect().also { bounds ->
        activity.window.decorView.getWindowVisibleDisplayFrame(bounds)
        if (bounds.isEmpty) bounds.set(0, 0, activity.resources.displayMetrics.widthPixels,
            activity.resources.displayMetrics.heightPixels)
    }
}

/** Keep dialog height independent of its text, updating only when the viewport changes. */
internal fun fitDialogToViewport(activity: Activity, dialog: Dialog, heightFraction: Float, widthFraction: Float? = null) {
    val window = dialog.window ?: return
    val decor = window.decorView
    var lastBounds: Rect? = null
    fun update() {
        val bounds = safeWindowBounds(activity)
        if (bounds.isEmpty || bounds == lastBounds) return
        lastBounds = Rect(bounds)
        window.setLayout(widthFraction?.let { (bounds.width() * it).toInt().coerceAtLeast(1) }
            ?: WindowManager.LayoutParams.WRAP_CONTENT,
            (bounds.height() * heightFraction).toInt().coerceAtLeast(1))
    }
    val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
    var observer: ViewTreeObserver? = null
    fun attach() {
        if (observer != null) return
        observer = activity.window.decorView.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
        update()
    }
    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = attach()
        override fun onViewDetachedFromWindow(view: View) {
            observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
            observer = null
            lastBounds = null
        }
    })
    attach()
}

/** Insets delivered here are the portion not already consumed by the window decor. */
internal fun protectSystemBars(view: View) {
    val left = view.paddingLeft
    val top = view.paddingTop
    val right = view.paddingRight
    val bottom = view.paddingBottom
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        insets
    }
}
