package com.aerowave.radio

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.webkit.WebView
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import com.aerowave.audio.AlarmPlaybackService

class MainActivity : TauriActivity() {
  private var appWebView: WebView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    showAlarmOverLockScreen(intent)
    // Keep every WebView control inside system bars, cutouts and the keyboard.
    // Consuming these insets avoids applying the same space again in CSS.
    val content = findViewById<android.view.View>(android.R.id.content)
    content.setBackgroundColor(Color.rgb(3, 24, 36))
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val safe = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
          WindowInsetsCompat.Type.ime(),
      )
      view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
      WindowInsetsCompat.CONSUMED
    }
    WindowInsetsControllerCompat(window, content).apply {
      isAppearanceLightStatusBars = false
      isAppearanceLightNavigationBars = false
    }
    ViewCompat.requestApplyInsets(content)
  }

  override fun onWebViewCreate(webView: WebView) {
    super.onWebViewCreate(webView)
    appWebView = webView
    // Wry finishes its WebView setup after this callback returns. Apply the
    // process-wide setting on the UI queue to disable release inspection
    // after initialization while debug builds remain inspectable.
    webView.post {
      val debuggable = BuildConfig.DEBUG &&
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
      WebView.setWebContentsDebuggingEnabled(debuggable)
    }
    applyFontScale()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    showAlarmOverLockScreen(intent)
  }

  private fun showAlarmOverLockScreen(intent: Intent?) {
    if (intent?.getBooleanExtra("aerowaveAlarm", false) != true) return
    intent.removeExtra("aerowaveAlarm")
    if (!AlarmPlaybackService.isRinging()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    applyFontScale()
  }

  private fun applyFontScale() {
    appWebView?.settings?.textZoom = (resources.configuration.fontScale * 100).roundToInt()
  }
}
