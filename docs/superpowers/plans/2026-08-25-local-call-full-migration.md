# Local AI Call Full Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the complete local AI call experience from `tmallandroid_messagebox` into the standalone `DoubaoLocalCallDemo` while retaining the target project's Compose, sherpa-onnx, and PP-OCRv5 foundations.

**Architecture:** Adapt pure Kotlin policies and Android service logic into `me.fleey.ppocrv5.call`, then expose them through the existing Compose app. Keep Alibaba-specific product recognition behind a standalone gateway and implement UI, persistence, picture-in-picture, and accessibility with public Android APIs only.

**Tech Stack:** Kotlin 2.3, Jetpack Compose Material 3, Android foreground services, MediaProjection, AccessibilityService, SharedPreferences, sherpa-onnx 1.13.4, PP-OCRv5/LiteRT JNI, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-08-25-local-call-full-migration-design.md`

## Global Constraints

- Preserve application ID and namespace `me.fleey.ppocrv5`.
- Preserve minimum SDK 26, target SDK 35, compile SDK 36.1, Java 21, arm64-v8a, and the existing NDK/CMake OCR pipeline.
- Do not introduce `TMActivity`, Tmall custom views, MTop, fastjson, Alibaba Maven dependencies, or network permission.
- Product recognition must use an injectable gateway whose default implementation performs no network request.
- Speaker playback mode means microphone capture with acoustic echo cancellation and noise suppression disabled; it does not mean digital playback capture.
- MediaProjection and accessibility always require explicit system authorization.
- The target directory is not currently a Git repository. Commit steps are recorded as checkpoints and must not initialize Git without user authorization.

---

### Task 1: Port turn coordination, interruption, and audio input policies

**Files:**
- Create: `app/src/main/java/me/fleey/ppocrv5/call/SpeechUtterance.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallTurnPolicy.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallTurnCoordinator.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallPartialTurnHandler.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAudioInputPolicy.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallSpeakerInputPreference.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallSpeechInputController.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/SherpaSpeechEngine.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallTurnPolicyTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallTurnCoordinatorTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallAudioInputPolicyTest.kt`

**Interfaces:**
- Produces: `LocalCallTurnPolicy.match(String): IntentMatch?` and `shouldInterrupt(String, String): Boolean`.
- Produces: `LocalCallTurnCoordinator.begin(...)`, `completeOperation(...)`, `completeSpeech(...)`, and `interrupt()`.
- Produces: `LocalCallAudioInputPolicy.resolve(LocalCallAudioInputMode, Boolean): LocalCallAudioInputConfiguration`.

- [ ] **Step 1: Port the source tests with package rewritten to `me.fleey.ppocrv5.call`**

Include explicit assertions such as:

```kotlin
assertEquals(LocalCallAction.READ_SCREEN, policy.match("帮我看一下当前页面")?.action)
assertEquals(LocalCallAction.RECOGNIZE_PRODUCT, policy.match("帮我看一下这个商品")?.action)
assertEquals(LocalCallAction.JD_CHECK_IN, policy.match("去京东打卡")?.action)
assertFalse(policy.shouldInterrupt("我还有一个问题", "正在播报"))
assertTrue(policy.shouldInterrupt("先停一下", "正在播报"))
```

- [ ] **Step 2: Run the focused tests and verify missing symbols fail**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.LocalCallTurn*' --tests 'me.fleey.ppocrv5.call.LocalCallAudioInputPolicyTest'`

Expected: compilation fails because the new policy and coordinator types do not yet exist.

- [ ] **Step 3: Adapt the pure Kotlin implementations from the source branch**

Keep the exact intent priority `JD_CHECK_IN`, `RECOGNIZE_PRODUCT`, then `READ_SCREEN`, and resolve speaker mode as:

```kotlin
LocalCallAudioInputConfiguration(
  preferVoiceCommunicationSource = false,
  enableEchoCancellation = false,
  enableNoiseSuppression = false,
)
```

- [ ] **Step 4: Update `SherpaSpeechEngine` to accept `LocalCallAudioInputMode` and configure `AudioRecord` effects**

