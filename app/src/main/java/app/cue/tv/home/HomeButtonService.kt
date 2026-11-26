package app.cue.tv.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

// The remote's Home button opens Cue Home, even on a TV that sends it to
// Google's home screen whatever the default is: Home is caught here, and so
// is Google's home screen coming to the front. Nothing on the screen is read.
// After Horizon Home Kit's HomeButtonService (MIT licence,
// github.com/jyotiprakash101/horizon-home-kit).
class HomeButtonService : AccessibilityService() {
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) goHome()
        return true
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: return
        // Only Google's home screen itself, not its other screens (settings, inputs).
        if ((pkg == "com.google.android.tvlauncher" && cls.endsWith("MainActivity")) ||
            (pkg == "com.google.android.apps.tv.launcherx" && cls.endsWith("HomeActivity"))
        ) goHome()
    }

    override fun onInterrupt() {}

    private fun goHome() {
        runCatching {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(HomeActivity.GO_HOME, true),
            )
        }
    }
}
