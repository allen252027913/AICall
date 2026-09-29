package me.fleey.ppocrv5.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.StopScreenShare
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun CallScreen(
  state: CallSessionState,
  currentCapture: CaptureHistoryEntry?,
  currentCaptureFile: File?,
  inPictureInPictureMode: Boolean,
  pipEnabled: Boolean,
  speakerInputEnabled: Boolean,
  accessibilityEnabled: Boolean,
  onToggleCall: () -> Unit,
  onStartScreenShare: () -> Unit,
  onStopScreenShare: () -> Unit,
  onAsk: (String) -> Unit,
  onPipEnabledChanged: (Boolean) -> Unit,
  onSpeakerInputEnabledChanged: (Boolean) -> Unit,
  onOpenAccessibilitySettings: () -> Unit,
  onStartWeChatCapture: () -> Unit,
  onCapabilityIssueAction: (LocalCallCapabilityIssue) -> Unit,
  onOpenResults: () -> Unit,
  onOpenSettings: () -> Unit = {},
) {
  var question by remember { mutableStateOf("帮我看看当前屏幕是什么") }
  val colorScheme = MaterialTheme.colorScheme
  val context = androidx.compose.ui.platform.LocalContext.current
  val galleryPermission = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) onAsk("长截图")
    else android.widget.Toast.makeText(context, "未获得相册存储权限，请重试授权", android.widget.Toast.LENGTH_LONG).show()
  }

  Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background) {
    if (inPictureInPictureMode) {
      LocalCallPipPreview(currentCapture, currentCaptureFile, state.screenSummary)
      return@Surface
    }
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("本地 AI 通话", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      Text("sherpa-onnx · PP-OCRv5 · 本地语音与 OCR", color = colorScheme.onSurfaceVariant)
      androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text("设置") }
      Spacer(Modifier.height(24.dp))

      Box(
        modifier = Modifier
          .size(120.dp)
          .background(
            if (state.callActive) colorScheme.primary.copy(alpha = 0.18f) else colorScheme.surfaceVariant,
            CircleShape,
          ),
        contentAlignment = Alignment.Center,
      ) {
        Box(
          modifier = Modifier
            .size(if (state.phase == CallPhase.SPEAKING) 74.dp else 62.dp)
            .background(if (state.callActive) colorScheme.primary else colorScheme.outline, CircleShape),
        )
      }
      Spacer(Modifier.height(12.dp))
      Text(statusText(state), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text(
        if (state.screenSharing) "正在共享屏幕，仅在提问时截取并本地 OCR" else "屏幕未共享",
        color = if (state.screenSharing) colorScheme.secondary else colorScheme.onSurfaceVariant,
      )

      Spacer(Modifier.height(22.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          onClick = onToggleCall,
          colors = ButtonDefaults.buttonColors(
            containerColor = if (state.callActive) colorScheme.error else colorScheme.primary,
            contentColor = if (state.callActive) colorScheme.onError else colorScheme.onPrimary,
          ),
          modifier = Modifier.weight(1f),
        ) {
          Icon(if (state.callActive) Icons.Rounded.CallEnd else Icons.Rounded.Call, null)
          Text(if (state.callActive) " 结束通话" else " 开始通话")
        }
        Button(
          onClick = if (state.screenSharing) onStopScreenShare else onStartScreenShare,
          enabled = state.callActive,
          modifier = Modifier.weight(1f),
        ) {
          Icon(
            if (state.screenSharing) Icons.AutoMirrored.Rounded.StopScreenShare else Icons.AutoMirrored.Rounded.ScreenShare,
            null,
          )
          Text(if (state.screenSharing) " 停止共享" else " 共享屏幕")
        }
      }

      Spacer(Modifier.height(18.dp))
      Button(onClick = onOpenResults, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.History, contentDescription = null)
        Text(" 查看识别记录")
      }

      Spacer(Modifier.height(18.dp))
      CurrentCaptureCard(entry = currentCapture, imageFile = currentCaptureFile, currentOcr = state.screenSummary)
      Spacer(Modifier.height(12.dp))
      InfoCard("你说", state.transcript.ifBlank { "开始通话后直接说话，或用下方输入框验证链路。" })
      Spacer(Modifier.height(12.dp))
      InfoCard("本地回复", state.reply.ifBlank { "回复会在这里显示并由系统 TTS 播放。" })
      state.errorMessage?.let {
        Spacer(Modifier.height(12.dp))
        InfoCard("运行提示", it, colorScheme.errorContainer)
      }
      state.capabilityIssues.forEach { issue ->
        Spacer(Modifier.height(12.dp))
        CapabilityIssueCard(issue = issue, onAction = { onCapabilityIssueAction(issue) })
      }

      Spacer(Modifier.height(18.dp))
      ControlCard(
        icon = { Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = null) },
        title = "画中画小窗",
        description = "离开应用时，继续显示本地识别画面",
        checked = pipEnabled,
        onCheckedChange = onPipEnabledChanged,
      )
      Spacer(Modifier.height(10.dp))
      ControlCard(
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null) },
        title = "将扬声器播放作为输入",
        description = "变更会立即交给本地语音输入服务",
        checked = speakerInputEnabled,
        onCheckedChange = onSpeakerInputEnabledChanged,
      )
      Spacer(Modifier.height(10.dp))
      Button(
        onClick = onOpenAccessibilitySettings,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.surfaceVariant),
      ) {
        Icon(Icons.Rounded.AccessibilityNew, contentDescription = null)
        Text(if (accessibilityEnabled) " 辅助功能已开启" else " 开启辅助功能")
      }

      Spacer(Modifier.height(10.dp))
      Button(
        onClick = onStartWeChatCapture,
        enabled = WeChatAutomationButtonPolicy.isEnabled(
          callActive = state.callActive,
          screenSharing = state.screenSharing,
          accessibilityEnabled = accessibilityEnabled,
          phase = state.phase,
        ),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Rounded.Search, contentDescription = null)
        Text(" 微信商品关键帧")
      }

      Spacer(Modifier.height(10.dp))
      Button(
        onClick = {
          if (android.os.Build.VERSION.SDK_INT <= 28 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            galleryPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
          } else onAsk("长截图")
        },
        enabled = WeChatAutomationButtonPolicy.isEnabled(state.callActive, state.screenSharing, accessibilityEnabled, state.phase),
        modifier = Modifier.fillMaxWidth(),
      ) { Text("微信当前页面长截图") }
      Text("先在微信打开目标页面。点击后 10 秒内切回微信，或直接在微信内说“长截图”。从当前位置持续向下截取到底并保存到相册，合图后联网识别。",
        style = MaterialTheme.typography.bodySmall)

      Spacer(Modifier.height(20.dp))
      OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        label = { Text("本地测试问题") },
        enabled = state.callActive,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
      )
      Spacer(Modifier.height(10.dp))
      Button(
        onClick = { if (question.isNotBlank()) onAsk(question) },
        enabled = state.callActive,
        modifier = Modifier.fillMaxWidth(),
      ) { Text("发送给本地处理链路") }
    }
  }
}