Create `AcousticEchoCanceler` and `NoiseSuppressor` only when enabled by the resolved configuration, and release both in `stop()`/`close()`.

- [ ] **Step 5: Run the focused tests and the existing call tests**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.*'`

Expected: all tests pass.

- [ ] **Step 6: Record checkpoint**

Record the new policy files and passing test command in the plan checklist; do not initialize Git.

### Task 2: Add robust capture, foreground-source metadata, and history persistence

**Files:**
- Create: `app/src/main/java/me/fleey/ppocrv5/call/ScreenCaptureRetryPolicy.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/FocusedScreenContentStore.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallCaptureVisibilityCoordinator.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallCaptureHistoryRepository.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallCaptureHistoryProvider.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/ScreenModels.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/ScreenCaptureEngine.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/ScreenCaptureRetryPolicyTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/FocusedScreenContentStoreTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallCaptureHistoryRepositoryTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallCaptureVisibilityCoordinatorTest.kt`

**Interfaces:**
- Produces: `ScreenCaptureRetryPolicy(maxAttempts = 3, retryDelayMillis).capture(block): Bitmap?`.
- Produces: `FocusedScreenContentStore.update(packageName, activityName, timestamp)` and `snapshot()`.
- Produces: `LocalCallCaptureHistoryRepository.save(bitmap, ocrText, sourcePackage, capturedAtMillis): CaptureHistoryEntry` and `load(): List<CaptureHistoryEntry>`.

- [ ] **Step 1: Port policy/repository tests and add a three-attempt assertion**

```kotlin
var attempts = 0
val result = policy.capture {
  attempts++
  if (attempts == 3) "frame" else null
}
assertEquals("frame", result)
assertEquals(3, attempts)
```

- [ ] **Step 2: Run focused tests and verify they fail before implementation**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.ScreenCaptureRetryPolicyTest' --tests 'me.fleey.ppocrv5.call.*History*'`

- [ ] **Step 3: Implement retry and visibility policies as platform-independent classes**

The visibility coordinator must expose the earliest safe capture time and use a larger delay after PiP entry or ratio update.

- [ ] **Step 4: Implement bounded private-cache persistence**

Persist entries under `cacheDir/local_call_history`, JPEG-compress frames, atomically replace metadata, sort newest first, and delete image files when entries are evicted.

- [ ] **Step 5: Extend `ScreenSnapshot` with frame, source package, and capture timestamp**

Keep OCR text blocks immutable and avoid retaining obsolete full-size Bitmaps in the session store.

- [ ] **Step 6: Run repository and capture tests**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.ScreenCaptureRetryPolicyTest' --tests 'me.fleey.ppocrv5.call.FocusedScreenContentStoreTest' --tests 'me.fleey.ppocrv5.call.LocalCallCaptureHistory*' --tests 'me.fleey.ppocrv5.call.LocalCallCaptureVisibilityCoordinatorTest'`

Expected: all focused tests pass.

- [ ] **Step 7: Record checkpoint**

Record the cache schema, maximum entry count, and passing test command.

### Task 3: Integrate coordinated speech and screen operations into the foreground service

**Files:**
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/VoiceCallService.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/AndroidSpeechOutput.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/CallStateMachine.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/CallSessionStore.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/CallStateMachineTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallPartialTurnHandlerTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/SpeechUtteranceTest.kt`

**Interfaces:**
- Consumes: turn policies, capture retry, OCR analyzer, history repository, and speaker preference from Tasks 1-2.
- Produces: service actions `ACTION_START_CALL`, `ACTION_END_CALL`, `ACTION_START_SCREEN_SHARE`, `ACTION_STOP_SCREEN_SHARE`, and `ACTION_SET_SPEAKER_INPUT`.

- [ ] **Step 1: Add tests for stale utterance callbacks and partial speech during TTS**

Assert that a non-stop partial is ignored while speaking and that an old query token cannot publish a result.

- [ ] **Step 2: Run service policy tests and verify the new cases fail**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.CallStateMachineTest' --tests 'me.fleey.ppocrv5.call.LocalCallPartialTurnHandlerTest' --tests 'me.fleey.ppocrv5.call.SpeechUtteranceTest'`

