package me.fleey.ppocrv5.call

import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatLongScreenshotCommandTest {
  @Test fun `long screenshot command selects current page capture rather than navigation`() {
    for (query in listOf("长截图", "帮我长截图", "长，截，图！", "微信长截图")) {
      assertEquals("WECHAT_LONG_SCREENSHOT", LocalCallTurnPolicy().match(query)?.action?.name)
    }
  }
}
