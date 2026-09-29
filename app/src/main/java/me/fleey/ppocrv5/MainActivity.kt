package me.fleey.ppocrv5

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.fleey.ppocrv5.call.CapabilityRecoveryAction
import me.fleey.ppocrv5.call.CaptureHistoryEntry
import me.fleey.ppocrv5.call.CallEvent
import me.fleey.ppocrv5.call.CallDestination
import me.fleey.ppocrv5.call.CallScreen
import me.fleey.ppocrv5.call.CaptureSettingsScreen
import me.fleey.ppocrv5.call.WeChatCapturePreference
import me.fleey.ppocrv5.call.CallSessionStore
import me.fleey.ppocrv5.call.LocalCallCaptureHistoryProvider
import me.fleey.ppocrv5.call.LocalCallOcrProvider
import me.fleey.ppocrv5.call.LocalCallCaptureVisibilityCoordinator
import me.fleey.ppocrv5.call.LocalCallCapability
import me.fleey.ppocrv5.call.LocalCallCapabilityIssue
import me.fleey.ppocrv5.call.LocalCallCapabilityRecoveryPolicy
import me.fleey.ppocrv5.call.LocalCallAccessibilityPermission
import me.fleey.ppocrv5.call.LocalCallPipEntryDecider
import me.fleey.ppocrv5.call.LocalCallPipParameterUpdatePolicy
import me.fleey.ppocrv5.call.LocalCallPipPolicy
import me.fleey.ppocrv5.call.LocalCallPipPreference
import me.fleey.ppocrv5.call.LocalCallPipPresentationPolicy
import me.fleey.ppocrv5.call.LocalCallPipPreview
import me.fleey.ppocrv5.call.LocalCallPipWindowStatePolicy
import me.fleey.ppocrv5.call.LocalCallPipUnavailablePreview
import me.fleey.ppocrv5.call.LocalCallPresentation
import me.fleey.ppocrv5.call.LocalCallOngoingPipPolicy
import me.fleey.ppocrv5.call.OngoingPipAction
import me.fleey.ppocrv5.call.LocalCallResultNavigationPolicy
import me.fleey.ppocrv5.call.LocalCallResultNavigationPreference
import me.fleey.ppocrv5.call.LocalCallResultNavigationState
import me.fleey.ppocrv5.call.LocalCallHistoryLoadCoordinator
import me.fleey.ppocrv5.call.LocalCallDepartureBaselinePolicy
import me.fleey.ppocrv5.call.LocalCallProjectionLifecyclePolicy
import me.fleey.ppocrv5.call.LocalCallPermissionRequestHistory
import me.fleey.ppocrv5.call.LocalCallResumeEvaluationGate
import me.fleey.ppocrv5.call.LocalCallRuntimeCapabilityReconciler
import me.fleey.ppocrv5.call.LocalCallRuntimeCapabilitySnapshot
import me.fleey.ppocrv5.call.LocalCallSpeakerInputPreference
import me.fleey.ppocrv5.call.LocalCallSpeakerInputActionPolicy
import me.fleey.ppocrv5.call.ResultScreen
import me.fleey.ppocrv5.call.rememberRecordGallerySaveAction
import me.fleey.ppocrv5.call.SpeakerInputAction
import me.fleey.ppocrv5.call.VoiceCallService
import me.fleey.ppocrv5.ui.theme.PPOCRv5AndroidTheme