- [ ] **Step 3: Replace direct transcript-to-reply handling with tokenized turn coordination**

On an intent match, call `begin`, speak the acknowledgement immediately, execute the operation on the worker executor, and publish the result only through `completeOperation`/`completeSpeech`.

- [ ] **Step 4: Integrate safe screen capture and history saving**

Wait for the visibility coordinator, retry capture three times, OCR once, save only successful non-empty output, and dispatch one failure response after exhaustion.

- [ ] **Step 5: Apply speaker preference changes without ending the call**

Stop the current `AudioRecord`, recreate it using the selected mode, and resume listening while preserving MediaProjection and the foreground notification.

- [ ] **Step 6: Run all unit tests and build the service**

Run: `./gradlew testDebugUnitTest assembleDebug`

Expected: tests pass and the Debug APK is produced.

- [ ] **Step 7: Record checkpoint**

Record the service action contract and build result.

### Task 4: Build Compose navigation, dark call UI, and the history feed

**Files:**
- Modify: `app/src/main/java/me/fleey/ppocrv5/MainActivity.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/CallScreen.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/ResultScreen.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallResultNavigationPolicy.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/ui/theme/Color.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/ui/theme/Theme.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallResultNavigationPolicyTest.kt`

**Interfaces:**
- Consumes: `CallSessionStore.state`, history provider, PiP/speaker preferences, and service actions.
- Produces: `CallDestination.CALL` and `CallDestination.RESULTS` navigation states.

- [ ] **Step 1: Add result-navigation policy tests**

```kotlin
assertTrue(policy.shouldOpenResults(returnedToApp = true, unseenExternalCaptures = 1))
assertFalse(policy.shouldOpenResults(returnedToApp = true, unseenExternalCaptures = 0))
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.LocalCallResultNavigationPolicyTest'`

- [ ] **Step 3: Implement navigation and result Feed**

Use Compose state navigation without adding Navigation Compose. Render each entry with a timestamp, source package, OCR text, and an aspect-ratio-preserving image loaded from the private cache.

- [ ] **Step 4: Expand the call screen controls**

Add dark styling, current frame/OCR preview, source package, PiP switch, speaker input switch, accessibility settings button, and result-page button. The Activity must remain action-bar-free.

- [ ] **Step 5: Run Compose compilation and unit tests**

Run: `./gradlew testDebugUnitTest assembleDebug`

Expected: the app compiles and navigation policy tests pass.

- [ ] **Step 6: Record checkpoint**

Record the two destinations and screenshot the two Compose states during device verification.

### Task 5: Implement adaptive picture-in-picture lifecycle

**Files:**
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallPipPolicy.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallPipPreference.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallPipEntryDecider.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/MainActivity.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/CallScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallPipPolicyTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallPipPreferenceTest.kt`

**Interfaces:**
- Produces: `PipContentMode.TEXT_ONLY` and `PipContentMode.FRAME_WITH_TEXT`.
- Produces: `LocalCallPipPolicy.aspectRatio(mode, frameWidth, frameHeight): Rational`.

- [ ] **Step 1: Add policy tests for compact initial mode and portrait frame mode**

Assert that text-only bounds are materially smaller than frame mode and that the frame ratio derives from the captured phone dimensions.

- [ ] **Step 2: Run PiP tests and verify failure before implementation**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.LocalCallPip*'`

- [ ] **Step 3: Implement PiP preference, entry decision, and ratio policy**

Only enter on user leave while the call is active and the switch is enabled. Update `PictureInPictureParams` after the first frame changes the mode.

- [ ] **Step 4: Render only preview image and OCR in PiP**

Hide all controls and headings in PiP. Use a compact centered text surface before a frame exists and `ContentScale.Fit` for the frame mode.

- [ ] **Step 5: Add manifest PiP capability and lifecycle handling**

Set `android:supportsPictureInPicture="true"` and the required configuration changes on `MainActivity`; update UI from `onPictureInPictureModeChanged`.

- [ ] **Step 6: Run PiP tests and assemble**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.LocalCallPip*' assembleDebug`

- [ ] **Step 7: Record checkpoint**

Record compact and frame ratios plus the successful build.

