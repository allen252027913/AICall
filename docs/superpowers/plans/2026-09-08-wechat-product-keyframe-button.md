# WeChat Product Keyframe Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a button-triggered, cancellable WeChat accessibility flow that opens the requested first live-preview product and captures its stable screen as a local OCR keyframe.

**Architecture:** Keep the existing `VoiceCallService` turn and token lease as the orchestration boundary. The Compose button dispatches the existing dedicated service action, the accessibility service advances the existing typed state machine, and successful completion returns to the existing MediaProjection/OCR/history pipeline. Harden only two uncovered boundaries: disable the button while a turn is busy, and select the spatially topmost valid Feed product instead of trusting accessibility-tree order.

**Tech Stack:** Kotlin, Android AccessibilityService, Jetpack Compose, MediaProjection, PP-OCRv5, JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-08-wechat-product-keyframe-button-design.md`

## Global Constraints

- Keep the search query exactly `李佳琦`, the service-center target exactly `所有女生会员服务中心`, the tab exactly `直播预告`, and the date exactly `09`.
- Use package `com.tencent.mm`; do not bypass login, consent, captcha, or system permission UI.
- Accessibility node semantics remain the primary locator; do not add fixed-device coordinate scripts.
- Keep all image analysis local and do not add network upload or new dependencies.
- Preserve token lease checks around every external click, text input, completion callback, and capture commit.
- The current workspace is not recognized as a Git repository. Do not initialize Git; omit commit commands unless repository metadata is restored by the user.

---

### Task 1: Make the new button reflect turn availability

**Files:**
- Modify: `app/src/test/java/me/fleey/ppocrv5/call/WeChatAutomationButtonPolicyTest.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/WeChatAutomationButtonPolicy.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/CallScreen.kt`
- Verify: `app/src/main/java/me/fleey/ppocrv5/MainActivity.kt`
- Verify: `app/src/main/java/me/fleey/ppocrv5/call/VoiceCallService.kt`

**Interfaces:**
- Consumes: `CallSessionState.phase: CallPhase`, `CallPhase.LISTENING`, and the existing `onStartWeChatCapture: () -> Unit` callback.
- Produces: `WeChatAutomationButtonPolicy.isEnabled(callActive: Boolean, screenSharing: Boolean, accessibilityEnabled: Boolean, phase: CallPhase): Boolean`.

- [ ] **Step 1: Change the policy test first**

Replace the existing happy assertion and add busy-state assertions:

```kotlin
assertTrue(
  WeChatAutomationButtonPolicy.isEnabled(
    callActive = true,
    screenSharing = true,
    accessibilityEnabled = true,
    phase = CallPhase.LISTENING,
  ),
)
assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, true, true, CallPhase.PROCESSING))
assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, true, true, CallPhase.SPEAKING))
assertFalse(WeChatAutomationButtonPolicy.isEnabled(false, true, true, CallPhase.LISTENING))
assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, false, true, CallPhase.LISTENING))
assertFalse(WeChatAutomationButtonPolicy.isEnabled(true, true, false, CallPhase.LISTENING))
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests me.fleey.ppocrv5.call.WeChatAutomationButtonPolicyTest --no-daemon
```

Expected: compilation fails because `isEnabled` does not yet accept `phase`.

- [ ] **Step 3: Implement the minimal readiness rule**

Update the policy to:

```kotlin
object WeChatAutomationButtonPolicy {
  fun isEnabled(
    callActive: Boolean,
    screenSharing: Boolean,
    accessibilityEnabled: Boolean,
    phase: CallPhase,
  ): Boolean = callActive && screenSharing && accessibilityEnabled && phase == CallPhase.LISTENING
}
```

Pass `state.phase` from `CallScreen`:

```kotlin
enabled = WeChatAutomationButtonPolicy.isEnabled(
  callActive = state.callActive,
  screenSharing = state.screenSharing,
  accessibilityEnabled = accessibilityEnabled,
  phase = state.phase,
)
```

- [ ] **Step 4: Verify the dedicated button route remains intact**

Confirm `MainActivity` sends `VoiceCallService.ACTION_START_WECHAT_CAPTURE`, and `VoiceCallService.onStartCommand` maps that action to the fixed button query. The required code shape is:

```kotlin
onStartWeChatCapture = {
  sendServiceAction(VoiceCallService.ACTION_START_WECHAT_CAPTURE)
}
```

```kotlin
ACTION_START_WECHAT_CAPTURE -> handleQuery(WECHAT_CAPTURE_BUTTON_QUERY)
```

Do not modify these files if those exact contracts are already present.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests me.fleey.ppocrv5.call.WeChatAutomationButtonPolicyTest \
  --tests me.fleey.ppocrv5.call.LocalCallTurnPolicyTest \
  --no-daemon
```

