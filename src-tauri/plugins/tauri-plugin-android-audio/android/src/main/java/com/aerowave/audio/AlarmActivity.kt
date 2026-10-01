package com.aerowave.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** The full-screen alarm destination has no WebView startup dependency. */
class AlarmActivity : Activity() {
  private val handler = Handler(Looper.getMainLooper())
  private val inputGate = AlarmInputGate()
  private lateinit var labelView: TextView
  private lateinit var sourceView: TextView
  private lateinit var noteView: TextView
  private lateinit var hintView: TextView
  private lateinit var dismissButton: Button
  private lateinit var snoozeButton: Button
  private var boundOccurrence: String? = null
  private var visible = false
  private var actionPending = false
  private var actionStartedMs = 0L
  private val checkRing = object : Runnable {
    override fun run() {
      if (!visible) return
      if (actionPending && SystemClock.elapsedRealtime() - actionStartedMs > ACTION_TIMEOUT_MS) {
        actionPending = false
        boundOccurrence?.let(inputGate::actionFailed)
      }
      refreshRing()
      if (visible && !isFinishing) handler.postDelayed(this, RING_CHECK_MS)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    debugLifecycle("onCreate", "restored=${savedInstanceState != null}")
    AlarmEventLog.record(this, "screen.create", intent?.getStringExtra(EXTRA_OCCURRENCE_ID),
      mapOf("restored" to (savedInstanceState != null)))
    boundOccurrence = null
    inputGate.bind(null)
    actionPending = false
    window.statusBarColor = BACKGROUND
    window.navigationBarColor = BACKGROUND
    window.decorView.systemUiVisibility = 0
    val content = buildContent()
    setContentView(content)
    ViewCompat.requestApplyInsets(content)
    refreshRing(allowLaunchRecovery = true)
  }

  override fun onResume() {
    super.onResume()
    debugLifecycle("onResume")
    AlarmEventLog.record(this, "screen.resume", boundOccurrence)
    visible = true
    refreshRing()
    handler.removeCallbacks(checkRing)
    if (!isFinishing) handler.postDelayed(checkRing, RING_CHECK_MS)
  }

  override fun onPause() {
    debugLifecycle("onPause")
    AlarmEventLog.record(this, "screen.pause", boundOccurrence)
    visible = false
    handler.removeCallbacks(checkRing)
    setAlarmWindowFlags(false)
    super.onPause()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    debugLifecycle("onNewIntent")
    AlarmEventLog.record(this, "screen.new_intent", intent.getStringExtra(EXTRA_OCCURRENCE_ID))
    setIntent(intent)
    boundOccurrence = null
    inputGate.bind(null)
    actionPending = false
    refreshRing()
  }

  override fun onDestroy() {
    debugLifecycle("onDestroy", "finishing=$isFinishing")
    AlarmEventLog.record(this, "screen.destroy", boundOccurrence, mapOf("finishing" to isFinishing))
    handler.removeCallbacks(checkRing)
    setAlarmWindowFlags(false)
    super.onDestroy()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
      event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
    val occurrence = boundOccurrence
    if (occurrence == null || !isCurrentRing(occurrence)) return super.dispatchKeyEvent(event)
    when (event.action) {
      KeyEvent.ACTION_DOWN -> {
        inputGate.volumeDown(event.keyCode, event.repeatCount, occurrence)
        return true
      }
      KeyEvent.ACTION_UP -> {
        if (inputGate.volumeUp(event.keyCode, occurrence) && !event.isCanceled) {
          val current = AlarmStateStore.snapshot(this).ringing
          if (current?.occurrenceId == occurrence) {
            performAction(occurrence, snooze = alarmVolumeButtonSnoozes(current.trigger), origin = "volume_button")
          }
        }
        return true
      }
    }
    return true
  }

  private fun refreshRing(allowLaunchRecovery: Boolean = false) {
    var occurrence = intent?.getStringExtra(EXTRA_OCCURRENCE_ID)
    val ring = AlarmStateStore.snapshot(this).ringing
    val liveOccurrence = AlarmPlaybackService.liveOccurrenceId(this)
    if (allowLaunchRecovery && ring != null && liveOccurrence == ring.occurrenceId &&
      occurrence != ring.occurrenceId) {
      // Android can recreate an old alarm task with its saved intent before
      // delivering the new full-screen intent. Bind to the live claimed ring.
      debugLifecycle("recoverLaunch", "staleIntent=true")
      AlarmEventLog.record(this, "screen.recovered_stale_intent", ring.occurrenceId,
        mapOf("requestedOccurrenceId" to occurrence))
      occurrence = ring.occurrenceId
      setIntent(intentFor(this, occurrence))
    }
    if (occurrence == null || ring?.occurrenceId != occurrence || liveOccurrence != occurrence) {
      debugLifecycle("guardClose", "hasIntent=${occurrence != null} storedMatch=${ring?.occurrenceId == occurrence} liveMatch=${liveOccurrence == occurrence}")
      AlarmEventLog.record(this, "screen.guard_close", occurrence,
        mapOf("storedMatch" to (ring?.occurrenceId == occurrence), "liveMatch" to (liveOccurrence == occurrence)))
      closeAlarmScreen()
      return
    }
    if (boundOccurrence != occurrence) {
      debugLifecycle("bindRing")
      boundOccurrence = occurrence
      inputGate.bind(occurrence)
    }
    setAlarmWindowFlags(true)
    labelView.text = ring.alarm.label.ifBlank { "Alarm" }
    sourceView.text = ring.title ?: "Aerowave is ringing"
    noteView.text = ring.note.orEmpty()
    noteView.visibility = if (ring.note.isNullOrBlank()) View.GONE else View.VISIBLE
    snoozeButton.visibility = if (ring.trigger == "test") View.GONE else View.VISIBLE
    snoozeButton.text = "Snooze ${ring.alarm.snoozeMins} min"
    dismissButton.isEnabled = !actionPending
    snoozeButton.isEnabled = !actionPending
    val volumeHint = if (ring.trigger == "test") {
      "Press a volume button to stop the test"
    } else {
      "Press a volume button to snooze"
    }
    hintView.text = if (AlarmPlaybackService.canShakeToDismiss(occurrence)) {
      "Shake to dismiss · $volumeHint"
    } else volumeHint
  }

  private fun isCurrentRing(occurrence: String): Boolean =
    AlarmPlaybackService.liveOccurrenceId(this) == occurrence

  private fun debugLifecycle(event: String, detail: String = "") {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
      Log.d("AerowaveAlarmActivity", "$event $detail")
    }
  }