### Task 6: Add generic accessibility commands and foreground-window awareness

**Files:**
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAccessibilityService.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAccessibilityPermission.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/DepthFirstTraversal.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/AccessibilityCommandParser.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAutomationCommandStore.kt`
- Create: `app/src/main/res/xml/local_call_accessibility_service.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/DepthFirstTraversalTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/AccessibilityCommandParserTest.kt`

**Interfaces:**
- Produces: parsed commands `Swipe(direction)`, `ClickListItem(index)`, and `ClickText(text)`.
- Produces: foreground package updates to `FocusedScreenContentStore`.

- [ ] **Step 1: Add parser and traversal tests**

Cover “下一页”, “向上滑”, “向左滑”, “第一个”, “第3个”, and “点下关注按钮”, including zero-result traversal.

- [ ] **Step 2: Run accessibility unit tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.DepthFirstTraversalTest' --tests 'me.fleey.ppocrv5.call.AccessibilityCommandParserTest'`

- [ ] **Step 3: Implement parser, DFS abstraction, gesture execution, and text/list clicks**

Use normalized screen coordinates for gestures and Android `AccessibilityNodeInfo` only inside the service; keep traversal policy testable with a platform-neutral node adapter.

- [ ] **Step 4: Register an event-observing accessibility service**

Listen for window-state and window-content changes, retrieve window content, report view IDs, and do not restrict `packageNames` so generic commands can operate in user-selected external apps.

- [ ] **Step 5: Wire permission status and settings entry into the call screen**

Open `Settings.ACTION_ACCESSIBILITY_SETTINGS` and refresh status in `onResume`.

- [ ] **Step 6: Run tests and assemble**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.*Accessibility*' --tests 'me.fleey.ppocrv5.call.DepthFirstTraversalTest' assembleDebug`

- [ ] **Step 7: Record checkpoint**

Record manifest metadata and the generic command matrix.

### Task 7: Port the JD check-in automation state machine

**Files:**
- Create: `app/src/main/java/me/fleey/ppocrv5/call/JdAutomationStateMachine.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/JdAutomationTargets.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/JdFallbackTapPolicy.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/JdAccessibilityNodePolicy.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/JdAutomationClickFeedback.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallJdAutomationController.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAutomationResultBus.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAccessibilityService.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/VoiceCallService.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/JdAutomationStateMachineTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/JdFallbackTapPolicyTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/JdAccessibilityNodePolicyTest.kt`

**Interfaces:**
- Consumes: `LocalCallAction.JD_CHECK_IN`, accessibility root/window events, and result bus.
- Produces: step results identifying `VIEW_ID`, `ACCESSIBILITY_NODE`, or `FALLBACK_COORDINATE`.

- [ ] **Step 1: Port JD policy/state tests and lock fallback coordinates**

```kotlin
assertEquals(PointF(916f, 475f), policy.pointFor(JdTarget.MORE))
assertEquals(PointF(500f, 574f), policy.pointFor(JdTarget.CLAIM_ALL))
```

- [ ] **Step 2: Run JD tests and verify failure before implementation**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.Jd*'`

- [ ] **Step 3: Port the pure state machine and click-selection policies**

Use the sequence `OPEN_JD`, `OPEN_MY`, `CLICK_MORE`, `CLICK_CLAIM_ALL`, `COMPLETE`, and ensure timeout transitions end the operation.

- [ ] **Step 4: Integrate controller and accessibility execution**

Launch `com.jingdong.app.mall`, wait for stable window events, search current targets using DFS, click via ID/node before dispatching the configured coordinate gesture.

- [ ] **Step 5: Add per-step Toast and result-bus messages**

Messages must distinguish ID, node/ancestor, and coordinate fallback; absence of a view ID must be described as an accessibility-node click rather than an ID click.

- [ ] **Step 6: Run JD tests and assemble**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.Jd*' assembleDebug`

- [ ] **Step 7: Record checkpoint**

Record state sequence, timeout values, coordinates, and test result.

### Task 8: Add standalone product-recognition gateway and response contracts