@Composable
fun LocalCallPipPreview(
  entry: CaptureHistoryEntry?,
  imageFile: File?,
  currentOcr: String,
) {
  val hasFrame = imageFile != null
  val previewText = LocalCallPipPolicy.previewText(
    hasFrame = hasFrame,
    ocrText = entry?.ocrText ?: currentOcr,
  )
  when (LocalCallPipPolicy.contentMode(hasFrame)) {
    PipContentMode.TEXT_ONLY -> Box(
      modifier = Modifier.fillMaxSize().padding(8.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = previewText,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
      )
    }

    PipContentMode.FRAME_WITH_TEXT -> Column(modifier = Modifier.fillMaxSize()) {
      LocalCallCachedImage(
        imageFile = requireNotNull(imageFile),
        contentDescription = "当前缓存的屏幕截图",
        maxHeight = 1000.dp,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = previewText,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        maxLines = 3,
      )
    }
  }
}

@Composable
fun LocalCallPipUnavailablePreview() {
  Box(
    modifier = Modifier.fillMaxSize().padding(8.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "屏幕共享已停止",
      style = MaterialTheme.typography.labelSmall,
      textAlign = TextAlign.Center,
      maxLines = 2,
    )
  }
}

@Composable
private fun CurrentCaptureCard(entry: CaptureHistoryEntry?, imageFile: File?, currentOcr: String) {
  InfoCardContainer {
    Text("当前画面", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    if (imageFile != null) {
      LocalCallCachedImage(
        imageFile = imageFile,
        contentDescription = "当前缓存的屏幕截图",
        maxHeight = 260.dp,
      )
      Spacer(Modifier.height(12.dp))
    }
    Text(entry?.ocrText ?: currentOcr.ifBlank { "共享屏幕后提问，即可在这里查看当前 OCR 结果。" })
    Spacer(Modifier.height(8.dp))
    Text(
      entry?.sourcePackage?.ifBlank { "来源应用不可用" } ?: "来源应用会在识别完成后显示",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun CapabilityIssueCard(
  issue: LocalCallCapabilityIssue,
  onAction: () -> Unit,
) {
  InfoCardContainer(MaterialTheme.colorScheme.errorContainer) {
    Text(
      "权限与共享提示",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onErrorContainer,
    )
    Spacer(Modifier.height(6.dp))
    Text(issue.message, color = MaterialTheme.colorScheme.onErrorContainer)
    Spacer(Modifier.height(10.dp))
    Button(onClick = onAction) {
      Text(LocalCallCapabilityRecoveryPolicy.actionLabel(issue))
    }
  }
}

@Composable
private fun ControlCard(
  icon: @Composable () -> Unit,
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon()
      Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
  }
}

@Composable
private fun InfoCard(title: String, content: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface) {
  InfoCardContainer(color) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Text(content, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun InfoCardContainer(
  color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = color),
    shape = RoundedCornerShape(18.dp),
  ) {
    Column(Modifier.padding(18.dp), content = content)
  }
}

private fun statusText(state: CallSessionState): String = when {
  !state.callActive -> "未开始"
  state.phase == CallPhase.PROCESSING -> "正在本地理解…"
  state.phase == CallPhase.SPEAKING -> "正在回复…"
  state.phase == CallPhase.ERROR -> "需要处理"
  else -> "正在聆听…"
}
