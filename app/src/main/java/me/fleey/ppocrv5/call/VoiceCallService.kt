package me.fleey.ppocrv5.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.fleey.ppocrv5.MainActivity
import me.fleey.ppocrv5.R
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class VoiceCallService : Service() {
  private val worker = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "local-screen-understanding")
  }
  private val longScreenshotNetworkWorker = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "long-screenshot-recognition")
  }
  @Volatile private var longScreenshotClient: ProductScreenshotClient? = null
  private var longScreenshotNetworkTask: java.util.concurrent.Future<*>? = null
  private val workerOwner = LocalCallWorkerOwner(
    submitToWorker = { task -> worker.execute(task) },
    shutdownWorker = worker::shutdown,
  )
  private val turnPolicy = LocalCallTurnPolicy()
  private val accessibilityCommandParser = AccessibilityCommandParser()
  private val accessibilitySpeechPolicy = AccessibilityCommandSpeechPolicy()
  private val automationTokenProvider = LocalCallAutomationTokenProvider()
  private val servicePolicies = LocalCallServicePolicies()
  private val turnCoordinator = LocalCallTurnCoordinator()
  private val partialTurnHandler = LocalCallPartialTurnHandler(turnPolicy, turnCoordinator)
  private val replyEngine = LocalReplyEngine()
  private val sessionLock = Any()
  private val sessionGeneration = AtomicLong()
  private val asrRunEpoch = LocalCallAsrRunEpoch()
  private val activeQueryToken = AtomicLong()
  private val queryInFlight = AtomicBoolean()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val automationTimeouts = mutableMapOf<Long, Runnable>()
  private var captureSwipeToken: Long? = null
  @Volatile private var activeTurn: LocalCallTurnIdentity? = null
  private val historyRepository by lazy { LocalCallCaptureHistoryProvider.get(applicationContext) }
  private val jdAutomationController by lazy { LocalCallJdAutomationController(applicationContext) }
  private val weChatAutomationController by lazy {
    LocalCallWeChatAutomationController(applicationContext)
  }
  private val speakerInputPreference by lazy { LocalCallSpeakerInputPreference(applicationContext) }
  private val permissionRequestHistory by lazy {
    LocalCallPermissionRequestHistory(applicationContext)
  }
  private val speechInputController by lazy {
    LocalCallSpeechInputController(
      preference = speakerInputPreference,
      stopListening = ::stopListeningForRestart,
      startListening = ::startListening,
    )
  }
  private lateinit var speechEngine: SpeechEngine
  private lateinit var speechOutput: AndroidSpeechOutput
  private lateinit var screenCapture: ScreenCaptureEngine
  private lateinit var productImageEncoder: ProductImageEncoder
  private lateinit var productRecognitionGateway: ProductRecognitionGateway
  private lateinit var weChatKeyFrameProvider: WeChatKeyFrameCaptureProvider
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    speechEngine = SherpaSpeechEngine(applicationContext)
    speechOutput = AndroidSpeechOutput(
      applicationContext,
      onFinished = ::handleSpeechFinished,
      onError = ::handleSpeechError,
    )
    screenCapture = ScreenCaptureEngine(applicationContext, ::handleScreenShareStopped)
    productImageEncoder = ProductImageEncoder()
    productRecognitionGateway = ProductRecognitionGatewayProvider.create()
    weChatKeyFrameProvider = WeChatKeyFrameCaptureProvider { token, callback ->
      workerOwner.submit {
        val result = runCatching {
          check(LocalCallAutomationCommandStore.isActive(token)) {
            "微信关键帧指令已失效"
          }
          val bitmap = captureScreenWithoutPictureInPicture()
            ?: error("暂时没有取得新的微信页面关键帧")
          try {
            val blocks = LocalCallOcrProvider.detectTextBlocks(applicationContext, bitmap)
            Log.d(
              WECHAT_FRAME_LOG_TAG,
              "keyframe ${bitmap.width}x${bitmap.height} " + blocks.joinToString(" | ") {
                "${it.text}@(${it.centerX.toInt()},${it.centerY.toInt()})"
              },
            )
            WeChatKeyFrame(bitmap.width, bitmap.height, blocks)
          } finally {
            bitmap.recycle()
          }
        }
        mainHandler.post { callback(result) }
      }
    }
    WeChatKeyFrameCaptureBridge.install(weChatKeyFrameProvider)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START_CALL -> startCall()
      ACTION_STOP_CALL, ACTION_END_CALL -> endCall()
      ACTION_START_SCREEN_SHARE -> if (!startScreenShare(intent)) stopSelfResult(startId)
      ACTION_STOP_SCREEN_SHARE -> stopScreenShare()
      ACTION_SET_SPEAKER_INPUT -> if (!updateSpeakerPlaybackInput(
          intent.getBooleanExtra(EXTRA_SPEAKER_INPUT_ENABLED, false),
        )
      ) {
        stopSelfResult(startId)
      }
      ACTION_MANUAL_QUERY -> handleQuery(intent.getStringExtra(EXTRA_QUERY).orEmpty())
      ACTION_START_WECHAT_CAPTURE -> handleQuery(WECHAT_CAPTURE_BUTTON_QUERY)
    }
    return START_NOT_STICKY
  }

  private fun startCall() {
    val shouldStartListening = synchronized(sessionLock) {
      val startingNewCall = if (!CallSessionStore.state.value.callActive) {
        sessionGeneration.incrementAndGet()
        activeQueryToken.set(0L)
        activeTurn = null
        queryInFlight.set(false)
        turnCoordinator.clear()
        CallSessionStore.dispatch(CallEvent.StartCall)
        acquireWakeLock()
        true
      } else {
        false
      }
      startForegroundForCall(screenSharing = CallSessionStore.state.value.screenSharing)
      startingNewCall
    }
    if (shouldStartListening) startListening()
  }

  private fun startListening(inputMode: LocalCallAudioInputMode = speakerInputPreference.inputMode()) {
    val callbackEpoch = asrRunEpoch.beginRun()
    speechEngine.start(
      inputMode = inputMode,
      onPartial = { partial -> handlePartial(callbackEpoch, partial) },
      onFinal = { query -> handleQuery(query, callbackEpoch) },
      onError = { error ->
        synchronized(sessionLock) {
          if (!asrRunEpoch.isCurrent(callbackEpoch, CallSessionStore.state.value.callActive)) {
            return@synchronized
          }
          val failure = LocalCallRuntimeCapabilityReconciler.microphoneRecorderError(
            microphoneGranted = ContextCompat.checkSelfPermission(
              applicationContext,
              Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
              microphoneWasRequested = permissionRequestHistory
                .wasRequested(LocalCallCapability.MICROPHONE),
              message = "离线语音识别失败：${error.message.orEmpty()}",
              microphoneWasGrantedBefore = permissionRequestHistory
                .wasGranted(LocalCallCapability.MICROPHONE),
            )
          if (failure is CallEvent.CapabilityIssueRaised &&
            failure.issue.capability == LocalCallCapability.MICROPHONE
          ) {
            // The recorder is no longer running. End the session so granting permission can start
            // a fresh ASR run instead of leaving a silent active call.
            endCallLocked()
          }
          CallSessionStore.dispatch(failure)
        }
      },
    )
  }

  private fun stopListeningForRestart() {
    asrRunEpoch.invalidateRun()
    speechEngine.stop()
  }

  private fun updateSpeakerPlaybackInput(enabled: Boolean): Boolean = synchronized(sessionLock) {
    val callActive = CallSessionStore.state.value.callActive
    speechInputController.updateSpeakerPlaybackInput(enabled, callActive)
    LocalCallSpeakerInputActionPolicy.action(callActive) ==
      SpeakerInputAction.RESTART_ACTIVE_RECORDER
  }

  private fun startScreenShare(intent: Intent): Boolean {
    val data = if (Build.VERSION.SDK_INT >= 33) {
      intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
    }
    val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)

    return synchronized(sessionLock) {
      val state = CallSessionStore.state.value
      if (LocalCallProjectionLifecyclePolicy.shouldStopIdleService(state.callActive)) {
        return@synchronized false
      }
      if (data == null) return@synchronized true
      runCatching {
        startForegroundForCall(screenSharing = true)
        screenCapture.start(resultCode, data, state.callEpoch)
        CallSessionStore.dispatch(CallEvent.ScreenShareGranted)
      }.onFailure {
        CallSessionStore.dispatch(CallEvent.Failed("屏幕共享启动失败：${it.message.orEmpty()}"))
        startForegroundForCall(screenSharing = false)
      }
      true
    }
  }

  private fun handleQuery(rawQuery: String, callbackEpoch: Long? = null) {
    val query = rawQuery.trim()
    if (query.isBlank()) return
    val accessibilityCommand = accessibilityCommandParser.parse(query)
    val intent = accessibilityCommand?.let(accessibilitySpeechPolicy::intentFor) ?: turnPolicy.match(query)
    val accepted = synchronized(sessionLock) {
      val state = CallSessionStore.state.value
      when {
        callbackEpoch != null && !asrRunEpoch.isCurrent(callbackEpoch, state.callActive) -> null
        !state.callActive || turnCoordinator.currentSpokenText().isNotBlank() -> null
        intent == null -> {
          CallSessionStore.dispatch(CallEvent.TranscriptChanged(query))
          null
        }
        !queryInFlight.compareAndSet(false, true) -> null
        else -> {
          val generation = sessionGeneration.get()
          val queryToken = automationTokenProvider.nextToken()
          val requiresAutomationLease = intent.action == LocalCallAction.JD_CHECK_IN ||
            intent.action == LocalCallAction.WECHAT_PRODUCT_CAPTURE ||
            intent.action == LocalCallAction.ACCESSIBILITY_COMMAND
          if (requiresAutomationLease && !LocalCallAutomationCommandStore.reserve(queryToken)) {
            queryInFlight.set(false)
            null
          } else {
            activeQueryToken.set(queryToken)
            val acknowledgement = if (intent.action == LocalCallAction.WECHAT_PRODUCT_CAPTURE ||
              intent.action == LocalCallAction.WECHAT_LONG_SCREENSHOT) {
              turnCoordinator.beginSilent(generation, queryToken, query)
              CallSessionStore.dispatch(CallEvent.Processing(if (intent.action == LocalCallAction.WECHAT_LONG_SCREENSHOT)
                "请在 10 秒内切回微信目标页面…" else "正在进入微信商品详情…"))
              null
            } else turnCoordinator.begin(generation, queryToken, query, intent)
            activeTurn = LocalCallTurnIdentity(generation, queryToken, intent.action)
            CallSessionStore.dispatch(CallEvent.TranscriptChanged(query))
            AcceptedTurn(generation, queryToken, query, acknowledgement, intent, accessibilityCommand,
              WeChatCapturePreference(applicationContext).count())
          }
        }
      }
    } ?: return
    accepted.acknowledgement?.let { speakIfActive(accepted.generation, it) }
    workerOwner.submit { executeTurn(accepted) }
  }

  private fun handlePartial(callbackEpoch: Long, rawPartial: String) {
    if (activeTurn?.action == LocalCallAction.WECHAT_PRODUCT_CAPTURE) return
    val partial = rawPartial.trim()
    if (partial.isBlank()) return
    val shouldStopSpeech = synchronized(sessionLock) {
      if (!asrRunEpoch.isCurrent(callbackEpoch, CallSessionStore.state.value.callActive)) {
        return@synchronized false
      }
      when (val decision = partialTurnHandler.evaluate(partial)) {
        is PartialTurnDecision.Interrupted -> {
          cancelLongScreenshotRecognition()
          cancelAutomation(decision.queryToken)
          sessionGeneration.incrementAndGet()
          activeQueryToken.compareAndSet(decision.queryToken, 0L)
          activeTurn = null
          queryInFlight.set(false)
          CallSessionStore.dispatch(CallEvent.ReplyInterrupted(partial))
          decision.stopSpeech
        }
        PartialTurnDecision.TranscriptOnly -> {
          CallSessionStore.dispatch(CallEvent.TranscriptChanged(partial))
          false
        }
        PartialTurnDecision.Ignored -> false
      }
    }
    if (shouldStopSpeech) speechOutput.stop()
  }

  private fun stopScreenShare() {
    val callEpoch = CallSessionStore.state.value.callEpoch
    screenCapture.stop()
    handleScreenShareStopped(callEpoch = callEpoch, revoked = false)
  }

  private fun handleScreenShareStopped(callEpoch: Long) =
    handleScreenShareStopped(callEpoch = callEpoch, revoked = true)

  private fun handleScreenShareStopped(callEpoch: Long, revoked: Boolean) {
    val shouldStopSpeech = synchronized(sessionLock) {
      val state = CallSessionStore.state.value
      if (!LocalCallProjectionLifecyclePolicy.shouldSurfaceRevocation(
          projectionCallEpoch = callEpoch,
          currentCallEpoch = state.callEpoch,
          callActive = state.callActive,
        )
      ) {
        return@synchronized false
      }
      CallSessionStore.dispatch(CallEvent.ScreenShareStopped)
      cancelLongScreenshotRecognition()
      if (revoked) {
        CallSessionStore.dispatch(
          CallEvent.CapabilityIssueRaised(LocalCallCapabilityIssue.ProjectionRevoked),
        )
      }
      val activeScreenTurn = activeTurn.takeIf(servicePolicies::shouldInvalidateForProjectionStop)
      val interruptedToken = if (activeScreenTurn != null) turnCoordinator.interrupt() else null
      if (interruptedToken != null) {
        cancelAutomation(interruptedToken)
        sessionGeneration.incrementAndGet()
        activeQueryToken.compareAndSet(interruptedToken, 0L)
        activeTurn = null
        queryInFlight.set(false)
        CallSessionStore.dispatch(CallEvent.ReplyFinished)
      }
      if (CallSessionStore.state.value.callActive) startForegroundForCall(screenSharing = false)
      interruptedToken != null
    }
    if (shouldStopSpeech) speechOutput.stop()
  }

  private fun executeTurn(turn: AcceptedTurn) {
    if (!isTurnActive(turn.generation, turn.queryToken)) return
    when (turn.intent.action) {
      LocalCallAction.READ_SCREEN -> executeReadScreen(turn)
      LocalCallAction.RECOGNIZE_PRODUCT -> executeProductRecognition(turn)
      LocalCallAction.JD_CHECK_IN -> executeJdAutomation(turn)
      LocalCallAction.WECHAT_PRODUCT_CAPTURE -> executeWeChatAutomation(turn)
      LocalCallAction.WECHAT_LONG_SCREENSHOT -> executeWeChatLongScreenshot(turn)
      LocalCallAction.ACCESSIBILITY_COMMAND -> executeAccessibilityCommand(turn)
    }
  }

  private fun executeAccessibilityCommand(turn: AcceptedTurn) {
    val command = turn.accessibilityCommand ?: run {
      completeTurn(turn, "没有识别到可执行的辅助功能指令。")
      return
    }
    var subscribed = false
    val leaseActive = LocalCallAutomationCommandStore.executeIfActive(turn.queryToken) {
      subscribed = LocalCallAutomationResultBus.subscribe(turn.queryToken) { result ->
        removeAutomationTimeout(turn.queryToken)
        completeTurn(turn, result.message)
      }
    }
    if (!leaseActive || !subscribed) {
      completeTurn(turn, "这条辅助功能指令已经处理过，请重新说一次。")
      return
    }
    val timeout = Runnable {
      LocalCallAutomationCommandStore.cancel(turn.queryToken)
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(turn.queryToken, false, "辅助功能操作等待超时，请重新试一次。"),
      )
    }
    synchronized(sessionLock) { automationTimeouts[turn.queryToken] = timeout }
    mainHandler.postDelayed(timeout, AUTOMATION_TIMEOUT_MILLIS)
    if (!LocalCallAutomationCommandStore.isActive(turn.queryToken)) {
      removeAutomationTimeout(turn.queryToken)
      return
    }
    val enqueued = LocalCallAutomationCommandStore.enqueue(
      AutomationCommandEnvelope(
        token = turn.queryToken,
        issuedAtMillis = SystemClock.elapsedRealtime(),
        command = command,
      ),
    )
    if (!enqueued) {
      LocalCallAutomationCommandStore.cancel(turn.queryToken)
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(turn.queryToken, false, "当前已有辅助功能操作，请稍后再试。"),
      )
      return
    }
    if (!LocalCallAccessibilityPermission.isEnabled(applicationContext)) {
      LocalCallAutomationCommandStore.cancel(turn.queryToken)
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(
          turn.queryToken,
          false,
          "辅助功能尚未开启，请先在辅助功能设置中启用本地 AI 通话操作。",
        ),
      )
      return
    }
  }

  private fun executeJdAutomation(turn: AcceptedTurn) {
    var subscribed = false
    val leaseActive = LocalCallAutomationCommandStore.executeIfActive(turn.queryToken) {
      subscribed = LocalCallAutomationResultBus.subscribe(turn.queryToken) { result ->
        removeAutomationTimeout(turn.queryToken)
        completeTurn(turn, result.message)
      }
    }
    if (!leaseActive || !subscribed) {
      completeTurn(turn, "这条京东打卡指令已经处理过，请重新说一次。")
      return
    }
    val timeout = Runnable {
      LocalCallAutomationCommandStore.cancel(turn.queryToken)
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(
          token = turn.queryToken,
          succeeded = false,
          message = JdAutomationResultFormatter.format(JdAutomationResult.TIMED_OUT),
        ),
      )
    }
    synchronized(sessionLock) { automationTimeouts[turn.queryToken] = timeout }
    if (!LocalCallAutomationCommandStore.isActive(turn.queryToken)) {
      removeAutomationTimeout(turn.queryToken)
      return
    }

    when (val outcome = jdAutomationController.start(turn.queryToken)) {
      is JdAutomationStartOutcome.Started -> synchronized(sessionLock) {
        if (automationTimeouts[turn.queryToken] === timeout) {
          val delay = JdAutomationTimingPolicy.DEFAULT.remainingUntil(
            outerDeadlineMillis = outcome.outerDeadlineMillis,
            nowMillis = SystemClock.elapsedRealtime(),
          )
          mainHandler.postDelayed(timeout, delay)
        }
      }
      is JdAutomationStartOutcome.Rejected -> {
        LocalCallAutomationCommandStore.cancel(turn.queryToken)
        LocalCallAutomationResultBus.publish(
          AccessibilityCommandResult(
            token = turn.queryToken,
            succeeded = false,
            message = JdAutomationResultFormatter.format(outcome.result),
          ),
        )
      }
    }
  }

  private fun executeWeChatAutomation(turn: AcceptedTurn) {
    var subscribed = false
    val leaseActive = LocalCallAutomationCommandStore.executeIfActive(turn.queryToken) {
      subscribed = LocalCallAutomationResultBus.subscribe(turn.queryToken) { result ->
        removeAutomationTimeout(turn.queryToken)
        if (result.succeeded) {
          workerOwner.submit { captureWeChatProductFrames(turn) }
        } else {
          completeTurn(turn, result.message)
        }
      }
    }
    if (!leaseActive || !subscribed) {
      completeTurn(turn, "这条微信关键帧指令已经处理过，请重新点击。")
      return
    }
    val timeout = Runnable {
      LocalCallAutomationCommandStore.cancel(turn.queryToken)
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(
          token = turn.queryToken,
          succeeded = false,
          message = WeChatAutomationResultFormatter.format(WeChatAutomationResult.TIMED_OUT),
        ),
      )
    }
    synchronized(sessionLock) { automationTimeouts[turn.queryToken] = timeout }
    if (!LocalCallAutomationCommandStore.isActive(turn.queryToken)) {
      removeAutomationTimeout(turn.queryToken)
      return
    }

    when (val outcome = weChatAutomationController.start(turn.queryToken)) {
      is WeChatAutomationStartOutcome.Started -> synchronized(sessionLock) {
        if (automationTimeouts[turn.queryToken] === timeout) {
          mainHandler.postDelayed(
            timeout,
            WeChatAutomationTimingPolicy.DEFAULT.remainingUntil(
              outcome.outerDeadlineMillis,
              SystemClock.elapsedRealtime(),
            ),
          )
        }
      }
      is WeChatAutomationStartOutcome.Rejected -> {
        LocalCallAutomationCommandStore.cancel(turn.queryToken)
        LocalCallAutomationResultBus.publish(
          AccessibilityCommandResult(
            token = turn.queryToken,
            succeeded = false,
            message = WeChatAutomationResultFormatter.format(outcome.result),
          ),
        )
      }
    }
  }

  private fun captureWeChatProductFrames(turn: AcceptedTurn) {
    var failure = ""
    var saved = 0
    val result = WeChatCaptureBatch(turn.captureCount).run(
      isActive = { canContinueWeChatCapture(turn) },
      save = {
        updateCaptureProgress(turn, "正在识别第 ${saved + 1}/${turn.captureCount} 帧…")
        runCatching { saveWeChatProductFrame(turn) }.onFailure {
          failure = it.message.orEmpty()
        }.getOrDefault(false).also { success ->
          if (success) {
            saved++
            Log.d(WECHAT_FRAME_LOG_TAG, "wechat_capture_saved $saved/${turn.captureCount}")
            updateCaptureProgress(turn, "已保存 $saved/${turn.captureCount} 帧，正在上滑…")
          }
        }
      },
      swipe = { swipeWeChatProductPage(turn) },
      settle = { waitForWeChatPage(turn, 1000L) },
    )
    val message = when (result.stop) {
      WeChatCaptureBatch.Stop.COMPLETED -> "采集完成：已保存 ${result.saved} 帧并上滑 ${result.swiped} 次，可在识别记录中查看。"
      WeChatCaptureBatch.Stop.SAVE_FAILED -> "采集已停止：已保存 ${result.saved} 帧，本帧保存失败。$failure"
      WeChatCaptureBatch.Stop.SWIPE_FAILED -> "采集已停止：已保存 ${result.saved} 帧，上滑未成功。"
      WeChatCaptureBatch.Stop.CANCELLED -> "采集已停止：已保存 ${result.saved} 帧。请保持微信商品详情在前台。"
    }
    Log.d(WECHAT_FRAME_LOG_TAG, "wechat_capture_finished saved=${result.saved} swiped=${result.swiped} stop=${result.stop}")
    completeTurn(turn, message)
  }

  private fun canContinueWeChatCapture(turn: AcceptedTurn): Boolean =
    isTurnActive(turn.generation, turn.queryToken) && CallSessionStore.state.value.screenSharing &&
      FocusedScreenContentStore.snapshot()?.packageName == LocalCallWeChatAutomationController.WECHAT_PACKAGE

  private fun updateCaptureProgress(turn: AcceptedTurn, message: String) = synchronized(sessionLock) {
    if (isTurnActive(turn.generation, turn.queryToken)) CallSessionStore.dispatch(CallEvent.Processing(message))
  }

  private fun waitForWeChatPage(turn: AcceptedTurn, millis: Long): Boolean {
    val deadline = SystemClock.elapsedRealtime() + millis
    while (SystemClock.elapsedRealtime() < deadline) {
      if (!canContinueWeChatCapture(turn)) return false
      Thread.sleep(50L)
    }
    return canContinueWeChatCapture(turn)
  }

  private fun swipeWeChatProductPage(turn: AcceptedTurn, forLongScreenshot: Boolean = false): Boolean {
    val token = automationTokenProvider.nextToken()
    val completion = CompletableFuture<Boolean>()
    synchronized(sessionLock) {
      if (!canContinueWeChatCapture(turn) || !LocalCallAutomationCommandStore.reserve(token)) return false
      captureSwipeToken = token
      LocalCallAutomationResultBus.subscribe(token) { completion.complete(it.succeeded) }
      if (!LocalCallAutomationCommandStore.enqueue(AutomationCommandEnvelope(
          token, SystemClock.elapsedRealtime(), AccessibilityCommand.Swipe(
            SwipeDirection.UP, if (forLongScreenshot) 500L else 150L,
            LocalCallWeChatAutomationController.WECHAT_PACKAGE,
            verticalTravelFraction = if (forLongScreenshot) .40f else .56f,
          ),
        ))) completion.complete(false)
    }
    return try {
      val deadline = SystemClock.elapsedRealtime() + 3000L
      while (!completion.isDone && SystemClock.elapsedRealtime() < deadline) {
        if (!canContinueWeChatCapture(turn)) return false
        Thread.sleep(50L)
      }
      completion.getNow(false).also {
        Log.d(WECHAT_FRAME_LOG_TAG, "wechat_capture_swipe success=$it longScreenshot=$forLongScreenshot")
      }
    } finally {
      synchronized(sessionLock) {
        LocalCallAutomationCommandStore.cancel(token)
        LocalCallAutomationResultBus.unsubscribe(token)
        if (captureSwipeToken == token) captureSwipeToken = null
      }
    }
  }

  private fun saveWeChatProductFrame(turn: AcceptedTurn): Boolean {
    if (!canContinueWeChatCapture(turn)) return false
    // Navigation/gesture completion and the page-settle delay precede this call. Keep the
    // latest queued frame: a static detail page may never produce another frame after a drain.
    val bitmap = LocalCallCaptureVisibilityCoordinator.shared.captureWithoutPictureInPicture(
      onDiscardedCapture = { frame -> frame?.recycle() },
    ) {
      ScreenCaptureRetryPolicy().capture { screenCapture.captureNext() }
    } ?: error("暂时没有取得当前屏幕画面")
    try {
      if (!canContinueWeChatCapture(turn)) return false
      val snapshot = LocalCallOcrProvider.analyze(applicationContext, bitmap)
      val ocrText = snapshot.summary.trim().ifBlank { "当前关键帧未识别到可读文字" }
      if (!canContinueWeChatCapture(turn)) return false
      return commitScreenUnderstanding(turn, snapshot, bitmap, ocrText)
    } finally {
      bitmap.recycle()
    }
  }

  private fun executeWeChatLongScreenshot(turn: AcceptedTurn) {
    val message = runCatching {
      check(CallSessionStore.state.value.screenSharing) { "请先共享整个屏幕，再打开微信目标页面" }
      check(LocalCallAccessibilityPermission.isEnabled(applicationContext)) { "请先开启辅助功能" }
      val deadline = SystemClock.elapsedRealtime() + 10_000L
      while (!canContinueWeChatCapture(turn) && SystemClock.elapsedRealtime() < deadline) {
        check(isTurnActive(turn.generation, turn.queryToken)) { "长截图已取消" }
        Thread.sleep(100L)
      }
      check(canContinueWeChatCapture(turn)) { "请打开微信小程序或公众号页面后重试" }
      check(waitForWeChatPage(turn, 1000L)) { "长截图已取消" }
      val result = LocalCallCaptureVisibilityCoordinator.shared.captureWithoutPictureInPicture(
        onDiscardedCapture = { value: LongScreenshotResult -> value.bitmap.recycle() },
      ) {
        WeChatLongScreenshotCapture(cacheDir).capture(
          isActive = { canContinueWeChatCapture(turn) },
          captureFrame = { screenCapture.captureNext() },
          scroll = {
            screenCapture.discardPendingFrame()
            swipeWeChatProductPage(turn, forLongScreenshot = true)
          },
          settle = { waitForWeChatPage(turn, 900L) },
          onProgress = { count -> updateCaptureProgress(turn, "长截图：已采集 $count 帧，继续向下截取…") },
          onReachedBottom = { showLongScreenshotToast(turn, "已滑动到底，正在拼接长截图") },
          onDiagnostic = { message -> Log.d(WECHAT_FRAME_LOG_TAG, "long_screenshot $message") },
        )
      }
      try {
        check(isTurnActive(turn.generation, turn.queryToken) && CallSessionStore.state.value.screenSharing) { "长截图已取消" }
        showLongScreenshotToast(turn,
          if (result.reachedBottom) "长截图拼接完成" else result.stop)
        recognizeLongScreenshot(turn, result.bitmap)
        val file = LongScreenshotStorage(applicationContext).saveBitmap(result.bitmap)
        if (!isTurnActive(turn.generation, turn.queryToken)) { file.delete(); error("长截图已取消") }
        val gallery = runCatching {
          RecordGalleryExporter(applicationContext).save(file, "image/png", "微信长截图")
        }
        if (!isTurnActive(turn.generation, turn.queryToken)) {
          gallery.getOrNull()?.let { contentResolver.delete(it, null, null) }
          file.delete()
          error("长截图已取消")
        }
        val saved = if (gallery.isSuccess) "已保存到相册「微信长截图」"
          else "原图已保存，相册保存失败：${gallery.exceptionOrNull()?.message}。本地路径：${file.absolutePath}"
        val scaled = if (result.scaled) "内容较长，已等比例缩小保存完整长图。" else ""
        "${result.stop}；${result.frames} 帧，${result.bitmap.width}×${result.bitmap.height}。$scaled$saved"
      } finally { result.bitmap.recycle() }
    }.getOrElse { "长截图未完成：${it.message.orEmpty()}" }
    completeTurn(turn, message)
  }

  private fun showLongScreenshotToast(turn: AcceptedTurn, message: String) {
    mainHandler.post {
      synchronized(sessionLock) {
        val state = CallSessionStore.state.value
        val token = activeQueryToken.get()
        // A successful turn may finish before this main-thread callback. Cancellation/end-call
        // increments the generation; a newer operation has a different nonzero token.
        if (sessionGeneration.get() == turn.generation && state.callActive && state.screenSharing &&
          (token == turn.queryToken || token == 0L)) {
          Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  /** Encode before the capture's finally recycles its Bitmap; HTTP runs on a separate worker. */
  private fun recognizeLongScreenshot(turn: AcceptedTurn, bitmap: android.graphics.Bitmap) {
    runCatching {
      val upload = LongScreenshotUploadEncoder.prepare(bitmap)
      val image = upload.bytes
      synchronized(sessionLock) {
        if (!isTurnActive(turn.generation, turn.queryToken)) return
        cancelLongScreenshotRecognition()
        val client = ProductScreenshotClient()
        longScreenshotClient = client
        Log.d(WECHAT_FRAME_LOG_TAG,
          "long_screenshot_recognition requestId=${client.requestId} start sourceWidth=${bitmap.width} sourceHeight=${bitmap.height} " +
            "width=${upload.width} height=${upload.height} quality=${upload.quality} imageBytes=${image.size} " +
            "requestBytes=${ProductScreenshotPayload.requestBytes(image.size)} requestBudget=${ProductScreenshotPayload.MAX_REQUEST_BYTES}")
        longScreenshotNetworkTask = longScreenshotNetworkWorker.submit {
          try {
            val response = client.analyze(image)
            if (longScreenshotClient === client && sessionGeneration.get() == turn.generation) {
              Log.d(WECHAT_FRAME_LOG_TAG,
                "long_screenshot_recognition requestId=${client.requestId} HTTP=200 elapsedMs=${response.elapsedMillis}")
              logLongScreenshotRecognitionBody(client, response.body)
            }
          } catch (cancelled: java.util.concurrent.CancellationException) {
            Log.d(WECHAT_FRAME_LOG_TAG, "long_screenshot_recognition requestId=${client.requestId} cancelled")
          } catch (error: Exception) {
            if (longScreenshotClient === client && sessionGeneration.get() == turn.generation) {
              Log.e(WECHAT_FRAME_LOG_TAG, "long_screenshot_recognition requestId=${client.requestId} failed: ${error.message}")
              if (error is ProductScreenshotApiException) logLongScreenshotRecognitionBody(client, error.body)
            }
          } finally {
            synchronized(sessionLock) {
              if (longScreenshotClient === client) {
                longScreenshotClient = null
                longScreenshotNetworkTask = null
              }
            }
          }
        }
      }
    }.onFailure {
      Log.e(WECHAT_FRAME_LOG_TAG, "long_screenshot_recognition prepare failed: ${it.message}")
    }
  }

  private fun logLongScreenshotRecognitionBody(client: ProductScreenshotClient, body: String) {
    var offset = 0
    var part = 1
    while (offset < body.length && longScreenshotClient === client) {
      var end = minOf(offset + 900, body.length)
      if (end < body.length && Character.isHighSurrogate(body[end - 1])) end--
      Log.d(WECHAT_FRAME_LOG_TAG,
        "long_screenshot_recognition requestId=${client.requestId} result[$part] ${body.substring(offset, end)}")
      offset = end
      part++
    }
  }

  private fun cancelLongScreenshotRecognition() = synchronized(sessionLock) {
    val client = longScreenshotClient
    longScreenshotClient = null
    client?.cancel()
    longScreenshotNetworkTask?.cancel(true)
    longScreenshotNetworkTask = null
  }

  private fun executeReadScreen(turn: AcceptedTurn) {
    if (!CallSessionStore.state.value.screenSharing) {
      completeTurn(turn, "请先点击“共享屏幕”，授权后我才能在本机读取当前画面。")
      return
    }
    val result = runCatching {
      val bitmap = captureScreenWithoutPictureInPicture()
        ?: error("暂时没有取得新的屏幕画面")
      try {
        if (!isTurnActive(turn.generation, turn.queryToken)) return
        val snapshot = LocalCallOcrProvider.analyze(applicationContext, bitmap)
        val ocrText = snapshot.summary.trim()
        if (ocrText.isNotBlank()) commitScreenUnderstanding(turn, snapshot, bitmap, ocrText)
        replyEngine.reply(turn.query, snapshot)
      } finally {
        bitmap.recycle()
      }
    }.getOrElse { error -> "本地屏幕识别失败：${error.message.orEmpty()}" }
    completeTurn(turn, result)
  }

  private fun executeProductRecognition(turn: AcceptedTurn) {
    if (!CallSessionStore.state.value.screenSharing) {
      completeTurn(turn, "请先点击“共享屏幕”，授权后我才能识别当前商品。")
      return
    }
    if (!canContinueProductRecognition(turn)) return

    val captureResult = try {
      captureProductScreenWithoutPictureInPicture(turn)
    } catch (_: Throwable) {
      completeProductFailureIfActive(turn)
      return
    }
    val bitmap = when (captureResult) {
      is ScreenCaptureRetryResult.Captured -> captureResult.value
      ScreenCaptureRetryResult.Cancelled -> return
      ScreenCaptureRetryResult.Exhausted -> {
        completeProductFailureIfActive(turn)
        return
      }
    }

    val reply = try {
      if (!canContinueProductRecognition(turn)) return
      val frame = productImageEncoder.encode(bitmap)
      if (!canContinueProductRecognition(turn)) return
      val request = ProductRecognitionRequestFactory.create(
        frame = frame,
        requestTimeMillis = System.currentTimeMillis(),
      )
      if (!canContinueProductRecognition(turn)) return
      val result = productRecognitionGateway.recognize(request)
      if (!canContinueProductRecognition(turn)) return
      ProductRecognitionReplyFormatter.format(result)
    } catch (_: Throwable) {
      if (!canContinueProductRecognition(turn)) return
      ProductRecognitionReplyFormatter.format(ProductRecognitionResult.Failure)
    } finally {
      bitmap.recycle()
    }
    if (canContinueProductRecognition(turn)) completeTurn(turn, reply)
  }

  private fun completeProductFailureIfActive(turn: AcceptedTurn) {
    if (canContinueProductRecognition(turn)) {
      completeTurn(turn, ProductRecognitionReplyFormatter.format(ProductRecognitionResult.Failure))
    }
  }

  private fun canContinueProductRecognition(turn: AcceptedTurn): Boolean =
    synchronized(sessionLock) {
      val candidate = LocalCallTurnIdentity(turn.generation, turn.queryToken, turn.intent.action)
      servicePolicies.canContinueProductRecognition(
        candidate = candidate,
        activeTurn = activeTurn,
        callActive = CallSessionStore.state.value.callActive,
        screenSharing = CallSessionStore.state.value.screenSharing,
      ) && isTurnActive(turn.generation, turn.queryToken)
    }

  private fun commitScreenUnderstanding(
    turn: AcceptedTurn,
    snapshot: ScreenSnapshot,
    bitmap: android.graphics.Bitmap,
    ocrText: String,
  ): Boolean {
    val candidate = LocalCallTurnIdentity(turn.generation, turn.queryToken, turn.intent.action)
    var saved = false
    LocalCallScreenCommitCoordinator<
      StagedCaptureHistoryEntry?,
      PreparedCaptureHistoryEntry?,
      CaptureHistoryPublication,
    >(
      canStart = { synchronized(sessionLock) { canCommitScreen(candidate) } },
      stage = {
        runCatching {
          historyRepository.stage(
            bitmap = bitmap,
            ocrText = ocrText,
            sourcePackage = snapshot.sourcePackage.orEmpty(),
            capturedAtMillis = snapshot.capturedAtMillis,
          )
        }.getOrNull()
      },
      canPrepare = { synchronized(sessionLock) { canCommitScreen(candidate) } },
      prepareCommit = { staged -> staged?.let(historyRepository::prepareCommit) },
      commitIfValid = { prepared ->
        prepared?.let {
          synchronized(sessionLock) {
            if (!canCommitScreen(candidate)) {
              null
            } else {
              historyRepository.publish(it).also { publication ->
                CallSessionStore.dispatch(CallEvent.ScreenUnderstood(ocrText, publication.entry.id))
              }
            }
          }
        }
      },
      discardStaged = { staged -> staged?.let { runCatching { historyRepository.discard(it) } } },
      rollbackPrepared = { prepared -> prepared?.let { runCatching { historyRepository.rollback(it) } } },
      afterCommit = { publication ->
        historyRepository.finishPublication(publication)
        saved = true
      },
    ).commit()
    return saved
  }

  private fun canCommitScreen(candidate: LocalCallTurnIdentity): Boolean =
    servicePolicies.canCommitScreen(
      candidate = candidate,
      activeTurn = activeTurn,
      callActive = CallSessionStore.state.value.callActive,
      screenSharing = CallSessionStore.state.value.screenSharing,
    )

  private fun captureScreenWithoutPictureInPicture() =
    LocalCallCaptureVisibilityCoordinator.shared.captureWithoutPictureInPicture(
      prepareCapture = screenCapture::discardPendingFrame,
      onDiscardedCapture = { bitmap -> bitmap?.recycle() },
    ) {
      ScreenCaptureRetryPolicy().capture { screenCapture.captureNext() }
    }

  private fun captureProductScreenWithoutPictureInPicture(turn: AcceptedTurn) =
    LocalCallCaptureVisibilityCoordinator.shared.captureWithoutPictureInPicture(
      prepareCapture = {
        if (canContinueProductRecognition(turn)) screenCapture.discardPendingFrame()
      },
      onDiscardedCapture = { result ->
        if (result is ScreenCaptureRetryResult.Captured) result.value.recycle()
      },
    ) {
      ScreenCaptureRetryPolicy().captureWhileActive(
        isActive = { canContinueProductRecognition(turn) },
        block = { screenCapture.captureNext() },
      )
    }

  private fun completeTurn(turn: AcceptedTurn, reply: String) {
    if (turn.intent.action == LocalCallAction.WECHAT_PRODUCT_CAPTURE ||
      turn.intent.action == LocalCallAction.WECHAT_LONG_SCREENSHOT) {
      synchronized(sessionLock) {
        if (!isTurnActive(turn.generation, turn.queryToken)) return
        if (turnCoordinator.completeSilently(turn.generation, turn.queryToken)) {
          cancelAutomation(turn.queryToken)
          activeQueryToken.compareAndSet(turn.queryToken, 0L)
          activeTurn = null
          queryInFlight.set(false)
          CallSessionStore.dispatch(CallEvent.SilentReply(reply))
        }
      }
      return
    }
    if (!isTurnActive(turn.generation, turn.queryToken)) return
    val nextSpeech = synchronized(sessionLock) {
      if (isTurnActive(turn.generation, turn.queryToken)) {
        turnCoordinator.completeOperation(turn.generation, turn.queryToken, reply)
      } else {
        null
      }
    }
    if (nextSpeech != null) speakIfActive(turn.generation, nextSpeech)
  }

  private fun handleSpeechFinished(utterance: SpeechUtterance) {
    synchronized(sessionLock) {
      if (!isTurnActive(sessionGeneration.get(), utterance.queryToken)) return
      val completion = turnCoordinator.completeSpeech(utterance)
      when {
        completion.ignored -> Unit
        completion.nextSpeech != null -> speakIfActive(sessionGeneration.get(), completion.nextSpeech)
        completion.processingQuery != null -> CallSessionStore.dispatch(
          CallEvent.Processing(completion.processingQuery),
        )
        completion.finished -> {
          cancelAutomation(utterance.queryToken)
          activeQueryToken.compareAndSet(utterance.queryToken, 0L)
          activeTurn = null
          queryInFlight.set(false)
          CallSessionStore.dispatch(CallEvent.ReplyFinished)
        }
      }
    }
  }

  private fun handleSpeechError(utterance: SpeechUtterance, message: String) {
    synchronized(sessionLock) {
      val callbackTurn = activeTurn?.takeIf { it.queryToken == utterance.queryToken } ?: return
      if (servicePolicies.canPublishTtsError(
          callbackTurn = callbackTurn,
          activeTurn = activeTurn,
          callActive = CallSessionStore.state.value.callActive,
        )
      ) {
        CallSessionStore.dispatch(CallEvent.Failed(message))
      }
    }
  }

  private fun speakIfActive(generation: Long, request: SpeechRequest) {
    synchronized(sessionLock) {
      if (!isTurnActive(generation, request.utterance.queryToken)) return
      CallSessionStore.dispatch(CallEvent.ReplyStarted(request.text))
      speechOutput.speak(request.text, request.utterance)
    }
  }

  private fun isTurnActive(generation: Long, queryToken: Long): Boolean =
    sessionGeneration.get() == generation &&
      activeQueryToken.get() == queryToken &&
      activeTurn?.let { it.sessionGeneration == generation && it.queryToken == queryToken } == true &&
      turnCoordinator.isActive(generation, queryToken) &&
      CallSessionStore.state.value.callActive

  private fun endCall() = synchronized(sessionLock) { endCallLocked() }

  /** Must be called with [sessionLock] held. */
  private fun endCallLocked() {
    cancelLongScreenshotRecognition()
    cancelAutomation(activeQueryToken.get())
    sessionGeneration.incrementAndGet()
    asrRunEpoch.invalidateRun()
    activeQueryToken.set(0L)
    activeTurn = null
    queryInFlight.set(false)
    turnCoordinator.clear()
    speechEngine.stop()
    speechOutput.stop()
    screenCapture.stop()
    CallSessionStore.dispatch(CallEvent.EndCall)
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun acquireWakeLock() {
    val manager = getSystemService(PowerManager::class.java)
    wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:local-ai-call")
      .apply { setReferenceCounted(false); acquire(MAX_WAKE_LOCK_MILLIS) }
  }

  private fun startForegroundForCall(screenSharing: Boolean) {
    val types = when {
      Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
        (if (screenSharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
      Build.VERSION.SDK_INT == 29 && screenSharing -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
      else -> 0
    }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(screenSharing), types)
  }

  private fun buildNotification(screenSharing: Boolean): Notification {
    val openIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val stopIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, VoiceCallService::class.java).setAction(ACTION_END_CALL),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_phone_call)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(if (screenSharing) "正在通话并共享屏幕（本地处理）" else "正在后台聆听（本地处理）")
      .setContentIntent(openIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .addAction(0, "结束", stopIntent)
      .build()
  }

  private fun createNotificationChannel() {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "本地 AI 通话", NotificationManager.IMPORTANCE_LOW).apply {
        description = "保持离线语音通话和屏幕共享服务运行"
        setSound(null, null)
      },
    )
  }

  override fun onDestroy() {
    cancelLongScreenshotRecognition()
    longScreenshotNetworkWorker.shutdownNow()
    if (::weChatKeyFrameProvider.isInitialized) {
      WeChatKeyFrameCaptureBridge.uninstall(weChatKeyFrameProvider)
    }
    synchronized(sessionLock) {
      cancelAutomation(activeQueryToken.get())
      sessionGeneration.incrementAndGet()
      asrRunEpoch.invalidateRun()
      activeQueryToken.set(0L)
      activeTurn = null
      queryInFlight.set(false)
      turnCoordinator.clear()
    }
    if (::speechEngine.isInitialized) speechEngine.close()
    if (::speechOutput.isInitialized) speechOutput.close()
    if (::screenCapture.isInitialized) screenCapture.close()
    // OCR belongs to the process and remains warm across call/service restarts.
    workerOwner.shutdownWithoutWaiting {}
    wakeLock?.let { if (it.isHeld) it.release() }
    if (CallSessionStore.state.value.callActive) CallSessionStore.dispatch(CallEvent.EndCall)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    const val ACTION_START_CALL = "me.fleey.ppocrv5.action.START_CALL"
    const val ACTION_END_CALL = "me.fleey.ppocrv5.action.END_CALL"
    const val ACTION_STOP_CALL = "me.fleey.ppocrv5.action.STOP_CALL"
    const val ACTION_START_SCREEN_SHARE = "me.fleey.ppocrv5.action.START_SCREEN_SHARE"
    const val ACTION_STOP_SCREEN_SHARE = "me.fleey.ppocrv5.action.STOP_SCREEN_SHARE"
    const val ACTION_SET_SPEAKER_INPUT = "me.fleey.ppocrv5.action.SET_SPEAKER_INPUT"
    const val ACTION_MANUAL_QUERY = "me.fleey.ppocrv5.action.MANUAL_QUERY"
    const val ACTION_START_WECHAT_CAPTURE = "me.fleey.ppocrv5.action.START_WECHAT_CAPTURE"
    const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
    const val EXTRA_PROJECTION_DATA = "projection_data"
    const val EXTRA_SPEAKER_INPUT_ENABLED = "speaker_input_enabled"
    const val EXTRA_QUERY = "query"
    private const val CHANNEL_ID = "local-ai-call"
    private const val NOTIFICATION_ID = 1001
    private const val MAX_WAKE_LOCK_MILLIS = 6 * 60 * 60 * 1000L
    private const val AUTOMATION_TIMEOUT_MILLIS = 6_000L
    private const val WECHAT_CAPTURE_BUTTON_QUERY = "微信商品关键帧"
    private const val WECHAT_FRAME_LOG_TAG = "zunyu"
  }

  private fun cancelAutomation(token: Long) {
    if (token <= 0L) return
    captureSwipeToken?.let { swipeToken ->
      LocalCallAutomationCommandStore.cancel(swipeToken)
      LocalCallAutomationResultBus.unsubscribe(swipeToken)
      captureSwipeToken = null
    }
    LocalCallAutomationCommandStore.cancel(token)
    LocalCallAutomationResultBus.unsubscribe(token)
    removeAutomationTimeout(token)
  }

  private fun removeAutomationTimeout(token: Long) {
    val timeout = synchronized(sessionLock) { automationTimeouts.remove(token) }
    if (timeout != null) mainHandler.removeCallbacks(timeout)
  }

  private data class AcceptedTurn(
    val generation: Long,
    val queryToken: Long,
    val query: String,
    val acknowledgement: SpeechRequest?,
    val intent: IntentMatch,
    val accessibilityCommand: AccessibilityCommand?,
    val captureCount: Int,
  )
}