**Files:**
- Create: `app/src/main/java/me/fleey/ppocrv5/call/ProductRecognitionGateway.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/UnavailableProductRecognitionGateway.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/ProductRecognitionRequest.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/ProductRecognitionResponseParser.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/ProductRecognitionReplyFormatter.kt`
- Create: `app/src/main/java/me/fleey/ppocrv5/call/ProductImageEncoder.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/VoiceCallService.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/ProductRecognitionRequestTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/ProductRecognitionResponseParserTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/ProductRecognitionReplyFormatterTest.kt`
- Test: `app/src/test/java/me/fleey/ppocrv5/call/ProductRecognitionGatewayTest.kt`

**Interfaces:**
- Produces: `ProductRecognitionGateway.recognize(ProductRecognitionRequest): ProductRecognitionResult`.
- Default result: `ProductRecognitionResult.Unavailable("独立版未配置商品识别服务")`.

- [ ] **Step 1: Add request, parser, formatter, and no-network default tests**

Assert request image dimensions reflect the Bitmap, only the first product is formatted, and the default gateway returns `Unavailable` synchronously without opening a connection.

- [ ] **Step 2: Run product tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.Product*'`

- [ ] **Step 3: Adapt platform-neutral product contracts from the source**

Use Android `org.json` instead of fastjson. Keep parameter names and response parsing isolated from the service.

- [ ] **Step 4: Integrate current-frame encoding and unavailable reply**

Capture through the same retry pipeline as OCR, build a request, invoke the injected gateway, and speak its formatted result once through the turn coordinator.

- [ ] **Step 5: Verify no forbidden dependencies or network permission were introduced**

Run: `rg -n 'MtopBusiness|mtopsdk|fastjson|android.permission.INTERNET|com.tmall' app/src app/build.gradle.kts`

Expected: no matches.

- [ ] **Step 6: Run product tests and assemble**

Run: `./gradlew testDebugUnitTest --tests 'me.fleey.ppocrv5.call.Product*' assembleDebug`

- [ ] **Step 7: Record checkpoint**

Record the gateway interface and the default unavailable message.

### Task 9: Complete manifest, lifecycle, documentation, and full verification

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `README_zh.md`
- Modify: `README.md`
- Modify: `docs/LOCAL_AI_CALL_TECHNICAL_ANALYSIS.md`
- Test: all `app/src/test/java/me/fleey/ppocrv5/call` tests.

**Interfaces:**
- Consumes: every task's public contract.
- Produces: a standalone Debug APK and documented device-verification checklist.

- [ ] **Step 1: Audit permissions, services, features, and exported flags**

The manifest must declare microphone, wake lock, foreground service, notification, microphone/mediaProjection foreground types, JD package query, PiP support, and the accessibility service binding permission. It must not declare Internet permission.

- [ ] **Step 2: Add/refresh README capability and privacy documentation**

Document authorization order, supported voice commands, PiP behavior, cache location, accessibility warning, JD fallback coordinates, and the unavailable product-recognition adapter.

- [ ] **Step 3: Run the complete unit suite**

Run: `./gradlew testDebugUnitTest`

Expected: all tests pass with zero failures.

- [ ] **Step 4: Run lint and resolve all new fatal findings**

Run: `./gradlew lintDebug`

Expected: exit code 0; pre-existing warnings may remain documented, but no new error may be suppressed without a scoped rationale.

- [ ] **Step 5: Build the final Debug APK**

Run: `./gradlew assembleDebug`

Expected: `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Run forbidden-dependency and duplicate-asset checks**

Run: `rg -n 'MtopBusiness|mtopsdk|fastjson|com.tmall' app/src app/build.gradle.kts`

Expected: no matches.

Run: `find app/src/main/assets -type f -print | sort | uniq -d`

Expected: no output.

- [ ] **Step 7: Execute the documented real-device checklist**

Verify call start/stop, background notification, screen-share revocation, three capture retries, compact-to-frame PiP transition, return-to-results navigation, stop-only TTS interruption, speaker mode, generic accessibility commands, and the full JD workflow.

- [ ] **Step 8: Record final checkpoint**

Record test, lint, and assemble outputs plus any device-only items that still require a physical phone. Do not claim device-only behavior was verified from JVM tests.
