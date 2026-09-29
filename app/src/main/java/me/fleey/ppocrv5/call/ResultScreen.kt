package me.fleey.ppocrv5.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Displays the app-private screen capture cache without any network image loader. */
@Composable
fun ResultScreen(
  entries: List<CaptureHistoryEntry>,
  imageFileFor: (CaptureHistoryEntry) -> File?,
  onBack: () -> Unit,
  onDelete: (CaptureHistoryEntry) -> Unit,
  deletingEntryId: String? = null,
  deleteError: String? = null,
  onSaveImage: (File) -> Unit,
) {
  var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
  var viewingId by rememberSaveable { mutableStateOf<String?>(null) }
  var menuId by rememberSaveable { mutableStateOf<String?>(null) }
  val pendingDelete = entries.firstOrNull { it.id == pendingDeleteId }
  val viewingFile = entries.firstOrNull { it.id == viewingId }?.let(imageFileFor)
  val menuFile = entries.firstOrNull { it.id == menuId }?.let(imageFileFor)
  BackHandler(onBack = onBack)
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        ResultHeader(onBack = onBack)
      }
      if (deleteError != null) {
        item { Text(deleteError, color = MaterialTheme.colorScheme.error) }
      }
      if (entries.isEmpty()) {
        item {
          EmptyResults()
        }
      } else {
        items(entries, key = CaptureHistoryEntry::id) { entry ->
          ResultEntry(
            entry = entry,
            imageFile = imageFileFor(entry),
            onDelete = { pendingDeleteId = entry.id },
            deleteEnabled = deletingEntryId == null,
            deleting = deletingEntryId == entry.id,
            onOpenImage = { viewingId = entry.id },
            onImageLongPress = { menuId = entry.id },
          )
        }
      }
    }
  }
  if (viewingFile != null) {
    RecordImageViewer(viewingFile, onClose = { viewingId = null }, onLongPress = { menuId = viewingId })
  }
  if (menuFile != null) {
    AlertDialog(
      onDismissRequest = { menuId = null },
      title = { Text("图片操作") },
      text = { Text("保存完整原图到本地相册，识别记录会保留。") },
      confirmButton = {
        TextButton(onClick = { menuId = null; onSaveImage(menuFile) }) { Text("保存到相册") }
      },
      dismissButton = { TextButton(onClick = { menuId = null }) { Text("取消") } },
    )
  }
  if (pendingDelete != null) {
    AlertDialog(
      onDismissRequest = { pendingDeleteId = null },
      title = { Text("删除这条识别记录？") },
      text = {
        Text("将删除 ${formatCapturedAt(pendingDelete.capturedAtMillis)} 的截图和识别文字，删除后无法恢复。")
      },
      confirmButton = {
        TextButton(
          enabled = deletingEntryId == null,
          onClick = {
            pendingDeleteId = null
            onDelete(pendingDelete)
          },
        ) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
      },
    )
  }
}

@Composable
private fun ResultHeader(onBack: () -> Unit) {
  androidx.compose.foundation.layout.Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack) {
      Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回通话")
    }
    Column {
      Text("识别记录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      Text("保存在本机私有缓存中", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun EmptyResults() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(modifier = Modifier.padding(24.dp)) {
      Icon(Icons.Rounded.History, contentDescription = null)
      Spacer(Modifier.height(12.dp))
      Text("还没有识别记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text("共享屏幕后询问当前画面，成功识别的截图和文字会显示在这里。")
    }
  }
}

@Composable
private fun ResultEntry(
  entry: CaptureHistoryEntry,
  imageFile: File?,
  onDelete: () -> Unit,
  deleteEnabled: Boolean,
  deleting: Boolean,
  onOpenImage: () -> Unit,
  onImageLongPress: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          formatCapturedAt(entry.capturedAtMillis),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = onDelete, enabled = deleteEnabled) {
          Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
          Text(if (deleting) "删除中…" else "删除")
        }
      }
      Spacer(Modifier.height(6.dp))
      if (imageFile != null) {
        LocalCallCachedImage(
          imageFile = imageFile,
          contentDescription = "${entry.sourcePackage.ifBlank { "未知来源" }} 的缓存截图",
          maxHeight = 320.dp,
          modifier = Modifier.testTag("record-image-${entry.id}").combinedClickable(
            onClickLabel = "放大图片", onLongClickLabel = "图片操作",
            onClick = onOpenImage, onLongClick = onImageLongPress,
          ),
        )
        Text("点击放大 · 长按保存", style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
      }
      Text(
        entry.sourcePackage.ifBlank { "来源应用不可用" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(10.dp))
      Text(entry.ocrText, style = MaterialTheme.typography.bodyLarge)
    }
  }
}

private fun formatCapturedAt(timestamp: Long): String =
  DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
