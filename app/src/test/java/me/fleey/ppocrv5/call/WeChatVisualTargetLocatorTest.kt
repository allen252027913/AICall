package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatVisualTargetLocatorTest {
  @Test
  fun `live preview missing from real home OCR is located from adjacent bottom tabs`() {
    // 2026-09-11 device frame: grey live-preview text was omitted by OCR.
    val frame = frame(
      block("首页", 147f, 194f),
      block("会员商城正品优选", 257f, 1189f),
      block("积分试用提前试", 763f, 1189f),
      block("□", 856f, 2539f),
      block("积分试用", 367f, 2598f),
      block("会员商城", 603f, 2598f),
    )
    val action = WeChatVisualTargetLocator.targetAction(frame, WeChatTarget.LIVE_PREVIEW)
    assertTrue(action is WeChatFrameTargetAction.ClickTarget)
    val point = (action as WeChatFrameTargetAction.ClickTarget).point
    assertTrue(point.x in 800f..910f)
    assertTrue(point.y in 2500f..2570f)
  }

  @Test
  fun `live preview ignores matching content above bottom navigation`() {
    val frame = frame(
      block("直播预告", 400f, 900f),
      block("直播预告", 854f, 2600f),
    )
    val action = WeChatVisualTargetLocator.targetAction(frame, WeChatTarget.LIVE_PREVIEW)
      as WeChatFrameTargetAction.ClickTarget
    assertEquals(854f, action.point.x)
    assertTrue(action.point.y in 2500f..2570f)
    assertNull(WeChatVisualTargetLocator.targetAction(
      frame(block("直播预告", 400f, 900f)), WeChatTarget.LIVE_PREVIEW,
    ))
  }

  @Test
  fun `live preview fallback rejects missing or misaligned navigation anchors`() {
    listOf(
      frame(block("会员商城", 603f, 2598f)),
      frame(block("积分试用", 367f, 1200f), block("会员商城", 603f, 1200f)),
      frame(block("积分试用", 367f, 2500f), block("会员商城", 603f, 2598f)),
      frame(block("积分试用", 367f, 2598f), block("会员商城", 490f, 2598f)),
      frame(block("积分试用", 300f, 2598f), block("会员商城", 490f, 2598f)),
      frame(block("积分试用", 440f, 2598f), block("会员商城", 730f, 2598f)),
      frame(block("积分试用", 950f, 2598f), block("会员商城", 1100f, 2598f)),
    ).forEach {
      assertNull(WeChatVisualTargetLocator.targetAction(it, WeChatTarget.LIVE_PREVIEW))
    }
  }

  @Test
  fun `live preview fallback scales with frame dimensions`() {
    val frame = WeChatKeyFrame(610, 1350, listOf(
      block("积分试用", 183.5f, 1299f), block("会员商城", 301.5f, 1299f),
    ))
    val action = WeChatVisualTargetLocator.targetAction(frame, WeChatTarget.LIVE_PREVIEW)
    assertTrue(action is WeChatFrameTargetAction.ClickTarget)
    val point = (action as WeChatFrameTargetAction.ClickTarget).point
    assertTrue(point.x in 400f..455f)
    assertTrue(point.y in 1250f..1285f)
  }

  @Test
  fun `me page selects the bottom WeChat tab before looking for search`() {
    val frame = frame(
      block("服务", 170f, 610f),
      block("微信", 150f, 2_560f),
      block("通讯录", 460f, 2_560f),
      block("发现", 760f, 2_560f),
      block("我", 1_065f, 2_560f),
    )

    assertEquals(
      WeChatTopSearchFrameAction.SelectChatsTab(WeChatScreenPoint(150f, 2_560f)),
      WeChatVisualTargetLocator.topSearchAction(frame),
    )
  }

  @Test
  fun `chat page uses the captured toolbar search component midpoint`() {
    val frame = frame(
      block("微信(14)", 610f, 190f),
      block("Q", 1_008f, 194f),
      block("微信", 150f, 2_560f),
      block("通讯录", 460f, 2_560f),
    )

    assertEquals(
      WeChatTopSearchFrameAction.OpenSearch(WeChatScreenPoint(1_008f, 194f)),
      WeChatVisualTargetLocator.topSearchAction(frame),
    )
  }

  @Test
  fun `search page input comes from OCR component center`() {
    val frame = frame(block("搜索本地或网络结果", 545f, 220f))

    assertEquals(
      WeChatScreenPoint(545f, 220f),
      WeChatVisualTargetLocator.queryInput(frame),
    )
  }

  @Test
  fun `labeled target uses its OCR component center`() {
    val frame = frame(
      block("李佳琦 - 账号", 250f, 560f),
      block("所有女生会员服务中心", 500f, 720f),
    )

    assertEquals(
      WeChatScreenPoint(500f, 720f),
      WeChatVisualTargetLocator.labeledTarget(frame, WeChatTarget.SERVICE_CENTER),
    )
    assertNull(WeChatVisualTargetLocator.labeledTarget(frame, WeChatTarget.LIVE_PREVIEW))
  }

  @Test
  fun `service center stage clicks exact query suggestion before the final result`() {
    val suggestion = block("李佳琦", 215f, 600f)
    val frame = frame(
      block("搜索网络结果", 250f, 450f),
      suggestion,
      block("李佳琦所有女生小程序", 370f, 760f),
    )

    assertEquals(
      WeChatFrameTargetAction.ClickIntermediate(WeChatScreenPoint(215f, 600f)),
      WeChatVisualTargetLocator.targetAction(frame, WeChatTarget.SERVICE_CENTER),
    )
  }

  @Test
  fun `target stage clicks the captured label midpoint`() {
    val frame = frame(block("所有女生会员服务中心", 530f, 740f))

    assertEquals(
      WeChatFrameTargetAction.ClickTarget(WeChatScreenPoint(530f, 740f)),
      WeChatVisualTargetLocator.targetAction(frame, WeChatTarget.SERVICE_CENTER),
    )
  }

  @Test
  fun `tomorrow uses the matching date component when OCR adds noise to its label`() {
    val frame = frame(
      block("6月", 172f, 354f),
      block("0周", 316f, 354f),
      block("00E", 459f, 354f),
      block("0☰", 611f, 354f),
      block("10囮", 755f, 354f),
      block("12脈", 1_050f, 354f),
    )

    assertEquals(
      WeChatFrameTargetAction.ClickTarget(WeChatScreenPoint(1_050f, 354f)),
      WeChatVisualTargetLocator.nextDayAction(frame, targetDayOfMonth = 12),
    )
  }

  @Test
  fun `missing tomorrow clicks the right page triangle at the date row midpoint`() {
    val frame = frame(
      block("06", 172f, 354f),
      block("07", 316f, 354f),
      block("08", 459f, 354f),
      block("09", 611f, 354f),
      block("10", 755f, 354f),
      block("11", 900f, 354f),
      block("12:00开播", 530f, 535f),
    )

    assertEquals(
      WeChatFrameTargetAction.ClickIntermediate(WeChatScreenPoint(1_146.8f, 354f)),
      WeChatVisualTargetLocator.nextDayAction(frame, targetDayOfMonth = 12),
    )
  }

  @Test
  fun `keyboard pinyin keys come from OCR key centers`() {
    val frame = frame(
      block("q", 61f, 1_980f),
      block("i", 909f, 1_980f),
      block("a", 104f, 2_150f),
      block("j", 836f, 2_150f),
      block("l", 1_079f, 2_150f),
    )

    assertEquals(
      listOf(
        WeChatScreenPoint(1_079f, 2_150f),
        WeChatScreenPoint(909f, 1_980f),
        WeChatScreenPoint(836f, 2_150f),
        WeChatScreenPoint(104f, 2_150f),
        WeChatScreenPoint(61f, 1_980f),
        WeChatScreenPoint(909f, 1_980f),
      ),
      WeChatVisualTargetLocator.pinyinKeys(frame),
    )
  }

  @Test
  fun `keyboard candidate and search use their captured text centers`() {
    val frame = frame(
      block("李佳琦", 145f, 1_795f),
      block("搜索", 1_105f, 2_515f),
    )

    assertEquals(
      WeChatScreenPoint(145f, 1_795f),
      WeChatVisualTargetLocator.queryCandidate(frame),
    )
    assertEquals(
      WeChatScreenPoint(1_105f, 2_515f),
      WeChatVisualTargetLocator.keyboardSearch(frame),
    )
  }

  @Test
  fun `first product is the title below all products and default not the live room header`() {
    val frame = frame(
      block("12脈", 1_050f, 355f),
      block("李佳琦直播间", 270f, 535f),
      block("体上播产品和优惠信息", 550f, 785f),
      block("全部商品", 135f, 880f),
      block("默认", 78f, 980f),
      block("今日预约榜", 610f, 980f),
      block("场次筛选", 1060f, 980f),
      block("玫珑网纹瓜4粒装", 775f, 1130f),
      block("4粒装单果900-1100g", 790f, 1280f),
      block("86.9元/4粒", 1030f, 1350f),
      block("上播提醒", 280f, 1630f),
      block("鹤仙人四川凯特芒2.5kg", 850f, 1990f),
    )

    assertEquals(
      WeChatScreenPoint(775f, 1130f),
      WeChatVisualTargetLocator.firstProduct(frame),
    )
  }

  @Test
  fun `loading list does not mistake header or reminder for a product`() {
    assertNull(WeChatVisualTargetLocator.firstProduct(frame(
      block("12", 1050f, 355f), block("李佳琦直播间", 270f, 535f),
    )))
    assertNull(WeChatVisualTargetLocator.firstProduct(frame(
      block("全部商品", 135f, 880f), block("默认", 78f, 980f),
      block("正在加载中", 610f, 1130f), block("上播提醒", 750f, 1200f),
    )))
    assertNull(WeChatVisualTargetLocator.firstProduct(frame(
      block("全部商品", 135f, 880f), block("默认", 78f, 980f),
      block("暂无商品", 610f, 1130f),
    )))
  }

  @Test
  fun `missing first title does not select the next card far below the filters`() {
    assertNull(WeChatVisualTargetLocator.firstProduct(frame(
      block("全部商品", 135f, 880f), block("默认", 78f, 980f),
      block("鹤仙人四川凯特芒2.5kg", 850f, 1990f),
    )))
  }

  @Test
  fun `real frame missing default OCR uses paired sort row anchors`() {
    assertEquals(WeChatScreenPoint(763f, 1130f), WeChatVisualTargetLocator.firstProduct(frame(
      block("全部商品", 139f, 885f),
      block("今日预约榜", 594f, 978f), block("场次筛选▼", 1067f, 978f),
      block("玫珑网纹瓜4粒装", 763f, 1130f),
    )))
    assertNull(WeChatVisualTargetLocator.firstProduct(frame(
      block("全部商品", 139f, 885f), block("场次筛选▼", 1067f, 978f),
      block("玫珑网纹瓜4粒装", 763f, 1130f),
    )))
    assertNull(WeChatVisualTargetLocator.firstProduct(frame(
      block("全部商品", 139f, 885f),
      block("今日预约榜", 594f, 920f), block("场次筛选▼", 1067f, 1040f),
      block("玫珑网纹瓜4粒装", 763f, 1130f),
    )))
  }

  @Test
  fun `first product supports scaled frames and merged sort labels`() {
    val frame = WeChatKeyFrame(610, 1350, listOf(
      block("全部商品", 67.5f, 440f), block("默认 价格 最新", 160f, 490f),
      block("玫珑网纹瓜4粒装", 387.5f, 565f),
    ))
    assertEquals(WeChatScreenPoint(387.5f, 565f), WeChatVisualTargetLocator.firstProduct(frame))
  }

  private fun frame(vararg blocks: ScreenTextBlock) = WeChatKeyFrame(
    width = 1_220,
    height = 2_700,
    blocks = blocks.toList(),
  )

  private fun block(text: String, x: Float, y: Float) =
    ScreenTextBlock(text, confidence = 0.99f, centerX = x, centerY = y)
}
