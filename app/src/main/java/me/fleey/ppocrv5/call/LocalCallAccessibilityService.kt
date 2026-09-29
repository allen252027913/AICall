package me.fleey.ppocrv5.call

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class LocalCallAccessibilityService : AccessibilityService() {
  private val jdTiming = JdAutomationTimingPolicy.DEFAULT
  private val weChatTiming = WeChatAutomationTimingPolicy.DEFAULT
  private val handler = Handler(Looper.getMainLooper())
  private val speechPolicy = AccessibilityCommandSpeechPolicy()
  private val leaseGate = AccessibilityCommandLeaseGate(
    LocalCallAutomationCommandStore::executeIfActive,
  )
  private val processRunnable = Runnable(::processAvailableCommand)
  private val pageLogScheduler = AccessibilityPageLogScheduler(
    postDelayed = { runnable, delayMillis -> handler.postDelayed(runnable, delayMillis) },
    removeCallbacks = handler::removeCallbacks,
    logPage = ::logCurrentPage,
  )
  private var activeCommand: AutomationCommandEnvelope? = null
  private var activeFallbackCompletion: JdFallbackGestureCompletion? = null
  private var activeWeChatTopSearchGesture: AccessibilityGestureCompletion? = null
  private var activeWeChatCoordinateGestureToken: Long? = null
  private var activeWeChatKeyFrameToken: Long? = null
  private var activeWeChatKeyFrameTimeout: Runnable? = null
  private var activeWeChatFocusedInput: AccessibilityNodeInfo? = null
  private var fallbackDeadlineMillis = 0L
  private var activeGestureWatchdog: GestureWatchdog? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    LocalCallAutomationCommandStore.setOnCommandAvailable {
      handler.removeCallbacks(processRunnable)
      handler.post(processRunnable)
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    event ?: return
    captureWeChatFocusedInput(event)
    updateFocusedWindow(event)
    val active = activeCommand
    val eventPackage = event.packageName?.toString()
    AccessibilityPageLogPolicy.requestFor(
      eventType = event.eventType,
      windowStateChangedEventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      packageName = eventPackage,
      pageName = event.className?.toString(),
    )?.let(pageLogScheduler::schedule)
    if (active == null || when (active.command) {
        is AccessibilityCommand.JdCheckIn ->
          eventPackage == LocalCallJdAutomationController.JD_PACKAGE
        is AccessibilityCommand.WeChatProductCapture ->
          eventPackage == LocalCallWeChatAutomationController.WECHAT_PACKAGE
        else -> true
      }
    ) {
      handler.removeCallbacks(processRunnable)
      handler.postDelayed(processRunnable, EVENT_COALESCE_MILLIS)
    }
  }

  override fun onInterrupt() {
    activeCommand?.let { envelope ->
      when (envelope.command) {
        is AccessibilityCommand.JdCheckIn -> finishJd(envelope, JdAutomationResult.INTERRUPTED)
        is AccessibilityCommand.WeChatProductCapture ->
          finishWeChat(envelope, WeChatAutomationResult.INTERRUPTED)
        else -> finish(envelope, succeeded = false, message = "辅助功能操作被系统中断。")
      }
    }
  }

  override fun onDestroy() {
    LocalCallAutomationCommandStore.setOnCommandAvailable(null)
    handler.removeCallbacks(processRunnable)
    pageLogScheduler.cancel()
    activeCommand?.let { envelope ->
      when (envelope.command) {
        is AccessibilityCommand.JdCheckIn ->
          finishJd(envelope, JdAutomationResult.INTERRUPTED, processNext = false)
        is AccessibilityCommand.WeChatProductCapture ->
          finishWeChat(envelope, WeChatAutomationResult.INTERRUPTED, processNext = false)
        else ->
          finish(envelope, succeeded = false, message = "辅助功能服务已停止。", processNext = false)
      }
    }
    super.onDestroy()
  }

  private fun updateFocusedWindow(event: AccessibilityEvent) {
    val root = rootInActiveWindow
    val rootPackage = try { root?.packageName?.toString() } finally { root?.recycleCompat() }
    val activityName = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      event.className?.toString()?.takeIf(String::isNotBlank)
    } else null
    FocusedScreenContentStore.updateFromActiveRoot(
      rootPackage, event.packageName?.toString(), activityName, System.currentTimeMillis(),
    )
  }

  private fun captureWeChatFocusedInput(event: AccessibilityEvent) {
    if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return
    if (event.packageName?.toString() != LocalCallWeChatAutomationController.WECHAT_PACKAGE) return
    if (activeCommand?.command !is AccessibilityCommand.WeChatProductCapture) return
    val source = event.source ?: return
    try {
      if (!WeChatAccessibilityNodePolicy.isQueryInput(source.isEditable, source.isEnabled)) return
      @Suppress("DEPRECATION")
      val retainedSource = AccessibilityNodeInfo.obtain(source)
      clearWeChatFocusedInput()
      activeWeChatFocusedInput = retainedSource
    } finally {
      source.recycleCompat()
    }
  }

  private fun processAvailableCommand() {
    val active = activeCommand
    if (active != null) {
      if (!LocalCallAutomationCommandStore.isClaimActive(active.token)) {
        abandonCancelledCommand(active)
        return
      }
      when (val command = active.command) {
        is AccessibilityCommand.JdCheckIn -> processJd(active, command.machine)
        is AccessibilityCommand.WeChatProductCapture -> processWeChat(active, command.machine)
        else -> Unit
      }
      return
    }
    when (val claim = LocalCallAutomationCommandStore.claim(SystemClock.elapsedRealtime())) {
      null -> Unit
      is CommandClaim.Stale -> LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(claim.token, false, "辅助功能指令已过期，请重新说一次。"),
      )
      is CommandClaim.Ready -> {
        activeCommand = claim.envelope
        execute(claim.envelope)
      }
    }
  }

  private fun execute(envelope: AutomationCommandEnvelope) {
    when (val command = envelope.command) {
      is AccessibilityCommand.Swipe -> executeSwipe(envelope, command)
      is AccessibilityCommand.ClickListItem -> finish(
        envelope,
        succeeded = withRoot { root -> clickListItem(envelope.token, root, command.index) },
      )
      is AccessibilityCommand.ClickText -> finish(
        envelope,
        succeeded = withRoot { root -> clickText(envelope.token, root, command.text) },
      )
      is AccessibilityCommand.JdCheckIn -> processJd(envelope, command.machine)
      is AccessibilityCommand.WeChatProductCapture -> processWeChat(envelope, command.machine)
    }
  }

  private fun processJd(
    envelope: AutomationCommandEnvelope,
    machine: JdAutomationStateMachine,
  ) {
    if (!isCurrentJd(envelope.token)) {
      clearStaleJd(envelope.token)
      return
    }
    val now = SystemClock.elapsedRealtime()
    activeFallbackCompletion?.let { pending ->
      if (now >= fallbackDeadlineMillis) pending.onTimedOut()
      else scheduleProcess(fallbackDeadlineMillis - now)
      return
    }
    when (val decision = machine.next(now)) {
      JdAutomationDecision.WAIT_FOR_JD_LAUNCH -> scheduleProcess(RETRY_MILLIS)
      JdAutomationDecision.SEARCH_MY -> searchAndClickJd(envelope, machine, JdTarget.MY)
      JdAutomationDecision.SEARCH_MORE -> searchAndClickJd(envelope, machine, JdTarget.MORE)
      JdAutomationDecision.SEARCH_CLAIM_ALL ->
        searchAndClickJd(envelope, machine, JdTarget.CLAIM_ALL)
      JdAutomationDecision.TAP_MORE_FALLBACK ->
        dispatchJdFallback(envelope, machine, JdTarget.MORE)
      JdAutomationDecision.TAP_CLAIM_ALL_FALLBACK ->
        dispatchJdFallback(envelope, machine, JdTarget.CLAIM_ALL)
      JdAutomationDecision.FAIL_TIMEOUT -> finishJd(
        envelope,
        if (focusedPackageIsWrong()) JdAutomationResult.WRONG_ROOT
        else JdAutomationResult.TIMED_OUT,
      )
      JdAutomationDecision.IGNORE -> Unit
    }
  }

  private fun processWeChat(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    if (!isCurrentWeChat(envelope.token)) {
      clearStaleWeChat(envelope.token)
      return
    }
    if (
      activeWeChatTopSearchGesture != null ||
      activeWeChatCoordinateGestureToken != null ||
      activeWeChatKeyFrameToken != null
    ) return
    val now = SystemClock.elapsedRealtime()
    when (machine.next(now)) {
      WeChatAutomationDecision.WAIT_FOR_WECHAT_LAUNCH -> scheduleProcess(RETRY_MILLIS)
      WeChatAutomationDecision.SEARCH_TOP_SEARCH ->
        searchAndClickWeChat(envelope, machine, WeChatTarget.TOP_SEARCH)
      WeChatAutomationDecision.ENTER_QUERY -> enterWeChatQuery(envelope, machine)
      WeChatAutomationDecision.SUBMIT_IME_SEARCH -> submitWeChatQuery(envelope, machine)
      WeChatAutomationDecision.OPEN_SERVICE_CENTER ->
        searchAndClickWeChat(envelope, machine, WeChatTarget.SERVICE_CENTER)
      WeChatAutomationDecision.OPEN_LIVE_PREVIEW ->
        searchAndClickWeChat(envelope, machine, WeChatTarget.LIVE_PREVIEW)
      WeChatAutomationDecision.SELECT_NEXT_DAY -> selectNextWeChatDay(envelope, machine)
      WeChatAutomationDecision.WAIT_FOR_PRODUCT_LIST -> scheduleProcess(RETRY_MILLIS)
      WeChatAutomationDecision.OPEN_FIRST_PRODUCT -> openFirstWeChatProduct(envelope, machine)
      WeChatAutomationDecision.WAIT_FOR_PRODUCT_DETAIL -> scheduleProcess(RETRY_MILLIS)
      WeChatAutomationDecision.COMPLETE -> {
        machine.finish(envelope.token)
        finishWeChat(envelope, WeChatAutomationResult.COMPLETED)
      }
      WeChatAutomationDecision.FAIL_TIMEOUT -> finishWeChat(
        envelope,
        if (focusedPackageIsWrong(LocalCallWeChatAutomationController.WECHAT_PACKAGE)) {
          WeChatAutomationResult.WRONG_ROOT
        } else {
          WeChatAutomationResult.TIMED_OUT
        },
      )
      WeChatAutomationDecision.IGNORE -> Unit
    }
  }

  private fun searchAndClickWeChat(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
    target: WeChatTarget,
  ) {
    if (target == WeChatTarget.TOP_SEARCH) {
      if (!dispatchWeChatTopSearchTap(envelope, machine)) scheduleProcess(RETRY_MILLIS)
      return
    }
    if (requestWeChatKeyFrame(envelope.token) { frame ->
        when (val action = WeChatVisualTargetLocator.targetAction(frame, target)) {
          is WeChatFrameTargetAction.ClickIntermediate ->
            dispatchWeChatVisualClick(envelope.token, action.point) {
              scheduleProcess(PAGE_TRANSITION_MILLIS)
            }
          is WeChatFrameTargetAction.ClickTarget -> {
            Log.d(
              PAGE_LOG_TAG,
              "wechat_visual_target target=${target.name} " +
                "center=${action.point.x.toInt()},${action.point.y.toInt()}",
            )
            dispatchWeChatVisualClick(envelope.token, action.point) {
              completeWeChatTargetClick(envelope, machine, target)
            }
          }
          null -> scheduleProcess(RETRY_MILLIS)
        }
      }
    ) return

    val root = weChatRootOrNull()
    if (root == null) {
      scheduleProcess(RETRY_MILLIS)
      return
    }
    val clickMethod = try {
      if (target == WeChatTarget.TOP_SEARCH) {
        WeChatTopSearchClickStrategy.execute(
          clickAccessibleNode = {
            findAndClickWeChatTarget(envelope.token, root, target)
          },
          dispatchPositionTap = {
            dispatchWeChatTopSearchTap(envelope, machine)
          },
        )
      } else {
        WeChatTopSearchClickMethod.ACCESSIBILITY_NODE.takeIf {
          findAndClickWeChatTarget(envelope.token, root, target)
        }
      }
    } finally {
      root.recycleCompat()
    }
    if (clickMethod == WeChatTopSearchClickMethod.POSITION_GESTURE) return
    if (clickMethod == null) {
      scheduleProcess(RETRY_MILLIS)
      return
    }
    val clickedAtMillis = SystemClock.elapsedRealtime()
    val transitioned = leaseGate.transitionAndFeedback(
      token = envelope.token,
      transition = {
        machine.onTargetClicked(envelope.token, target, clickedAtMillis)
      },
      feedback = {
        Toast.makeText(applicationContext, "已点击「${target.label}」", Toast.LENGTH_SHORT).show()
      },
    )
    if (!transitioned) {
      if (
        isCurrentWeChat(envelope.token) &&
        machine.next(clickedAtMillis) == WeChatAutomationDecision.FAIL_TIMEOUT
      ) {
        finishWeChat(envelope, WeChatAutomationResult.TIMED_OUT)
      } else {
        clearStaleWeChat(envelope.token)
      }
      return
    }
    scheduleProcess(
      when (target) {
        WeChatTarget.SUBMIT_SEARCH, WeChatTarget.NEXT_DAY -> CONTENT_REFRESH_MILLIS
        else -> PAGE_TRANSITION_MILLIS
      },
    )
  }

  private fun completeWeChatTargetClick(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
    target: WeChatTarget,
  ) {
    val clickedAtMillis = SystemClock.elapsedRealtime()
    if (!leaseGate.transitionAndFeedback(
        token = envelope.token,
        transition = { machine.onTargetClicked(envelope.token, target, clickedAtMillis) },
        feedback = {
          Toast.makeText(
            applicationContext,
            "已从关键帧中心点击「${target.label}」",
            Toast.LENGTH_SHORT,
          ).show()
        },
      )
    ) {
      clearStaleWeChat(envelope.token)
      return
    }
    scheduleProcess(
      if (target == WeChatTarget.NEXT_DAY) CONTENT_REFRESH_MILLIS else PAGE_TRANSITION_MILLIS,
    )
  }

  private fun selectNextWeChatDay(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    if (requestWeChatKeyFrame(envelope.token) { frame ->
        when (
          val action = WeChatVisualTargetLocator.nextDayAction(
            frame,
            machine.targetDayOfMonth,
          )
        ) {
          is WeChatFrameTargetAction.ClickIntermediate -> {
            Log.d(
              PAGE_LOG_TAG,
              "wechat_visual_next_date_page target=${machine.targetDayOfMonth} " +
                "center=${action.point.x.toInt()},${action.point.y.toInt()}",
            )
            dispatchWeChatVisualClick(envelope.token, action.point) {
              scheduleProcess(PAGE_TRANSITION_MILLIS)
            }
          }
          is WeChatFrameTargetAction.ClickTarget -> {
            Log.d(
              PAGE_LOG_TAG,
              "wechat_visual_next_day target=${machine.targetDayOfMonth} " +
                "center=${action.point.x.toInt()},${action.point.y.toInt()}",
            )
            dispatchWeChatVisualClick(envelope.token, action.point) {
              completeWeChatTargetClick(envelope, machine, WeChatTarget.NEXT_DAY)
            }
          }
          null -> scheduleProcess(RETRY_MILLIS)
        }
      }
    ) return
    scheduleProcess(RETRY_MILLIS)
  }

  private fun dispatchWeChatTopSearchTap(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ): Boolean = requestWeChatKeyFrame(envelope.token) { frame ->
    when (val action = WeChatVisualTargetLocator.topSearchAction(frame)) {
      is WeChatTopSearchFrameAction.SelectChatsTab ->
        dispatchWeChatVisualClick(envelope.token, action.point) {
          scheduleProcess(PAGE_TRANSITION_MILLIS)
        }
      is WeChatTopSearchFrameAction.OpenSearch ->
        dispatchWeChatVisualClick(envelope.token, action.point) {
          transitionAfterVisualTopSearch(envelope, machine)
        }
      WeChatTopSearchFrameAction.SearchAlreadyOpen ->
        transitionAfterVisualTopSearch(envelope, machine)
      null -> scheduleProcess(RETRY_MILLIS)
    }
  }

  private fun transitionAfterVisualTopSearch(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    val completedAtMillis = SystemClock.elapsedRealtime()
    if (!leaseGate.transitionAndFeedback(
        token = envelope.token,
        transition = {
          machine.onTargetClicked(envelope.token, WeChatTarget.TOP_SEARCH, completedAtMillis)
        },
        feedback = {
          Toast.makeText(applicationContext, "已从关键帧定位并点击「搜索」", Toast.LENGTH_SHORT)
            .show()
        },
      )
    ) {
      clearStaleWeChat(envelope.token)
      return
    }
    scheduleProcess(PAGE_TRANSITION_MILLIS)
  }

  private fun enterWeChatQuery(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    if (requestWeChatKeyFrame(envelope.token) { frame ->
        val input = WeChatVisualTargetLocator.queryInput(frame)
        if (input == null) {
          scheduleProcess(RETRY_MILLIS)
        } else if (!startWeChatKeyboardQueryFallback(envelope, machine, frame, input)) {
          scheduleProcess(RETRY_MILLIS)
        }
      }
    ) return
    scheduleProcess(RETRY_MILLIS)
  }

  private fun submitWeChatQuery(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    if (startWeChatKeyboardSearchFallback(envelope, machine)) return
    scheduleProcess(RETRY_MILLIS)
  }

  private fun startWeChatKeyboardQueryFallback(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
    frame: WeChatKeyFrame,
    queryInput: WeChatScreenPoint,
  ): Boolean {
    if (activeWeChatCoordinateGestureToken != null) return true
    val plan = WeChatKeyboardTapPolicy.planFor(frame.width, frame.height)
      ?: return false
    Log.d(
      PAGE_LOG_TAG,
      "wechat_visual_input center=${queryInput.x.toInt()},${queryInput.y.toInt()} " +
        "screen=${frame.width}x${frame.height}",
    )
    activeWeChatCoordinateGestureToken = envelope.token
    dispatchWeChatCoordinateGesture(envelope.token, tapGesture(queryInput)) {
      handler.postDelayed(
        {
          if (!isCurrentWeChatCoordinateGesture(envelope.token)) return@postDelayed
          requestWeChatKeyFrame(
            token = envelope.token,
            onFailure = { failWeChatCoordinateFlow(envelope.token) },
          ) { keyboardFrame ->
            val pinyinKeys = WeChatVisualTargetLocator.pinyinKeys(keyboardFrame)
              ?: plan.pinyinKeys.takeIf { WeChatVisualTargetLocator.hasKeyboard(keyboardFrame) }
            if (pinyinKeys == null) {
              failWeChatCoordinateFlow(envelope.token)
              return@requestWeChatKeyFrame
            }
            Log.d(PAGE_LOG_TAG, "wechat_visual_keyboard keys=$pinyinKeys")
            dispatchWeChatCoordinateGesture(
              envelope.token,
              multiTapGesture(pinyinKeys),
            ) {
              handler.postDelayed(
                {
                  if (!isCurrentWeChatCoordinateGesture(envelope.token)) return@postDelayed
                  requestWeChatKeyFrame(
                    token = envelope.token,
                    onFailure = { failWeChatCoordinateFlow(envelope.token) },
                  ) { candidateFrame ->
                    val candidate = WeChatVisualTargetLocator.queryCandidate(candidateFrame)
                      ?: plan.firstCandidate.takeIf {
                        WeChatVisualTargetLocator.hasKeyboard(candidateFrame)
                      }
                    if (candidate == null) {
                      failWeChatCoordinateFlow(envelope.token)
                      return@requestWeChatKeyFrame
                    }
                    Log.d(
                      PAGE_LOG_TAG,
                      "wechat_visual_candidate center=${candidate.x.toInt()},${candidate.y.toInt()}",
                    )
                    dispatchWeChatCoordinateGesture(
                      envelope.token,
                      tapGesture(candidate),
                    ) {
                      completeWeChatKeyboardQueryFallback(envelope, machine)
                    }
                  }
                },
                KEYBOARD_CANDIDATE_SETTLE_MILLIS,
              )
            }
          }
        },
        KEYBOARD_OPEN_SETTLE_MILLIS,
      )
    }
    return true
  }

  private fun completeWeChatKeyboardQueryFallback(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    if (!isCurrentWeChatCoordinateGesture(envelope.token)) return
    activeWeChatCoordinateGestureToken = null
    if (!leaseGate.transitionAndFeedback(
        token = envelope.token,
        transition = { machine.onQueryEntered(envelope.token, SystemClock.elapsedRealtime()) },
        feedback = {
          Toast.makeText(
            applicationContext,
            "已通过系统键盘输入“$WECHAT_QUERY”",
            Toast.LENGTH_SHORT,
          ).show()
        },
      )
    ) {
      clearStaleWeChat(envelope.token)
      return
    }
    scheduleProcess(INPUT_SETTLE_MILLIS)
  }

  private fun startWeChatKeyboardSearchFallback(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ): Boolean {
    if (activeWeChatCoordinateGestureToken != null || activeWeChatKeyFrameToken != null) return true
    return requestWeChatKeyFrame(envelope.token) { frame ->
      val plan = WeChatKeyboardTapPolicy.planFor(frame.width, frame.height)
      val search = WeChatVisualTargetLocator.keyboardSearch(frame)
        ?: plan?.keyboardSearch?.takeIf { WeChatVisualTargetLocator.hasKeyboard(frame) }
      if (search == null) {
        scheduleProcess(RETRY_MILLIS)
        return@requestWeChatKeyFrame
      }
      Log.d(
        PAGE_LOG_TAG,
        "wechat_visual_keyboard_search center=${search.x.toInt()},${search.y.toInt()}",
      )
      activeWeChatCoordinateGestureToken = envelope.token
      dispatchWeChatCoordinateGesture(envelope.token, tapGesture(search)) {
        if (!isCurrentWeChatCoordinateGesture(envelope.token)) {
          return@dispatchWeChatCoordinateGesture
        }
        activeWeChatCoordinateGestureToken = null
        val submittedAtMillis = SystemClock.elapsedRealtime()
        if (!leaseGate.transitionAndFeedback(
            token = envelope.token,
            transition = {
              machine.onTargetClicked(
                envelope.token,
                WeChatTarget.SUBMIT_SEARCH,
                submittedAtMillis,
              )
            },
            feedback = {
              Toast.makeText(applicationContext, "已点击键盘搜索", Toast.LENGTH_SHORT).show()
            },
          )
        ) {
          clearStaleWeChat(envelope.token)
          return@dispatchWeChatCoordinateGesture
        }
        scheduleProcess(CONTENT_REFRESH_MILLIS)
      }
    }
  }

  private fun failWeChatCoordinateFlow(token: Long) {
    if (activeWeChatCoordinateGestureToken == token) activeWeChatCoordinateGestureToken = null
    if (isCurrentWeChat(token)) scheduleProcess(RETRY_MILLIS)
  }

  private fun dispatchWeChatCoordinateGesture(
    token: Long,
    gesture: GestureDescription,
    onCompleted: () -> Unit,
  ) {
    lateinit var completion: AccessibilityGestureCompletion
    completion = AccessibilityGestureCompletion(
      onCompleted = {
        cancelGestureWatchdog(token)
        if (isCurrentWeChatCoordinateGesture(token)) onCompleted()
      },
      onFailed = {
        cancelGestureWatchdog(token)
        if (activeWeChatCoordinateGestureToken == token) {
          activeWeChatCoordinateGestureToken = null
          if (isCurrentWeChat(token)) scheduleProcess(RETRY_MILLIS)
        }
      },
    )
    val accepted = completion.dispatch {
      LocalCallAutomationCommandStore.runIfActive(token) {
        dispatchGesture(
          gesture,
          object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) =
              completion.onCompleted()

            override fun onCancelled(gestureDescription: GestureDescription?) =
              completion.onCancelled()
          },
          handler,
        )
      }
    }
    if (accepted && isCurrentWeChatCoordinateGesture(token)) {
      scheduleGestureWatchdog(token, completion)
    }
  }

  private fun dispatchWeChatVisualClick(
    token: Long,
    point: WeChatScreenPoint,
    onCompleted: () -> Unit,
  ) {
    activeWeChatCoordinateGestureToken = token
    dispatchWeChatCoordinateGesture(token, tapGesture(point)) {
      if (!isCurrentWeChatCoordinateGesture(token)) return@dispatchWeChatCoordinateGesture
      activeWeChatCoordinateGestureToken = null
      onCompleted()
    }
  }

  private fun requestWeChatKeyFrame(
    token: Long,
    onFailure: () -> Unit = { if (isCurrentWeChat(token)) scheduleProcess(RETRY_MILLIS) },
    onCaptured: (WeChatKeyFrame) -> Unit,
  ): Boolean {
    if (activeWeChatKeyFrameToken != null) return true
    activeWeChatKeyFrameToken = token
    val timeout = Runnable {
      if (activeWeChatKeyFrameToken != token) return@Runnable
      clearWeChatKeyFrameRequest()
      onFailure()
    }
    activeWeChatKeyFrameTimeout = timeout
    handler.postDelayed(timeout, KEY_FRAME_TIMEOUT_MILLIS)
    val accepted = WeChatKeyFrameCaptureBridge.request(token) { result ->
      handler.post {
        if (activeWeChatKeyFrameToken != token) return@post
        clearWeChatKeyFrameRequest()
        if (!isCurrentWeChat(token)) return@post
        result.fold(
          onSuccess = onCaptured,
          onFailure = {
            Log.w(PAGE_LOG_TAG, "wechat_keyframe_failed", it)
            onFailure()
          },
        )
      }
    }
    if (!accepted) {
      clearWeChatKeyFrameRequest()
      return false
    }
    return true
  }

  private fun clearWeChatKeyFrameRequest() {
    activeWeChatKeyFrameTimeout?.let(handler::removeCallbacks)
    activeWeChatKeyFrameTimeout = null
    activeWeChatKeyFrameToken = null
  }

  private fun tapGesture(point: WeChatScreenPoint): GestureDescription =
    GestureDescription.Builder()
      .addStroke(tapStroke(point, startTimeMillis = 0L))
      .build()

  private fun multiTapGesture(points: List<WeChatScreenPoint>): GestureDescription =
    GestureDescription.Builder().apply {
      points.forEachIndexed { index, point ->
        addStroke(tapStroke(point, startTimeMillis = index * KEYBOARD_KEY_INTERVAL_MILLIS))
      }
    }.build()

  private fun tapStroke(
    point: WeChatScreenPoint,
    startTimeMillis: Long,
  ): GestureDescription.StrokeDescription {
    val path = Path().apply { moveTo(point.x, point.y) }
    return GestureDescription.StrokeDescription(path, startTimeMillis, FALLBACK_TAP_MILLIS)
  }

  private fun isCurrentWeChatCoordinateGesture(token: Long): Boolean =
    activeWeChatCoordinateGestureToken == token && isCurrentWeChat(token)

  private fun fullScreenDimensions(): Pair<Int, Int> {
    val fallback = resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
    val bounds = getSystemService(WindowManager::class.java)
      ?.maximumWindowMetrics
      ?.bounds
      ?: return fallback
    return bounds.width() to bounds.height()
  }

  private fun openFirstWeChatProduct(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    if (requestWeChatKeyFrame(envelope.token) { frame ->
        val point = WeChatVisualTargetLocator.firstProduct(frame)
        if (point == null) {
          scheduleProcess(RETRY_MILLIS)
        } else {
          Log.d(PAGE_LOG_TAG, "wechat_first_product center=${point.x.toInt()},${point.y.toInt()}")
          dispatchWeChatVisualClick(envelope.token, point) {
            completeFirstWeChatProductClick(envelope, machine)
          }
        }
      }
    ) return

    val root = weChatRootOrNull()
    if (root == null) {
      scheduleProcess(RETRY_MILLIS)
      return
    }
    val point = try {
      findFirstWeChatProductPoint(root)
    } finally {
      root.recycleCompat()
    }
    if (point == null) {
      scheduleProcess(RETRY_MILLIS)
      return
    }
    dispatchWeChatVisualClick(envelope.token, point) {
      completeFirstWeChatProductClick(envelope, machine)
    }
  }

  private fun completeFirstWeChatProductClick(
    envelope: AutomationCommandEnvelope,
    machine: WeChatAutomationStateMachine,
  ) {
    if (!leaseGate.transitionAndFeedback(
        token = envelope.token,
        transition = {
          machine.onFirstProductClicked(envelope.token, SystemClock.elapsedRealtime())
        },
        feedback = {
          Toast.makeText(applicationContext, "已从关键帧中心点击首个商品入口", Toast.LENGTH_SHORT)
            .show()
        },
      )
    ) {
      clearStaleWeChat(envelope.token)
      return
    }
    scheduleProcess(weChatTiming.detailSettleMillis)
  }

  private fun findAndClickWeChatTarget(
    token: Long,
    root: AccessibilityNodeInfo,
    target: WeChatTarget,
  ): Boolean {
    if (
      target == WeChatTarget.TOP_SEARCH &&
      findAndClickWeChatTextQuery(token, root, target)
    ) {
      return true
    }
    return DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      if (!WeChatAccessibilityNodePolicy.matches(node.text, node.contentDescription, target)) {
        return@firstResult null
      }
      true.takeIf { clickNodeOrAncestor(token, node) }
    } ?: false
  }

  private fun findAndClickWeChatTextQuery(
    token: Long,
    root: AccessibilityNodeInfo,
    target: WeChatTarget,
  ): Boolean {
    val candidates = runCatching {
      root.findAccessibilityNodeInfosByText(target.label)
    }.getOrNull().orEmpty()
    return try {
      WeChatTextQueryClickStrategy.click(
        candidates = candidates,
        matches = { node ->
          WeChatAccessibilityNodePolicy.matches(node.text, node.contentDescription, target)
        },
        performDirectClick = { node -> performNodeClickAction(token, node) },
        performAncestorClick = { node -> clickAncestorOnly(token, node) },
      )
    } finally {
      candidates.forEach { it.recycleCompat() }
    }
  }

  private fun setWeChatQuery(token: Long, root: AccessibilityNodeInfo): Boolean =
    DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      true.takeIf { setWeChatQueryOnNode(token, node) }
    } ?: false

  private fun setWeChatQueryOnNode(token: Long, node: AccessibilityNodeInfo): Boolean {
    if (!WeChatAccessibilityNodePolicy.isQueryInput(node.isEditable, node.isEnabled)) return false
    val arguments = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, WECHAT_QUERY)
    }
    return LocalCallAutomationCommandStore.runIfActive(token) {
      runCatching {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        WeChatVerifiedQueryEntryStrategy.enter(
          expectedText = WECHAT_QUERY,
          currentText = { node.text },
          performSetText = {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
          },
          prepareClipboard = ::copyWeChatQueryToClipboard,
          performPaste = { pasteWeChatQueryOnNode(node) },
          refresh = { node.refresh() },
        ) != null
      }.getOrDefault(false)
    }
  }

  private fun copyWeChatQueryToClipboard(): Boolean {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(WECHAT_CLIP_LABEL, WECHAT_QUERY))
    return true
  }

  private fun pasteWeChatQueryOnNode(node: AccessibilityNodeInfo): Boolean {
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val currentLength = node.text?.length ?: 0
    if (currentLength > 0) {
      val selectionArguments = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentLength)
      }
      node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArguments)
    }
    return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
  }

  private fun submitWeChatQueryFromRoot(
    token: Long,
    root: AccessibilityNodeInfo,
  ): Boolean =
    DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      true.takeIf { submitWeChatQueryOnNode(token, node) }
    } ?: false

  private fun submitWeChatQueryOnNode(token: Long, node: AccessibilityNodeInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return WeChatImeSearchActionPolicy.submit(
      sdkInt = Build.VERSION.SDK_INT,
      editable = node.isEditable,
      enabled = node.isEnabled,
      focused = node.isFocused,
      performImeEnter = {
        LocalCallAutomationCommandStore.runIfActive(token) {
          runCatching {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
          }.getOrDefault(false)
        }
      },
    )
  }

  private fun findFirstWeChatProductPoint(
    root: AccessibilityNodeInfo,
  ): WeChatScreenPoint? {
    val blocks = mutableListOf<ScreenTextBlock>()
    DepthFirstTraversal.forEach(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      val bounds = Rect().also(node::getBoundsInScreen)
      if (node.isVisibleToUser && !bounds.isEmpty) {
        val text = node.text?.toString()?.takeIf(String::isNotBlank)
          ?: node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank()) blocks += ScreenTextBlock(
          text, 1f, bounds.exactCenterX(), bounds.exactCenterY(),
        )
      }
    }
    val (width, height) = fullScreenDimensions()
    return WeChatVisualTargetLocator.firstProduct(WeChatKeyFrame(width, height, blocks))
  }

  private fun weChatRootOrNull(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    if (root.packageName?.toString() == LocalCallWeChatAutomationController.WECHAT_PACKAGE) return root
    root.recycleCompat()
    return null
  }

  private fun logCurrentPage(request: AccessibilityPageLogRequest) {
    Log.d(
      PAGE_LOG_TAG,
      WeChatPageLogFormatter.pageStart(
        request.packageName,
        request.pageName,
      ),
    )
    val root = rootInActiveWindow
    if (root == null) {
      Log.d(PAGE_LOG_TAG, WeChatPageLogFormatter.pageEnd(0))
      return
    }
    var nodeCount = 0
    try {
      nodeCount = AccessibilityWidgetTreeLogger.log(
        root = AndroidTraversalNode(root),
        values = { traversalNode ->
          val node = (traversalNode as AndroidTraversalNode).node
          AccessibilityWidgetLogValues(
            className = node.className?.toString(),
            text = node.text,
            contentDescription = node.contentDescription,
          )
        },
        emit = { line -> Log.d(PAGE_LOG_TAG, line) },
      )
    } catch (error: Throwable) {
      Log.w(PAGE_LOG_TAG, "page_traversal_failed", error)
    } finally {
      root.recycleCompat()
    }
    Log.d(PAGE_LOG_TAG, WeChatPageLogFormatter.pageEnd(nodeCount))
  }

  private fun isCurrentWeChat(token: Long): Boolean =
    activeCommand?.token == token && LocalCallAutomationCommandStore.isClaimActive(token)

  private fun clearStaleWeChat(token: Long) {
    if (activeCommand?.token != token) return
    handler.removeCallbacks(processRunnable)
    cancelGestureWatchdog(token)
    activeWeChatTopSearchGesture = null
    activeWeChatCoordinateGestureToken = null
    clearWeChatKeyFrameRequest()
    clearWeChatFocusedInput()
    LocalCallAutomationCommandStore.executeIfActive(token) {
      (activeCommand?.command as? AccessibilityCommand.WeChatProductCapture)
        ?.machine
        ?.finish(token)
    }
    activeCommand = null
    LocalCallAutomationCommandStore.cancel(token)
    handler.post(processRunnable)
  }

  private fun finishWeChat(
    envelope: AutomationCommandEnvelope,
    result: WeChatAutomationResult,
    processNext: Boolean = true,
  ) {
    if (activeCommand?.token != envelope.token) return
    cancelGestureWatchdog(envelope.token)
    activeWeChatTopSearchGesture = null
    activeWeChatCoordinateGestureToken = null
    clearWeChatKeyFrameRequest()
    clearWeChatFocusedInput()
    LocalCallAutomationCommandStore.executeIfActive(envelope.token) {
      (envelope.command as? AccessibilityCommand.WeChatProductCapture)
        ?.machine
        ?.finish(envelope.token)
    }
    handler.removeCallbacks(processRunnable)
    activeCommand = null
    val completedLease = LocalCallAutomationCommandStore.completeClaim(envelope.token)
    if (completedLease) {
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(
          envelope.token,
          result == WeChatAutomationResult.COMPLETED,
          WeChatAutomationResultFormatter.format(result),
        ),
      )
    }
    if (processNext) handler.post(processRunnable)
  }

  private fun searchAndClickJd(
    envelope: AutomationCommandEnvelope,
    machine: JdAutomationStateMachine,
    target: JdTarget,
  ) {
    val root = jdRootOrNull()
    if (root == null) {
      scheduleProcess(RETRY_MILLIS)
      return
    }
    val method = try {
      findAndClickJdTarget(envelope.token, root, target)
    } finally {
      root.recycleCompat()
    }
    if (method == null) {
      scheduleProcess(RETRY_MILLIS)
      return
    }
    if (!transitionJdAndShowFeedback(envelope.token, machine, target, method)) {
      clearStaleJd(envelope.token)
      return
    }
    if (target == JdTarget.CLAIM_ALL) {
      finishJd(envelope, JdAutomationResult.COMPLETED)
    } else {
      scheduleProcess(jdTiming.pageTransitionMillis)
    }
  }

  private fun dispatchJdFallback(
    envelope: AutomationCommandEnvelope,
    machine: JdAutomationStateMachine,
    target: JdTarget,
  ) {
    val root = jdRootOrNull()
    if (root == null) {
      finishJd(
        envelope,
        if (focusedPackageIsWrong()) JdAutomationResult.WRONG_ROOT
        else JdAutomationResult.TIMED_OUT,
      )
      return
    }
    root.recycleCompat()
    val point = JdFallbackTapPolicy.pointFor(target) ?: run {
      finishJd(envelope, JdAutomationResult.GESTURE_FAILED)
      return
    }
    lateinit var completion: JdFallbackGestureCompletion
    completion = JdFallbackGestureCompletion(
      onCompleted = {
        if (activeFallbackCompletion !== completion) return@JdFallbackGestureCompletion
        activeFallbackCompletion = null
        fallbackDeadlineMillis = 0L
        if (!transitionJdAndShowFeedback(
            envelope.token,
            machine,
            target,
            JdAutomationClickMethod.FALLBACK_COORDINATE,
          )
        ) {
          clearStaleJd(envelope.token)
          return@JdFallbackGestureCompletion
        }
        if (target == JdTarget.CLAIM_ALL) {
          finishJd(envelope, JdAutomationResult.COMPLETED)
        } else {
          scheduleProcess(jdTiming.pageTransitionMillis)
        }
      },
      onFailed = {
        if (activeFallbackCompletion !== completion) return@JdFallbackGestureCompletion
        activeFallbackCompletion = null
        fallbackDeadlineMillis = 0L
        finishJd(envelope, JdAutomationResult.GESTURE_FAILED)
      },
    )
    activeFallbackCompletion = completion
    fallbackDeadlineMillis = SystemClock.elapsedRealtime() + jdTiming.fallbackCallbackBudgetMillis
    val path = Path().apply { moveTo(point.x, point.y) }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0L, FALLBACK_TAP_MILLIS))
      .build()
    val accepted = completion.dispatch {
      LocalCallAutomationCommandStore.runIfActive(envelope.token) {
        dispatchGesture(
          gesture,
          object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = completion.onCompleted()
            override fun onCancelled(gestureDescription: GestureDescription?) = completion.onCancelled()
          },
          handler,
        )
      }
    }
    if (accepted) scheduleProcess(jdTiming.fallbackCallbackBudgetMillis)
  }

  private fun findAndClickJdTarget(
    token: Long,
    root: AccessibilityNodeInfo,
    target: JdTarget,
  ): JdAutomationClickMethod? =
    DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      if (!JdAccessibilityNodePolicy.matches(node.text, node.contentDescription, target)) {
        return@firstResult null
      }
      val viewId = node.viewIdResourceName
      if (
        JdAccessibilityNodePolicy.clickStrategy(viewId) == JdNodeClickStrategy.VIEW_ID_THEN_NODE
      ) {
        val idAttempt = clickMatchingJdViewId(token, root, viewId.orEmpty(), target)
        return@firstResult when {
          idAttempt.method != null -> idAttempt.method
          idAttempt.attempted -> null
          else -> JdNodeClickExecutor.execute(
            clickDirectNode = { clickNodeOnce(token, node) },
            clickAncestorOnly = { clickAncestorOnly(token, node) },
          )
        }
      }
      JdAutomationClickMethod.ACCESSIBILITY_NODE.takeIf {
        clickNodeOrAncestor(token, node)
      }
    }

  private fun clickMatchingJdViewId(
    token: Long,
    root: AccessibilityNodeInfo,
    viewId: String,
    target: JdTarget,
  ): JdIdClickAttempt {
    val candidates = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
      .getOrNull()
      .orEmpty()
    return try {
      var attempted = false
      candidates.forEach { candidate ->
        if (JdAccessibilityNodePolicy.matches(candidate.text, candidate.contentDescription, target)) {
          attempted = true
          val method = JdNodeClickExecutor.execute(
            clickDirectNode = { clickNodeOnce(token, candidate) },
            clickAncestorOnly = { clickAncestorOnly(token, candidate) },
          )
          if (method != null) return JdIdClickAttempt(attempted = true, method = method)
        }
      }
      JdIdClickAttempt(attempted = attempted, method = null)
    } finally {
      candidates.forEach { it.recycleCompat() }
    }
  }

  private fun jdRootOrNull(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null
    if (root.packageName?.toString() == LocalCallJdAutomationController.JD_PACKAGE) return root
    root.recycleCompat()
    return null
  }

  private fun focusedPackageIsWrong(
    expectedPackage: String = LocalCallJdAutomationController.JD_PACKAGE,
  ): Boolean =
    FocusedScreenContentStore.snapshot()?.packageName
      ?.takeIf(String::isNotBlank)
      ?.let { it != expectedPackage }
      ?: false

  private fun showClickFeedback(target: JdTarget, method: JdAutomationClickMethod) {
    Toast.makeText(
      applicationContext,
      JdAutomationClickFeedback.format(target, method),
      Toast.LENGTH_SHORT,
    ).show()
  }

  private fun transitionJdAndShowFeedback(
    token: Long,
    machine: JdAutomationStateMachine,
    target: JdTarget,
    method: JdAutomationClickMethod,
  ): Boolean = leaseGate.transitionAndFeedback(
    token = token,
    transition = { machine.onTargetClicked(token, target, SystemClock.elapsedRealtime()) },
    feedback = { showClickFeedback(target, method) },
  )

  private fun isCurrentJd(token: Long): Boolean =
    activeCommand?.token == token &&
      LocalCallAutomationCommandStore.isClaimActive(token)

  private fun clearStaleJd(token: Long) {
    if (activeCommand?.token != token) return
    handler.removeCallbacks(processRunnable)
    cancelGestureWatchdog(token)
    activeFallbackCompletion = null
    fallbackDeadlineMillis = 0L
    LocalCallAutomationCommandStore.executeIfActive(token) {
      (activeCommand?.command as? AccessibilityCommand.JdCheckIn)?.machine?.finish(token)
    }
    activeCommand = null
    LocalCallAutomationCommandStore.cancel(token)
    handler.post(processRunnable)
  }

  private fun finishJd(
    envelope: AutomationCommandEnvelope,
    result: JdAutomationResult,
    processNext: Boolean = true,
  ) {
    if (activeCommand?.token != envelope.token) return
    LocalCallAutomationCommandStore.executeIfActive(envelope.token) {
      (envelope.command as? AccessibilityCommand.JdCheckIn)?.machine?.finish(envelope.token)
    }
    handler.removeCallbacks(processRunnable)
    cancelGestureWatchdog(envelope.token)
    activeFallbackCompletion = null
    fallbackDeadlineMillis = 0L
    activeCommand = null
    val completedLease = LocalCallAutomationCommandStore.completeClaim(envelope.token)
    if (completedLease) {
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(
          token = envelope.token,
          succeeded = result == JdAutomationResult.COMPLETED,
          message = JdAutomationResultFormatter.format(result),
        ),
      )
    }
    if (processNext) handler.post(processRunnable)
  }

  private fun scheduleProcess(delayMillis: Long) {
    handler.removeCallbacks(processRunnable)
    handler.postDelayed(processRunnable, delayMillis.coerceAtLeast(0L))
  }

  private fun executeSwipe(
    envelope: AutomationCommandEnvelope,
    command: AccessibilityCommand.Swipe,
  ) {
    val metrics = resources.displayMetrics
    val width = metrics.widthPixels.toFloat()
    val height = metrics.heightPixels.toFloat()
    if (width <= 0f || height <= 0f) {
      finish(envelope, succeeded = false)
      return
    }
    val points = when (command.direction) {
      SwipeDirection.UP -> floatArrayOf(.5f, .5f + command.verticalTravelFraction / 2, .5f, .5f - command.verticalTravelFraction / 2)
      SwipeDirection.DOWN -> floatArrayOf(.5f, .5f - command.verticalTravelFraction / 2, .5f, .5f + command.verticalTravelFraction / 2)
      SwipeDirection.LEFT -> floatArrayOf(.82f, .5f, .18f, .5f)
      SwipeDirection.RIGHT -> floatArrayOf(.18f, .5f, .82f, .5f)
    }
    val path = Path().apply {
      moveTo(points[0] * width, points[1] * height)
      lineTo(points[2] * width, points[3] * height)
    }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0L, command.durationMillis))
      .build()
    lateinit var completion: AccessibilityGestureCompletion
    completion = AccessibilityGestureCompletion(
      onCompleted = {
        cancelGestureWatchdog(envelope.token)
        finish(envelope, succeeded = true)
      },
      onFailed = {
        cancelGestureWatchdog(envelope.token)
        finish(envelope, succeeded = false)
      },
    )
    val accepted = completion.dispatch {
      LocalCallAutomationCommandStore.runIfActive(envelope.token) {
        if (command.requiredPackage != null && !withRoot { it.packageName?.toString() == command.requiredPackage }) {
          return@runIfActive false
        }
        dispatchGesture(
          gesture,
          object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = completion.onCompleted()
            override fun onCancelled(gestureDescription: GestureDescription?) = completion.onCancelled()
          },
          handler,
        )
      }
    }
    if (accepted && isCurrentCommand(envelope.token)) {
      scheduleGestureWatchdog(envelope.token, completion)
    }
  }

  private fun clickText(token: Long, root: AccessibilityNodeInfo, target: String): Boolean =
    DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      if (!matches(node, target)) return@firstResult null
      true.takeIf {
        clickMatchingReliableViewId(token, root, node.viewIdResourceName, target) ||
          clickNodeOrAncestor(token, node)
      }
    } ?: false

  private fun clickListItem(token: Long, root: AccessibilityNodeInfo, oneBasedIndex: Int): Boolean {
    var itemCount = 0
    val itemClicked = DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      if (node.collectionItemInfo == null) return@firstResult null
      itemCount += 1
      if (itemCount == oneBasedIndex) true.takeIf { clickNodeOrAncestor(token, node) } else null
    } ?: false
    if (itemClicked) return true

    return DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
      val node = (traversalNode as AndroidTraversalNode).node
      if (!isListContainer(node) || node.childCount < oneBasedIndex) return@firstResult null
      val child = node.getChild(oneBasedIndex - 1) ?: return@firstResult null
      try {
        true.takeIf { clickNodeOrAncestor(token, child) }
      } finally {
        child.recycleCompat()
      }
    } ?: false
  }

  private fun isListContainer(node: AccessibilityNodeInfo): Boolean {
    if (node.collectionInfo != null) return true
    val className = node.className?.toString().orEmpty()
    return className.endsWith("ListView") || className.endsWith("RecyclerView")
  }

  private fun clickMatchingReliableViewId(
    token: Long,
    root: AccessibilityNodeInfo,
    viewId: String?,
    target: String,
  ): Boolean {
    if (viewId.isNullOrBlank() || !viewId.contains(":id/")) return false
    val candidates = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }
      .getOrNull()
      .orEmpty()
    return try {
      candidates.firstOrNull { matches(it, target) }?.let { clickNodeOrAncestor(token, it) } == true
    } finally {
      candidates.forEach { it.recycleCompat() }
    }
  }

  private fun matches(node: AccessibilityNodeInfo, target: String): Boolean {
    val normalizedTarget = target.trim().lowercase()
    return sequenceOf(node.text, node.contentDescription)
      .mapNotNull { it?.toString()?.trim()?.lowercase() }
      .any { it == normalizedTarget || it.contains(normalizedTarget) }
  }

  private fun clickNodeOrAncestor(token: Long, node: AccessibilityNodeInfo): Boolean {
    if (clickNodeOnce(token, node)) return true
    return clickAncestorOnly(token, node)
  }

  private fun clickNodeOnce(token: Long, node: AccessibilityNodeInfo): Boolean =
    node.isClickable && performNodeClickAction(token, node)

  private fun performNodeClickAction(token: Long, node: AccessibilityNodeInfo): Boolean =
    LocalCallAutomationCommandStore.runIfActive(token) {
      runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

  private fun clickAncestorOnly(token: Long, node: AccessibilityNodeInfo): Boolean {
    var parent = node.parent
    while (parent != null) {
      val current = parent
      if (!LocalCallAutomationCommandStore.isClaimActive(token)) {
        current.recycleCompat()
        return false
      }
      var next: AccessibilityNodeInfo? = null
      var clicked = false
      try {
        clicked = current.isClickable && LocalCallAutomationCommandStore.runIfActive(token) {
          runCatching {
            current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
          }.getOrDefault(false)
        }
      } finally {
        if (!clicked && LocalCallAutomationCommandStore.isClaimActive(token)) next = current.parent
        current.recycleCompat()
      }
      if (clicked) return true
      parent = next
    }
    return false
  }

  private fun withRoot(operation: (AccessibilityNodeInfo) -> Boolean): Boolean {
    val root = rootInActiveWindow ?: return false
    return try {
      operation(root)
    } finally {
      root.recycleCompat()
    }
  }

  private fun finish(
    envelope: AutomationCommandEnvelope,
    succeeded: Boolean,
    message: String = speechPolicy.resultFor(envelope.command, succeeded),
    processNext: Boolean = true,
  ) {
    if (activeCommand?.token != envelope.token) return
    cancelGestureWatchdog(envelope.token)
    activeCommand = null
    val completedLease = LocalCallAutomationCommandStore.completeClaim(envelope.token)
    if (completedLease) {
      LocalCallAutomationResultBus.publish(
        AccessibilityCommandResult(envelope.token, succeeded, message),
      )
    }
    if (processNext) handler.post(processRunnable)
  }

  private fun isCurrentCommand(token: Long): Boolean =
    activeCommand?.token == token &&
      LocalCallAutomationCommandStore.isClaimActive(token)

  private fun scheduleGestureWatchdog(
    token: Long,
    completion: AccessibilityGestureCompletion,
  ) {
    cancelGestureWatchdog(token)
    val runnable = Runnable { completion.onTimedOut() }
    activeGestureWatchdog = GestureWatchdog(token, runnable)
    handler.postDelayed(runnable, GENERIC_GESTURE_CALLBACK_BUDGET_MILLIS)
  }

  private fun cancelGestureWatchdog(token: Long) {
    val watchdog = activeGestureWatchdog?.takeIf { it.token == token } ?: return
    activeGestureWatchdog = null
    handler.removeCallbacks(watchdog.runnable)
  }

  private fun abandonCancelledCommand(envelope: AutomationCommandEnvelope) {
    if (activeCommand?.token != envelope.token) return
    handler.removeCallbacks(processRunnable)
    cancelGestureWatchdog(envelope.token)
    activeFallbackCompletion = null
    activeWeChatTopSearchGesture = null
    activeWeChatCoordinateGestureToken = null
    clearWeChatKeyFrameRequest()
    clearWeChatFocusedInput()
    fallbackDeadlineMillis = 0L
    LocalCallAutomationCommandStore.executeIfActive(envelope.token) {
      when (val command = envelope.command) {
        is AccessibilityCommand.JdCheckIn -> command.machine.finish(envelope.token)
        is AccessibilityCommand.WeChatProductCapture -> command.machine.finish(envelope.token)
        else -> Unit
      }
    }
    activeCommand = null
    handler.post(processRunnable)
  }

  private fun clearWeChatFocusedInput() {
    activeWeChatFocusedInput?.recycleCompat()
    activeWeChatFocusedInput = null
  }

  private class AndroidTraversalNode(val node: AccessibilityNodeInfo) : TraversalNode {
    override val childCount: Int get() = node.childCount

    override fun childAt(index: Int): TraversalNode? = node.getChild(index)?.let(::AndroidTraversalNode)

    override fun recycle() = node.recycleCompat()
  }

  private data class JdIdClickAttempt(
    val attempted: Boolean,
    val method: JdAutomationClickMethod?,
  )

  private data class GestureWatchdog(val token: Long, val runnable: Runnable)

  private companion object {
    const val EVENT_COALESCE_MILLIS = 100L
    const val GENERIC_GESTURE_CALLBACK_BUDGET_MILLIS = 2_000L
    const val RETRY_MILLIS = 250L
    const val FALLBACK_TAP_MILLIS = 80L
    const val INPUT_SETTLE_MILLIS = 300L
    const val KEYBOARD_OPEN_SETTLE_MILLIS = 600L
    const val KEYBOARD_CANDIDATE_SETTLE_MILLIS = 300L
    const val KEYBOARD_KEY_INTERVAL_MILLIS = 120L
    const val KEY_FRAME_TIMEOUT_MILLIS = 5_000L
    const val PAGE_TRANSITION_MILLIS = 600L
    const val CONTENT_REFRESH_MILLIS = 1_000L
    const val WECHAT_QUERY = "李佳琦"
    const val WECHAT_CLIP_LABEL = "微信搜索"
    const val PAGE_LOG_TAG = "zunyu"

    @Suppress("DEPRECATION")
    fun AccessibilityNodeInfo.recycleCompat() {
      recycle()
    }
  }
}
