package me.fleey.ppocrv5.call

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
fun RecordImageViewer(imageFile: File, onClose: () -> Unit, onLongPress: () -> Unit) {
  var image by remember(imageFile) { mutableStateOf<ImageBitmap?>(null) }
  var loaded by remember(imageFile) { mutableStateOf(false) }
  var viewport by remember { mutableStateOf(IntSize.Zero) }
  val zoom = remember(imageFile, viewport) { RecordImageZoom() }
  var revision by remember(imageFile, viewport) { mutableIntStateOf(0) }
  LaunchedEffect(imageFile) {
    image = withContext(Dispatchers.IO) { runCatching { decodeSampled(imageFile, 4096, 4096) }.getOrNull() }
    loaded = true
  }
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onClose) { Text("关闭大图", color = Color.White) }
        Spacer(Modifier.weight(1f))
        // Read revision so mutations from pointer input invalidate the rendered transform.
        key(revision) { Text("${(zoom.scale * 100).roundToInt()}%", color = Color.White, modifier = Modifier.padding(16.dp)) }
      }
      Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged { viewport = it }
        .testTag("record-image-viewer")
        .pointerInput(imageFile, viewport, image) {
          detectTransformGestures { centroid, pan, scale, _ ->
            image?.let { bitmap ->
              val fit = LocalCallImageFitPolicy.fitWithin(bitmap.width, bitmap.height, viewport.width, viewport.height)
              zoom.transform(scale, pan.x, pan.y, centroid.x, centroid.y,
                viewport.width.toFloat(), viewport.height.toFloat(), fit.width.toFloat(), fit.height.toFloat())
              revision++
            }
          }
        }
        .pointerInput(imageFile, image) { detectTapGestures(onLongPress = { if (image != null) onLongPress() }) },
        contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
          @Suppress("UNUSED_VARIABLE") val observedRevision = revision
          Image(bitmap, "识别记录大图", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
              scaleX = zoom.scale; scaleY = zoom.scale
              translationX = zoom.x; translationY = zoom.y
            })
        } else Text(if (loaded) "图片已失效或无法读取" else "正在加载…", color = Color.White)
      }
      Text("双指缩放 · 拖动查看 · 长按保存", color = Color.LightGray, modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
    }
  }
}
