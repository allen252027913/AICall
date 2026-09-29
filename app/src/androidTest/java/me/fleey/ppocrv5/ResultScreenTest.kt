package me.fleey.ppocrv5

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertTrue
import me.fleey.ppocrv5.call.CaptureHistoryEntry
import me.fleey.ppocrv5.call.ResultScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ResultScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test fun imageOpensZoomsAndLongPressSavesOriginalFile() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val file = File.createTempFile("viewer-test-", ".jpg", context.cacheDir)
    val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bitmap.recycle()
    val entry = CaptureHistoryEntry("viewer-test", 1L, file.name, "测试图片", "com.tencent.mm")
    var saved: File? = null
    try {
      compose.setContent { MaterialTheme { ResultScreen(listOf(entry), { file }, {}, {}, onSaveImage = { saved = it }) } }
      compose.onNodeWithTag("record-image-viewer-test").performClick()
      compose.onNodeWithTag("record-image-viewer").assertExists()
      compose.waitUntil(5_000L) {
        compose.onAllNodesWithContentDescription("识别记录大图").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag("record-image-viewer").performTouchInput {
        pinch(Offset(center.x - 40f, center.y), Offset(center.x + 40f, center.y),
          Offset(center.x - 160f, center.y), Offset(center.x + 160f, center.y), 400L)
      }
      compose.onNodeWithText("100%").assertDoesNotExist()
      compose.onNodeWithTag("record-image-viewer").performTouchInput { longClick() }
      compose.onNodeWithText("保存到相册").performClick()
      compose.runOnIdle { assertEquals(file, saved); assertTrue(file.exists()) }
      compose.onNodeWithText("关闭大图").performClick()
      compose.onNodeWithText("测试图片").assertExists()
      compose.onNodeWithTag("record-image-viewer-test").performTouchInput { longClick() }
      compose.onNodeWithText("取消").performClick()
      compose.onNodeWithTag("record-image-viewer").assertDoesNotExist()
    } finally { file.delete() }
  }

  @Test
  fun deletionRequiresConfirmationAndShowsEmptyStateAfterLastRecord() {
    val entry = CaptureHistoryEntry("test", 1L, "test.jpg", "测试商品识别文字", "com.tencent.mm")
    val entries = mutableStateOf(listOf(entry))
    var deletions = 0
    compose.setContent {
      MaterialTheme {
        ResultScreen(
          entries = entries.value,
          onSaveImage = {},
          imageFileFor = { null },
          onBack = {},
          onDelete = {
            assertEquals(entry, it)
            deletions++
            entries.value = emptyList()
          },
        )
      }
    }
    compose.onNodeWithText("删除").performClick()
    compose.onNodeWithText("删除这条识别记录？").assertExists()
    compose.onNodeWithText("取消").performClick()
    compose.runOnIdle { assertEquals(0, deletions) }
    compose.onNodeWithText("测试商品识别文字").assertExists()
    compose.onNodeWithText("删除").performClick()
    compose.onNodeWithText("确认删除").performClick()
    compose.onNodeWithText("还没有识别记录").assertExists()
    compose.onNodeWithText("测试商品识别文字").assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, deletions) }
  }
}