Expected: both test classes pass.

### Task 2: Add a safe complete-tree traversal primitive

**Files:**
- Modify: `app/src/test/java/me/fleey/ppocrv5/call/DepthFirstTraversalTest.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/DepthFirstTraversal.kt`

**Interfaces:**
- Consumes: the existing `TraversalNode` ownership contract.
- Produces: `DepthFirstTraversal.forEach(root: TraversalNode, visit: (TraversalNode) -> Unit): Unit`, which visits root and descendants in depth-first screen order, never recycles the caller-owned root, and recycles every retrieved child exactly once even when `visit` throws.

- [ ] **Step 1: Write failing traversal tests**

Add these tests using the existing `FakeNode`:

```kotlin
@Test
fun `for each visits the complete tree in depth first order and recycles owned children`() {
  val leaf = FakeNode("leaf")
  val branch = FakeNode("branch", leaf)
  val sibling = FakeNode("sibling")
  val root = FakeNode("root", branch, sibling)
  val visited = mutableListOf<String>()

  DepthFirstTraversal.forEach(root) { visited += (it as FakeNode).label }

  assertEquals(listOf("root", "branch", "leaf", "sibling"), visited)
  assertEquals(0, root.recycleCount)
  assertEquals(1, branch.recycleCount)
  assertEquals(1, leaf.recycleCount)
  assertEquals(1, sibling.recycleCount)
}

@Test
fun `for each recycles queued nodes when visitor throws`() {
  val branch = FakeNode("branch")
  val sibling = FakeNode("sibling")
  val root = FakeNode("root", branch, sibling)

  runCatching {
    DepthFirstTraversal.forEach(root) { node ->
      if ((node as FakeNode).label == "branch") error("stop")
    }
  }.onSuccess { error("visitor should fail") }

  assertEquals(1, branch.recycleCount)
  assertEquals(1, sibling.recycleCount)
}
```

- [ ] **Step 2: Run the traversal test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests me.fleey.ppocrv5.call.DepthFirstTraversalTest --no-daemon
```

Expected: compilation fails because `DepthFirstTraversal.forEach` is missing.

- [ ] **Step 3: Implement full traversal with the same ownership guarantees**

Add to `DepthFirstTraversal`:

```kotlin
fun forEach(root: TraversalNode, visit: (TraversalNode) -> Unit) {
  val stack = ArrayDeque<OwnedNode>()
  stack.addLast(OwnedNode(root, owned = false))
  while (stack.isNotEmpty()) {
    val current = stack.removeLast()
    try {
      visit(current.node)
      for (index in current.node.childCount - 1 downTo 0) {
        current.node.childAt(index)?.let { stack.addLast(OwnedNode(it, owned = true)) }
      }
    } catch (error: Throwable) {
      current.recycleIfOwned()
      recycleStack(stack)
      throw error
    }
    current.recycleIfOwned()
  }
}
```

Keep `OwnedNode`, `recycleIfOwned`, and `recycleStack` private and shared by `firstResult` and `forEach`.

- [ ] **Step 4: Run the traversal tests and verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest --tests me.fleey.ppocrv5.call.DepthFirstTraversalTest --no-daemon
```

Expected: all traversal tests pass with no leaked or double-recycled fake nodes.

### Task 3: Select the spatially first valid Feed product