class MainActivity : ComponentActivity(), LocalCallCaptureVisibilityCoordinator.PipHost {
  private val historyRepository by lazy { LocalCallCaptureHistoryProvider.get(applicationContext) }
  private val resultNavigationPolicy = LocalCallResultNavigationPolicy()
  private val resultNavigationPreference by lazy { LocalCallResultNavigationPreference(applicationContext) }
  private val pipPreference by lazy { LocalCallPipPreference(applicationContext) }
  private val pipEntryDecider by lazy { LocalCallPipEntryDecider(pipPreference) }
  private val speakerInputPreference by lazy { LocalCallSpeakerInputPreference(applicationContext) }
  private val permissionRequestHistory by lazy {
    LocalCallPermissionRequestHistory(applicationContext)
  }
  private lateinit var resultNavigationState: LocalCallResultNavigationState
  private var returnWithNewCaptureEvent by mutableIntStateOf(0)
  private var inPictureInPicture by mutableStateOf(false)
  private var pipHiddenForCapture = false
  private var accessibilityEnabled by mutableStateOf(false)
  private var pipCaptureVisibilityGeneration = 0L
  private var windowAlphaBeforeCapture = 1f
  private val pipWindowStatePolicy = LocalCallPipWindowStatePolicy()
  private var historySnapshot by mutableStateOf(HistoryUiSnapshot.EMPTY)
  private var deletingHistoryEntryId by mutableStateOf<String?>(null)
  private var historyDeleteError by mutableStateOf<String?>(null)
  private val resumeEvaluationGate = LocalCallResumeEvaluationGate()
  private val historyLoadCoordinator = LocalCallHistoryLoadCoordinator(resumeEvaluationGate)
  private var pendingProjectionCallEpoch: Long? = null
  private var pipFinishRequested = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    LocalCallOcrProvider.preload(applicationContext)
    pendingProjectionCallEpoch = savedInstanceState
      ?.takeIf { it.containsKey(STATE_PENDING_PROJECTION_CALL_EPOCH) }
      ?.getLong(STATE_PENDING_PROJECTION_CALL_EPOCH)
    resultNavigationState = resultNavigationPreference.load()
    accessibilityEnabled = LocalCallAccessibilityPermission.isEnabled(this)
    inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
    if (inPictureInPicture) setWindowSecure(pipWindowStatePolicy.onPipEnter(isWindowSecure()))
    LocalCallCaptureVisibilityCoordinator.shared.register(this)
    enableEdgeToEdge()
    setContent {
      val state by CallSessionStore.state.collectAsState()
      val saveRecordImage = rememberRecordGallerySaveAction()
      val returnEvent = returnWithNewCaptureEvent
      var destination by rememberSaveable { mutableStateOf(CallDestination.CALL) }
      var showingSettings by rememberSaveable { mutableStateOf(false) }
      var pipEnabled by rememberSaveable { mutableStateOf(pipPreference.isEnabled()) }
      var speakerInputEnabled by remember { mutableStateOf(speakerInputPreference.isEnabled()) }
      var historyVersion by remember { mutableIntStateOf(0) }
      val currentCapture = state.currentCaptureId?.let(historySnapshot.entriesById::get)
      val currentCaptureFile = state.currentCaptureId?.let(historySnapshot.imageFilesById::get)
      val openResults = {
        historyVersion += 1
        destination = CallDestination.RESULTS
      }
      val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
      ) { granted ->
        if (granted) permissionRequestHistory.markGranted(LocalCallCapability.MICROPHONE)
        CallSessionStore.dispatch(
          LocalCallRuntimeCapabilityReconciler.microphoneRequestResult(
            granted = granted,
            wasRequestedBeforeMostRecentRequest = permissionRequestHistory
              .wasRequestedBeforeMostRecentRequest(LocalCallCapability.MICROPHONE),
            showRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
          ),
        )
        if (granted) startCallService()
      }
      val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
      ) { granted ->
        if (granted) permissionRequestHistory.markGranted(LocalCallCapability.NOTIFICATIONS)
        CallSessionStore.dispatch(
          LocalCallRuntimeCapabilityReconciler.notificationRequestResult(
            granted = granted,
            wasRequestedBeforeMostRecentRequest = permissionRequestHistory
              .wasRequestedBeforeMostRecentRequest(LocalCallCapability.NOTIFICATIONS),
            showRationale = Build.VERSION.SDK_INT >= 33 &&
              shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
          ),
        )
      }
      val requestMicrophonePermission = {
        permissionRequestHistory.markRequested(LocalCallCapability.MICROPHONE)
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
      }
      val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= 33) {
          permissionRequestHistory.markRequested(LocalCallCapability.NOTIFICATIONS)
          notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
      val screenCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
      ) { result ->
        val requestedCallEpoch = pendingProjectionCallEpoch
        pendingProjectionCallEpoch = null
        val currentState = CallSessionStore.state.value
        if (LocalCallProjectionLifecyclePolicy.acceptAuthorizationResult(
            requestedCallEpoch = requestedCallEpoch,
            currentCallEpoch = currentState.callEpoch,
            callActive = currentState.callActive,
          )
        ) {
          if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CallSessionStore.dispatch(CallEvent.CapabilityRestored(LocalCallCapability.SCREEN_CAPTURE))
            sendServiceAction(VoiceCallService.ACTION_START_SCREEN_SHARE) {
              putExtra(VoiceCallService.EXTRA_PROJECTION_RESULT_CODE, result.resultCode)
              putExtra(VoiceCallService.EXTRA_PROJECTION_DATA, result.data)
            }
          } else {
            CallSessionStore.dispatch(
              CallEvent.CapabilityIssueRaised(LocalCallCapabilityIssue.ProjectionCancelled),
            )
          }
        }
      }
      val requestScreenCapture = {
        val currentState = CallSessionStore.state.value
        if (currentState.callActive && currentState.callEpoch > 0L) {
          pendingProjectionCallEpoch = currentState.callEpoch
          val manager = getSystemService(MediaProjectionManager::class.java)
          screenCapture.launch(manager.createScreenCaptureIntent())
        }
      }

      val ongoingPipAction = LocalCallOngoingPipPolicy.action(
        isInPictureInPicture = inPictureInPicture,
        callActive = state.callActive,
        screenSharing = state.screenSharing,
      )

      PPOCRv5AndroidTheme {
        when (LocalCallPipPresentationPolicy.route(
          isInPictureInPicture = inPictureInPicture,
          showingResults = destination == CallDestination.RESULTS,
        )) {
          LocalCallPresentation.PIP -> if (ongoingPipAction == OngoingPipAction.KEEP_SHOWING) {
            LocalCallPipPreview(
              entry = currentCapture,
              imageFile = currentCaptureFile,
              currentOcr = state.screenSummary,
            )
          } else {
            LocalCallPipUnavailablePreview()
          }

          LocalCallPresentation.CALL -> {
            if (showingSettings) {
              val preference = remember { WeChatCapturePreference(applicationContext) }
              CaptureSettingsScreen(preference.count(), preference::save, { showingSettings = false })
            } else {
            CallScreen(
              state = state,
              currentCapture = currentCapture,
              currentCaptureFile = currentCaptureFile,
              inPictureInPictureMode = false,
              pipEnabled = pipEnabled,
              speakerInputEnabled = speakerInputEnabled,
              accessibilityEnabled = accessibilityEnabled,
              onToggleCall = {
                if (state.callActive) {
                  sendServiceAction(VoiceCallService.ACTION_STOP_CALL)
                } else if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                ) {
                  CallSessionStore.dispatch(
                    CallEvent.CapabilityRestored(LocalCallCapability.MICROPHONE),
                  )
                  startCallService()
                } else {
                  requestMicrophonePermission()
                }
              },
              onStartScreenShare = requestScreenCapture,
              onStopScreenShare = { sendServiceAction(VoiceCallService.ACTION_STOP_SCREEN_SHARE) },
              onAsk = { question ->
                sendServiceAction(VoiceCallService.ACTION_MANUAL_QUERY) {
                  putExtra(VoiceCallService.EXTRA_QUERY, question)
                }
              },
              onPipEnabledChanged = { enabled ->
                pipEnabled = enabled
                pipPreference.setEnabled(enabled)
              },
              onSpeakerInputEnabledChanged = { enabled ->
                speakerInputEnabled = enabled
                when (LocalCallSpeakerInputActionPolicy.action(state.callActive)) {
                  SpeakerInputAction.RESTART_ACTIVE_RECORDER -> {
                    sendServiceAction(VoiceCallService.ACTION_SET_SPEAKER_INPUT) {
                      putExtra(VoiceCallService.EXTRA_SPEAKER_INPUT_ENABLED, enabled)
                    }
                  }
                  SpeakerInputAction.PERSIST_LOCALLY -> speakerInputPreference.setEnabled(enabled)
                }
              },
              onOpenAccessibilitySettings = {
                LocalCallAccessibilityPermission.openSettings(this)
              },
              onStartWeChatCapture = {
                sendServiceAction(VoiceCallService.ACTION_START_WECHAT_CAPTURE)
              },
              onCapabilityIssueAction = { issue ->
                when (LocalCallCapabilityRecoveryPolicy.actionFor(issue)) {
                  CapabilityRecoveryAction.RETRY_MICROPHONE ->
                    requestMicrophonePermission()
                  CapabilityRecoveryAction.RETRY_NOTIFICATIONS -> requestNotificationPermission()
                  CapabilityRecoveryAction.RETRY_SCREEN_SHARE -> requestScreenCapture()
                  CapabilityRecoveryAction.OPEN_APPLICATION_SETTINGS -> openApplicationSettings()
                  CapabilityRecoveryAction.OPEN_NOTIFICATION_SETTINGS -> openNotificationSettings()
                }
              },
              onOpenResults = openResults,
              onOpenSettings = { showingSettings = true },
            )
            }
          }

          LocalCallPresentation.RESULTS -> ResultScreen(
            entries = historySnapshot.entries,
            imageFileFor = { entry -> historySnapshot.imageFilesById[entry.id] },
            onBack = { destination = CallDestination.CALL },
            onDelete = ::deleteHistoryEntry,
            deletingEntryId = deletingHistoryEntryId,
            deleteError = historyDeleteError,
            onSaveImage = saveRecordImage,
          )
        }
      }

      LaunchedEffect(returnEvent) {
        if (returnEvent > 0) openResults()
      }

      val pipContentMode = LocalCallPipPresentationPolicy.contentMode(
        currentCaptureId = state.currentCaptureId,
        hasCurrentCaptureFile = currentCaptureFile != null,
      )
      LaunchedEffect(inPictureInPicture, state.currentCaptureId, pipContentMode) {
        if (inPictureInPicture) updatePictureInPictureParams()
      }

      LaunchedEffect(state.currentCaptureId, historyVersion) {
        requestHistorySnapshot()
      }

      LaunchedEffect(ongoingPipAction) {
        when (ongoingPipAction) {
          OngoingPipAction.FINISH_ACTIVITY -> if (!pipFinishRequested) {
            pipFinishRequested = true
            finish()
          }
          OngoingPipAction.NONE -> pipFinishRequested = false
          OngoingPipAction.KEEP_SHOWING -> Unit
        }
      }

      LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
          !getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
          !permissionRequestHistory.wasRequested(LocalCallCapability.NOTIFICATIONS)
        ) {
          requestNotificationPermission()
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    accessibilityEnabled = LocalCallAccessibilityPermission.isEnabled(this)
    val microphoneGranted = ContextCompat.checkSelfPermission(
      this,
      Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val notificationsEnabled = getSystemService(NotificationManager::class.java)
      .areNotificationsEnabled()
    if (microphoneGranted) {
      permissionRequestHistory.markGranted(LocalCallCapability.MICROPHONE)
    }
    if (notificationsEnabled) {
      permissionRequestHistory.markGranted(LocalCallCapability.NOTIFICATIONS)
    }
    LocalCallRuntimeCapabilityReconciler.resumeEvents(
      LocalCallRuntimeCapabilitySnapshot(
        sdkInt = Build.VERSION.SDK_INT,
        microphoneGranted = microphoneGranted,
        microphoneWasRequested = permissionRequestHistory
          .wasRequested(LocalCallCapability.MICROPHONE),
        showMicrophoneRationale = shouldShowRequestPermissionRationale(
          Manifest.permission.RECORD_AUDIO,
        ),
        notificationsEnabled = notificationsEnabled,
        notificationsWereRequested = permissionRequestHistory
          .wasRequested(LocalCallCapability.NOTIFICATIONS),
        showNotificationRationale = Build.VERSION.SDK_INT >= 33 &&
          shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        microphoneWasGrantedBefore = permissionRequestHistory
          .wasGranted(LocalCallCapability.MICROPHONE),
        microphoneRequestCount = permissionRequestHistory
          .requestCount(LocalCallCapability.MICROPHONE),
        notificationsWereGrantedBefore = permissionRequestHistory
          .wasGranted(LocalCallCapability.NOTIFICATIONS),
        notificationRequestCount = permissionRequestHistory
          .requestCount(LocalCallCapability.NOTIFICATIONS),
      ),
    ).forEach(CallSessionStore::dispatch)
    requestHistorySnapshot(resumeEpoch = resumeEvaluationGate.beginResume())
  }

  override fun onPause() {
    resumeEvaluationGate.invalidateForLeaveOrPause()
    super.onPause()
  }

  override fun onUserLeaveHint() {
    resumeEvaluationGate.invalidateForLeaveOrPause()
    val departureCutoff = System.currentTimeMillis()
    resultNavigationState = resultNavigationState.markUserLeft(
      historySnapshot.entriesById.keys + listOfNotNull(CallSessionStore.state.value.currentCaptureId),
      departureCutoffMillis = departureCutoff,
    )
    resultNavigationPreference.save(resultNavigationState)
    requestHistorySnapshot(departureCutoffMillis = departureCutoff)
    super.onUserLeaveHint()
    enterPictureInPictureIfEligible()
  }

  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    inPictureInPicture = isInPictureInPictureMode
    if (isInPictureInPictureMode) {
      setWindowSecure(pipWindowStatePolicy.onPipEnter(isWindowSecure()))
      LocalCallCaptureVisibilityCoordinator.shared.onPictureInPictureEntered()
    } else {
      restoreWindowSecureAfterCaptureIfNeeded()
      restoreWindowSecureAfterPip()
    }
  }

  private fun enterPictureInPictureIfEligible() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
      !packageManager.hasSystemFeature(PIP_SYSTEM_FEATURE)
    ) return
    val state = CallSessionStore.state.value
    if (!pipEntryDecider.shouldEnter(state.callActive, state.screenSharing)) return

    setWindowSecure(pipWindowStatePolicy.onPipEnter(isWindowSecure()))
    val entered = runCatching { enterPictureInPictureMode(createPictureInPictureParams()) }.getOrDefault(false)
    if (!entered) restoreWindowSecureAfterPip()
  }

  private fun createPictureInPictureParams(): PictureInPictureParams {
    val frame = currentPipFrameDimensions()
    val mode = LocalCallPipPolicy.contentMode(frame != null)
    val ratio = LocalCallPipPolicy.aspectRatio(mode, frame?.width, frame?.height)
    return PictureInPictureParams.Builder()
      .setAspectRatio(ratio)
      .apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setSeamlessResizeEnabled(false)
      }
      .build()
  }

  private fun updatePictureInPictureParams() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode) return
    val applied = runCatching { setPictureInPictureParams(createPictureInPictureParams()) }.isSuccess
    if (LocalCallPipParameterUpdatePolicy.shouldNotifyAspectRatioUpdated(applied)) {
      LocalCallCaptureVisibilityCoordinator.shared.onPictureInPictureAspectRatioUpdated()
    }
  }

  private fun currentPipFrameDimensions(): PipFrameDimensions? {
    val captureId = CallSessionStore.state.value.currentCaptureId ?: return null
    return historySnapshot.dimensionsById[captureId]
  }

  override fun hidePictureInPictureForCapture(onHidden: () -> Unit) {
    runOnUiThread {
      if (!isInPictureInPictureMode) {
        onHidden()
        return@runOnUiThread
      }
      pipCaptureVisibilityGeneration += 1L
      val generation = pipCaptureVisibilityGeneration
      pipHiddenForCapture = true
      windowAlphaBeforeCapture = window.attributes.alpha
      setWindowSecure(pipWindowStatePolicy.onCaptureHide(isWindowSecure()))
      updateWindowAlpha(0f)
      window.decorView.postOnAnimation {
        window.decorView.postOnAnimation {
          if (pipHiddenForCapture && pipCaptureVisibilityGeneration == generation) onHidden()
        }
      }
    }
  }

  override fun restorePictureInPictureAfterCapture(onRestored: () -> Unit) {
    runOnUiThread {
      try {
        pipCaptureVisibilityGeneration += 1L
        if (!pipHiddenForCapture) return@runOnUiThread
        pipHiddenForCapture = false
        updateWindowAlpha(windowAlphaBeforeCapture)
        restoreWindowSecureAfterCaptureIfNeeded()
      } finally {
        onRestored()
      }
    }
  }

  private fun updateWindowAlpha(alpha: Float) {
    window.attributes = window.attributes.apply { this.alpha = alpha }
  }

  private fun isWindowSecure(): Boolean =
    window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

  private fun setWindowSecure(enabled: Boolean) {
    if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }

  private fun restoreWindowSecureAfterCaptureIfNeeded() {
    pipWindowStatePolicy.onCaptureRestore()?.let(::setWindowSecure)
  }

  private fun restoreWindowSecureAfterPip() {
    pipWindowStatePolicy.onPipExit()?.let(::setWindowSecure)
  }

  private fun startCallService() {
    ContextCompat.startForegroundService(
      this,
      Intent(this, VoiceCallService::class.java).setAction(VoiceCallService.ACTION_START_CALL),
    )
  }

  private fun sendServiceAction(action: String, extras: Intent.() -> Unit = {}) {
    startService(Intent(this, VoiceCallService::class.java).setAction(action).apply(extras))
  }

  private fun deleteHistoryEntry(entry: CaptureHistoryEntry) {
    if (deletingHistoryEntryId != null) return
    deletingHistoryEntryId = entry.id
    historyDeleteError = null
    lifecycleScope.launch {
      try {
        val result = withContext(Dispatchers.IO) {
          runCatching { historyRepository.delete(entry) }
        }
        if (result.isFailure) historyDeleteError = "删除失败，请重试。"
        requestHistorySnapshot()
      } finally {
        deletingHistoryEntryId = null
      }
    }
  }

  private fun requestHistorySnapshot(
    resumeEpoch: Long? = null,
    departureCutoffMillis: Long? = null,
  ) {
    val request = historyLoadCoordinator.beginLoad(resumeEpoch)
    lifecycleScope.launch {
      val snapshot = withContext(Dispatchers.IO) { loadHistorySnapshot() }
      val completion = historyLoadCoordinator.claimLatestCompletion(request) ?: return@launch
      historySnapshot = snapshot
      if (departureCutoffMillis != null &&
        resultNavigationState.departureCutoffMillis == departureCutoffMillis &&
        resultNavigationState.awaitingExternalReturn
      ) {
        refineDepartureBaseline(snapshot, departureCutoffMillis)
      }
      if (completion.evaluateResume) {
        resultNavigationState.departureCutoffMillis?.let { cutoff ->
          if (resultNavigationState.awaitingExternalReturn) refineDepartureBaseline(snapshot, cutoff)
        }
        val returned = resultNavigationState.onAppResumed(snapshot.entriesById.keys)
        resultNavigationState = returned.nextState
        resultNavigationPreference.save(resultNavigationState)
        if (resultNavigationPolicy.shouldOpenResults(
            returnedToApp = returned.returnedFromUserDeparture,
            unseenExternalCaptures = returned.unseenExternalCaptures,
          )
        ) {
          returnWithNewCaptureEvent += 1
        }
      }
    }
  }

  private fun refineDepartureBaseline(snapshot: HistoryUiSnapshot, cutoffMillis: Long) {
    resultNavigationState = resultNavigationState.markUserLeft(
      LocalCallDepartureBaselinePolicy.captureIds(
        entries = snapshot.entries,
        departureCutoffMillis = cutoffMillis,
        alreadyKnownIds = resultNavigationState.departureCaptureIds,
      ),
      departureCutoffMillis = cutoffMillis,
    )
    resultNavigationPreference.save(resultNavigationState)
  }

  private fun loadHistorySnapshot(): HistoryUiSnapshot {
    val entries = historyRepository.load()
    val imageFiles = entries.mapNotNull { entry ->
      historyRepository.imageFile(entry)?.let { entry.id to it }
    }.toMap()
    val dimensions = imageFiles.mapNotNull { (id, frame) ->
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(frame.absolutePath, bounds)
      PipFrameDimensions(bounds.outWidth, bounds.outHeight)
        .takeIf { it.width > 0 && it.height > 0 }
        ?.let { id to it }
    }.toMap()
    return HistoryUiSnapshot(
      entries = entries,
      entriesById = entries.associateBy(CaptureHistoryEntry::id),
      imageFilesById = imageFiles,
      dimensionsById = dimensions,
    )
  }

  private fun openApplicationSettings() {
    startActivity(
      Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
    )
  }

  private fun openNotificationSettings() {
    startActivity(
      Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
      },
    )
  }

  override fun onDestroy() {
    pipCaptureVisibilityGeneration += 1L
    if (pipHiddenForCapture) {
      pipHiddenForCapture = false
      updateWindowAlpha(windowAlphaBeforeCapture)
    }
    restoreWindowSecureAfterCaptureIfNeeded()
    restoreWindowSecureAfterPip()
    LocalCallCaptureVisibilityCoordinator.shared.unregister(this)
    super.onDestroy()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    pendingProjectionCallEpoch?.let { outState.putLong(STATE_PENDING_PROJECTION_CALL_EPOCH, it) }
    super.onSaveInstanceState(outState)
  }

  private data class PipFrameDimensions(val width: Int, val height: Int)

  private data class HistoryUiSnapshot(
    val entries: List<CaptureHistoryEntry>,
    val entriesById: Map<String, CaptureHistoryEntry>,
    val imageFilesById: Map<String, java.io.File>,
    val dimensionsById: Map<String, PipFrameDimensions>,
  ) {
    companion object {
      val EMPTY = HistoryUiSnapshot(emptyList(), emptyMap(), emptyMap(), emptyMap())
    }
  }

  private companion object {
    const val PIP_SYSTEM_FEATURE = "android.software.picture_in_picture"
    const val STATE_PENDING_PROJECTION_CALL_EPOCH = "pending_projection_call_epoch"
  }
}
