package me.fleey.ppocrv5

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import me.fleey.ppocrv5.call.CaptureSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureSettingsScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test fun invalidCountIsNotSavedAndValidCountIsSubmitted() {
    var saved = ""
    compose.setContent { MaterialTheme { CaptureSettingsScreen(10, { saved = it; true }, {}) } }
    compose.onNodeWithText("微信商品采集次数").performTextReplacement("0")
    compose.onNodeWithText("保存").performClick()
    compose.onNodeWithText("请输入 1–50 的整数").assertExists()
    compose.runOnIdle { assertEquals("", saved) }
    compose.onNodeWithText("微信商品采集次数").performTextReplacement("2")
    compose.onNodeWithText("保存").performClick()
    compose.onNodeWithText("已保存，下次采集生效").assertExists()
    compose.runOnIdle { assertEquals("2", saved) }
  }
}