  private fun performAction(occurrence: String, snooze: Boolean, origin: String = "native_screen") {
    val current = AlarmStateStore.snapshot(this).ringing
    if (current?.occurrenceId != occurrence || !isCurrentRing(occurrence) ||
      (snooze && current.trigger == "test") || !inputGate.beginAction(occurrence)) return
    actionPending = true
    actionStartedMs = SystemClock.elapsedRealtime()
    dismissButton.isEnabled = false
    snoozeButton.isEnabled = false
    AlarmPlaybackService.stopIfMatching(this, occurrence, snooze, origin = origin) { state ->
      runOnUiThread {
        if (isDestroyed || isFinishing || boundOccurrence != occurrence) return@runOnUiThread
        if (state.ringing?.occurrenceId == occurrence) {
          actionPending = false
          inputGate.actionFailed(occurrence)
          refreshRing()
        } else {
          closeAlarmScreen()
        }
      }
    }
  }

  private fun closeAlarmScreen() {
    boundOccurrence = null
    inputGate.bind(null)
    actionPending = false
    handler.removeCallbacks(checkRing)
    setAlarmWindowFlags(false)
    if (!isFinishing) finish()
  }

  private fun setAlarmWindowFlags(enabled: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(enabled)
      setTurnScreenOn(enabled)
    } else {
      @Suppress("DEPRECATION")
      if (enabled) window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
      ) else window.clearFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
      )
    }
    if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun buildContent(): View {
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      setPadding(dp(24), dp(32), dp(24), dp(32))
    }
    val root = ScrollView(this).apply {
      isFillViewport = true
      setBackgroundColor(BACKGROUND)
      addView(panel, ViewGroup.LayoutParams(-1, -1))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val safe = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
      )
      view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
      WindowInsetsCompat.CONSUMED
    }
    fun add(view: View, bottom: Int) {
      panel.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(bottom)
      })
    }
    add(TextView(this).apply {
      text = "AEROWAVE"
      setTextColor(SECONDARY)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      gravity = Gravity.CENTER
    }, 24)
    labelView = TextView(this).apply {
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
      gravity = Gravity.CENTER
    }
    add(labelView, 12)
    sourceView = TextView(this).apply {
      setTextColor(SECONDARY)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
      gravity = Gravity.CENTER
    }
    add(sourceView, 12)
    noteView = TextView(this).apply {
      setTextColor(Color.rgb(255, 174, 174))
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      gravity = Gravity.CENTER
    }
    add(noteView, 28)
    dismissButton = Button(this).apply {
      text = "Dismiss alarm"
      contentDescription = "Dismiss ringing alarm"
      setTextColor(Color.WHITE)
      backgroundTintList = ColorStateList.valueOf(Color.rgb(171, 42, 49))
      minHeight = dp(64)
      setOnClickListener { boundOccurrence?.let { performAction(it, snooze = false) } }
    }
    add(dismissButton, 14)
    snoozeButton = Button(this).apply {
      contentDescription = "Snooze ringing alarm"
      setTextColor(Color.WHITE)
      backgroundTintList = ColorStateList.valueOf(Color.rgb(23, 91, 134))
      minHeight = dp(64)
      setOnClickListener { boundOccurrence?.let { performAction(it, snooze = true) } }
    }
    add(snoozeButton, 24)
    hintView = TextView(this).apply {
      setTextColor(SECONDARY)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      gravity = Gravity.CENTER
    }
    add(hintView, 0)
    return root
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  companion object {
    private const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    private const val RING_CHECK_MS = 500L
    private const val ACTION_TIMEOUT_MS = 5_000L
    private val BACKGROUND = Color.rgb(3, 24, 36)
    private val SECONDARY = Color.rgb(196, 215, 225)

    fun intentFor(context: Context, occurrenceId: String): Intent =
      Intent(context, AlarmActivity::class.java)
        .setAction("com.aerowave.audio.action.SHOW_ALARM")
        .setData(Uri.parse("aerowave://alarm-ui/${Uri.encode(occurrenceId)}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
          Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
  }
}