**Files:**
- Modify: `app/src/test/java/me/fleey/ppocrv5/call/WeChatAutomationPoliciesTest.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/WeChatAutomationTargets.kt`
- Modify: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAccessibilityService.kt`

**Interfaces:**
- Consumes: `DepthFirstTraversal.forEach`, `WeChatFeedNodePolicy.isCandidate`, the selected `09` anchor bottom, and current screen height.
- Produces: `WeChatFeedCandidate(top: Int, bottom: Int, left: Int, right: Int, collectionItem: Boolean, clickable: Boolean)` and `WeChatFeedNodePolicy.selectFirst(candidates: Iterable<WeChatFeedCandidate>, anchorBottom: Int, screenHeight: Int): WeChatFeedCandidate?`.

- [ ] **Step 1: Write a failing selection-policy test**

Add:

```kotlin
@Test
fun `first feed selection uses screen position instead of accessibility traversal order`() {
  val lowerTreeNode = WeChatFeedCandidate(900, 1_300, 24, 1_056, true, false)
  val topmostProduct = WeChatFeedCandidate(420, 820, 24, 1_056, false, true)
  val header = WeChatFeedCandidate(300, 350, 24, 1_056, false, true)

  assertEquals(
    topmostProduct,
    WeChatFeedNodePolicy.selectFirst(
      candidates = listOf(lowerTreeNode, header, topmostProduct),
      anchorBottom = 360,
      screenHeight = 2_400,
    ),
  )
}
```

Add a tie-break test:

```kotlin
@Test
fun `collection item wins when nested feed candidates share the same top`() {
  val wrapper = WeChatFeedCandidate(420, 820, 24, 1_056, false, true)
  val item = WeChatFeedCandidate(420, 820, 24, 1_056, true, true)

  assertEquals(
    item,
    WeChatFeedNodePolicy.selectFirst(listOf(wrapper, item), 360, 2_400),
  )
}
```

- [ ] **Step 2: Run the policy test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests me.fleey.ppocrv5.call.WeChatAutomationPoliciesTest --no-daemon
```

Expected: compilation fails because `WeChatFeedCandidate` and `selectFirst` do not exist.

- [ ] **Step 3: Implement deterministic candidate selection**

Add the data class and selector:

```kotlin
data class WeChatFeedCandidate(
  val top: Int,
  val bottom: Int,
  val left: Int,
  val right: Int,
  val collectionItem: Boolean,
  val clickable: Boolean,
)

fun selectFirst(
  candidates: Iterable<WeChatFeedCandidate>,
  anchorBottom: Int,
  screenHeight: Int,
): WeChatFeedCandidate? = candidates
  .filter { candidate ->
    isCandidate(
      top = candidate.top,
      bottom = candidate.bottom,
      anchorBottom = anchorBottom,
      screenHeight = screenHeight,
      collectionItem = candidate.collectionItem,
      clickable = candidate.clickable,
    )
  }
  .minWithOrNull(
    compareBy<WeChatFeedCandidate> { it.top }
      .thenByDescending { it.collectionItem }
      .thenBy { it.left }
      .thenByDescending { it.bottom - it.top },
  )
```

- [ ] **Step 4: Use a two-pass accessibility lookup**

In `findAndClickFirstWeChatProduct`, first traverse the complete tree and collect immutable candidates:

```kotlin
val candidates = mutableListOf<WeChatFeedCandidate>()
DepthFirstTraversal.forEach(AndroidTraversalNode(root)) { traversalNode ->
  val node = (traversalNode as AndroidTraversalNode).node
  val bounds = Rect().also(node::getBoundsInScreen)
  candidates += WeChatFeedCandidate(
    top = bounds.top,
    bottom = bounds.bottom,
    left = bounds.left,
    right = bounds.right,
    collectionItem = node.collectionItemInfo != null,
    clickable = node.isClickable,
  )
}
val selected = WeChatFeedNodePolicy.selectFirst(candidates, anchorBottom, screenHeight)
  ?: return false
```

Then traverse a second time and click the first node whose immutable candidate equals `selected` and whose lease is still active:

