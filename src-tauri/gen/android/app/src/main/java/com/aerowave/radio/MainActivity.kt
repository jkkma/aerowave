package com.aerowave.radio

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.webkit.WebView
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import kotlin.math.roundToInt
import com.aerowave.audio.AlarmInputGate
import com.aerowave.audio.AlarmPlaybackService

class MainActivity : TauriActivity() {
  private var appWebView: WebView? = null
  private val alarmKeys = AlarmInputGate()
  private val backHandler = Handler(Looper.getMainLooper())
  private var backRequestId = 0
  private var backPending = false
  private var backTimeout: Runnable? = null
  private val editorBackCallback = object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
      val content = findViewById<View>(android.R.id.content)
      if (ViewCompat.getRootWindowInsets(content)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
        WindowInsetsControllerCompat(window, content).hide(WindowInsetsCompat.Type.ime())
        return
      }

      val webView = appWebView
      if (webView == null) {
        continueDefaultBack()
        return
      }
      // WebView evaluates asynchronously. A second press must not start a second
      // default Back while the first result is still pending.
      if (backPending) return
      backPending = true
      val requestId = ++backRequestId
      val timeout = Runnable {
        if (requestId != backRequestId || !backPending) return@Runnable
        clearPendingBack()
        if (canCompleteBack()) continueDefaultBack()
      }
      backTimeout = timeout
      backHandler.postDelayed(timeout, 2000)
      try {
        webView.evaluateJavascript(
          "(function(){try{return typeof window.__aerowaveHandleAndroidBack === 'function' && window.__aerowaveHandleAndroidBack() === true;}catch(e){return false;}})()",
        ) { result ->
          if (requestId != backRequestId || !backPending) return@evaluateJavascript
          clearPendingBack()
          if (result != "true" && canCompleteBack()) continueDefaultBack()
        }
      } catch (_: RuntimeException) {
        if (requestId == backRequestId && backPending) {
          clearPendingBack()
          if (canCompleteBack()) continueDefaultBack()
        }
      }
    }
  }

  private fun clearPendingBack() {
    backTimeout?.let(backHandler::removeCallbacks)
    backTimeout = null
    backPending = false
    ++backRequestId
  }

  private fun canCompleteBack(): Boolean =
    !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

  private fun continueDefaultBack() {
    editorBackCallback.isEnabled = false
    try {
      onBackPressedDispatcher.onBackPressed()
    } finally {
      editorBackCallback.isEnabled = true
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
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
    onBackPressedDispatcher.addCallback(this, editorBackCallback)
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

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
      event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
    val occurrence = AlarmPlaybackService.liveOccurrenceId(this)
    alarmKeys.bind(occurrence)
    if (occurrence == null) return super.dispatchKeyEvent(event)
    when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        alarmKeys.volumeDown(event.keyCode, event.repeatCount, occurrence)
        return true
      }
      KeyEvent.ACTION_UP -> {
        if (alarmKeys.volumeUp(event.keyCode, occurrence) && !event.isCanceled &&
          alarmKeys.beginAction(occurrence)) {
          AlarmPlaybackService.dismissIfMatching(this, occurrence)
        }
        return true
      }
    }
    return true
  }

  override fun onPause() {
    clearPendingBack()
    alarmKeys.bind(null)
    super.onPause()
  }

  override fun onDestroy() {
    clearPendingBack()
    appWebView = null
    super.onDestroy()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    applyFontScale()
  }

  private fun applyFontScale() {
    appWebView?.settings?.textZoom = (resources.configuration.fontScale * 100).roundToInt()
  }
}
