package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatAutomationPoliciesTest {
  @Test
  fun `labeled targets use normalized exact matching`() {
    assertTrue(WeChatAccessibilityNodePolicy.matches(" 搜\n索 ", null, WeChatTarget.TOP_SEARCH))
    assertTrue(WeChatAccessibilityNodePolicy.matches(null, "所有女生会员服务中心", WeChatTarget.SERVICE_CENTER))
    assertTrue(WeChatAccessibilityNodePolicy.matches("直播预告", null, WeChatTarget.LIVE_PREVIEW))
    assertFalse(WeChatAccessibilityNodePolicy.matches("搜索结果", null, WeChatTarget.TOP_SEARCH))
  }

  @Test
  fun `text query click skips partial matches and directly clicks the exact result`() {
    val attempts = mutableListOf<String>()

    val clicked = WeChatTextQueryClickStrategy.click(
      candidates = listOf("搜索结果", "搜索"),
      matches = { it == "搜索" },
      performDirectClick = {
        attempts += "direct:$it"
        true
      },
      performAncestorClick = {
        attempts += "ancestor:$it"
        true
      },
    )

    assertTrue(clicked)
    assertEquals(listOf("direct:搜索"), attempts)
  }

  @Test
  fun `text query click tries the matching ancestor after direct action fails`() {
    val attempts = mutableListOf<String>()

    val clicked = WeChatTextQueryClickStrategy.click(
      candidates = listOf("搜索"),
      matches = { true },
      performDirectClick = {
        attempts += "direct"
        false
      },
      performAncestorClick = {
        attempts += "ancestor"
        true
      },
    )

    assertTrue(clicked)
    assertEquals(listOf("direct", "ancestor"), attempts)
  }

  @Test
  fun `query input requires an enabled editable node`() {
    assertTrue(WeChatAccessibilityNodePolicy.isQueryInput(editable = true, enabled = true))
    assertFalse(WeChatAccessibilityNodePolicy.isQueryInput(editable = false, enabled = true))
    assertFalse(WeChatAccessibilityNodePolicy.isQueryInput(editable = true, enabled = false))
  }

  @Test
  fun `focused editable input submits through ime search action on Android 11`() {
    var imeSearchActions = 0

    val submitted = WeChatImeSearchActionPolicy.submit(
      sdkInt = 30,
      editable = true,
      enabled = true,
      focused = true,
      performImeEnter = {
        imeSearchActions += 1
        true
      },
    )

    assertTrue(submitted)
    assertEquals(1, imeSearchActions)
  }

  @Test
  fun `ime search action is not sent to an unfocused input`() {
    var imeSearchActions = 0

    val submitted = WeChatImeSearchActionPolicy.submit(
      sdkInt = 35,
      editable = true,
      enabled = true,
      focused = false,
      performImeEnter = {
        imeSearchActions += 1
        true
      },
    )

    assertFalse(submitted)
    assertEquals(0, imeSearchActions)
  }

  @Test
  fun `page logger formats page boundaries and node text content`() {
    assertEquals(
      "page_start package=com.tencent.mm page=com.tencent.mm.ui.LauncherUI",
      WeChatPageLogFormatter.pageStart("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
    )
    assertEquals(
      "node[2] class=android.widget.TextView text=\"李佳琦\\n直播\" content=\"入口\"",
      WeChatPageLogFormatter.node(
        index = 2,
        className = "android.widget.TextView",
        text = "李佳琦\n直播",
        contentDescription = "入口",
      ),
    )
    assertEquals("page_end nodes=3", WeChatPageLogFormatter.pageEnd(3))
  }

  @Test
  fun `page logger distinguishes missing component values`() {
    assertEquals(
      "node[0] class=<null> text=<null> content=<null>",
      WeChatPageLogFormatter.node(0, null, null, null),
    )
  }

  @Test
  fun `cached focused input is used when current focus is unavailable`() {
    val attempts = mutableListOf<String>()

    val entered = WeChatQueryEntryStrategy.enter(
      currentFocusedInput = null,
      cachedFocusedInput = "focused-input",
      isUsableFocusedInput = { it == "focused-input" },
      enterFromFocusedInput = {
        attempts += it
        true
      },
      enterFromWindowTree = {
        attempts += "window"
        false
      },
    )

    assertTrue(entered)
    assertEquals(listOf("focused-input"), attempts)
  }

  @Test
  fun `current focused input is used before the window tree`() {
    val attempts = mutableListOf<String>()

    val entered = WeChatQueryEntryStrategy.enter(
      currentFocusedInput = "current-focus",
      cachedFocusedInput = "cached-focus",
      isUsableFocusedInput = { true },
      enterFromFocusedInput = {
        attempts += it
        true
      },
      enterFromWindowTree = {
        attempts += "window"
        true
      },
    )

    assertTrue(entered)
    assertEquals(listOf("current-focus"), attempts)
  }

  @Test
  fun `clipboard paste is used when set text reports success without changing the query`() {
    var observedText = ""
    val attempts = mutableListOf<String>()

    val method = WeChatVerifiedQueryEntryStrategy.enter(
      expectedText = "李佳琦",
      currentText = { observedText },
      performSetText = {
        attempts += "set-text"
        true
      },
      prepareClipboard = {
        attempts += "clipboard"
        true
      },
      performPaste = {
        attempts += "paste"
        observedText = "李佳琦"
        true
      },
      refresh = { attempts += "refresh" },
    )

    assertEquals(WeChatQueryEntryMethod.CLIPBOARD_PASTE, method)
    assertEquals(
      listOf("set-text", "refresh", "clipboard", "paste", "refresh"),
      attempts,
    )
  }

  @Test
  fun `verified set text avoids changing the clipboard`() {
    var observedText = ""
    var clipboardPrepared = false

    val method = WeChatVerifiedQueryEntryStrategy.enter(
      expectedText = "李佳琦",
      currentText = { observedText },
      performSetText = {
        observedText = "李佳琦"
        true
      },
      prepareClipboard = {
        clipboardPrepared = true
        true
      },
      performPaste = { true },
      refresh = {},
    )

    assertEquals(WeChatQueryEntryMethod.SET_TEXT, method)
    assertFalse(clipboardPrepared)
  }

  @Test
  fun `query entry stays incomplete when neither input method changes the text`() {
    val method = WeChatVerifiedQueryEntryStrategy.enter(
      expectedText = "李佳琦",
      currentText = { "" },
      performSetText = { true },
      prepareClipboard = { true },
      performPaste = { true },
      refresh = {},
    )

    assertEquals(null, method)
  }

  @Test
  fun `first feed candidate must be a visible collection item below selected date`() {
    val anchorBottom = 320
    val screenHeight = 2_400

    assertTrue(WeChatFeedNodePolicy.isCandidate(360, 760, anchorBottom, screenHeight, true, false))
    assertTrue(WeChatFeedNodePolicy.isCandidate(360, 760, anchorBottom, screenHeight, false, true))
    assertFalse(WeChatFeedNodePolicy.isCandidate(280, 760, anchorBottom, screenHeight, true, false))
    assertFalse(WeChatFeedNodePolicy.isCandidate(360, 380, anchorBottom, screenHeight, true, false))
    assertFalse(WeChatFeedNodePolicy.isCandidate(2_200, 2_390, anchorBottom, screenHeight, true, false))
    assertFalse(WeChatFeedNodePolicy.isCandidate(360, 760, anchorBottom, screenHeight, false, false))
  }

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

  @Test
  fun `collection item wins when nested feed candidates share the same top`() {
    val wrapper = WeChatFeedCandidate(420, 820, 24, 1_056, false, true)
    val item = WeChatFeedCandidate(420, 820, 24, 1_056, true, true)

    assertEquals(
      item,
      WeChatFeedNodePolicy.selectFirst(listOf(wrapper, item), 360, 2_400),
    )
  }

  @Test
  fun `result messages distinguish completion from missing app and timeout`() {
    assertEquals("微信商品页面已打开，正在截取关键帧。", WeChatAutomationResultFormatter.format(WeChatAutomationResult.COMPLETED))
    assertEquals("没有检测到微信 App，请先安装后再试。", WeChatAutomationResultFormatter.format(WeChatAutomationResult.WECHAT_NOT_INSTALLED))
    assertEquals("等待微信目标页面超时，请重新试一次。", WeChatAutomationResultFormatter.format(WeChatAutomationResult.TIMED_OUT))
  }
}