```kotlin
return DepthFirstTraversal.firstResult(AndroidTraversalNode(root)) { traversalNode ->
  val node = (traversalNode as AndroidTraversalNode).node
  val bounds = Rect().also(node::getBoundsInScreen)
  val candidate = WeChatFeedCandidate(
    bounds.top,
    bounds.bottom,
    bounds.left,
    bounds.right,
    node.collectionItemInfo != null,
    node.isClickable,
  )
  if (candidate != selected) return@firstResult null
  true.takeIf { clickNodeOrAncestor(token, node) }
} ?: false
```

- [ ] **Step 5: Run policy and traversal tests and verify GREEN**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests me.fleey.ppocrv5.call.WeChatAutomationPoliciesTest \
  --tests me.fleey.ppocrv5.call.DepthFirstTraversalTest \
  --no-daemon
```

Expected: both test classes pass.

### Task 4: Verify the complete state, cancellation, and keyframe pipeline

**Files:**
- Verify: `app/src/test/java/me/fleey/ppocrv5/call/WeChatAutomationStateMachineTest.kt`
- Verify: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallWeChatAutomationControllerTest.kt`
- Verify: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallServicePoliciesTest.kt`
- Verify: `app/src/test/java/me/fleey/ppocrv5/call/LocalCallScreenCommitCoordinatorTest.kt`
- Verify: `app/src/main/java/me/fleey/ppocrv5/call/VoiceCallService.kt`
- Verify: `app/src/main/java/me/fleey/ppocrv5/call/LocalCallAccessibilityService.kt`
- Verify: `app/src/main/AndroidManifest.xml`
- Verify: `app/src/main/res/xml/local_call_accessibility_service.xml`

**Interfaces:**
- Consumes: the button route, `WeChatAutomationStateMachine`, `LocalCallAutomationCommandStore`, `LocalCallAutomationResultBus`, and `captureScreenWithoutPictureInPicture()`.
- Produces: a verified end-to-end local contract in which only `WeChatAutomationResult.COMPLETED` schedules `captureWeChatProductFrame`, and only the matching active shared-screen turn may publish a keyframe.

- [ ] **Step 1: Run all focused WeChat and capture-lifecycle tests**

Run:

```bash
./gradlew testDebugUnitTest \
  --tests 'me.fleey.ppocrv5.call.WeChatAutomation*' \
  --tests me.fleey.ppocrv5.call.LocalCallWeChatAutomationControllerTest \
  --tests me.fleey.ppocrv5.call.LocalCallServicePoliciesTest \
  --tests me.fleey.ppocrv5.call.LocalCallScreenCommitCoordinatorTest \
  --tests me.fleey.ppocrv5.call.LocalCallAutomationResultBusTest \
  --no-daemon
```

Expected: tests prove the exact stage order, per-stage timeout, permission rejection, tokenized one-shot completion, projection-stop invalidation, and guarded screen commit.

- [ ] **Step 2: Verify Android declarations and source contracts**

Confirm the manifest contains:

```xml
<package android:name="com.tencent.mm" />
```

Confirm the accessibility service declares both content retrieval and gesture capability:

```xml
android:canPerformGestures="true"
android:canRetrieveWindowContent="true"
```

Confirm successful automation calls `captureWeChatProductFrame(turn)`, and failed automation calls `completeTurn(turn, result.message)` without capture.

- [ ] **Step 3: Run the full JVM test suite**

Run:

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all unit tests pass.

- [ ] **Step 4: Compile and package the Debug app**

Run:

```bash
./gradlew compileDebugKotlin assembleDebug --no-daemon
```

Expected: Gradle exits successfully and produces `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Record the remaining device-only acceptance test**

On an Android device with a logged-in WeChat account:

1. Start the local call.
2. Grant screen sharing.
3. Enable the app's accessibility service.
4. Verify the button becomes enabled only while the call is listening.
5. Tap “微信商品关键帧”.
6. Observe the exact sequence: 微信搜索 → 李佳琦 → 所有女生会员服务中心 → 直播预告 → 09 → first visible Feed product.
7. Verify the product page is displayed before capture feedback.
8. Return to the demo and verify a new current keyframe and recognition-history entry exist.
9. Repeat once while cancelling screen sharing mid-flow; verify automation stops and no new keyframe is committed.

Because a real device and authenticated third-party App are required, report this step as pending unless such a device is connected and authorized in the workspace.
