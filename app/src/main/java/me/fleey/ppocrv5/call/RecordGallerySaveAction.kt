package me.fleey.ppocrv5.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun rememberRecordGallerySaveAction(): (File) -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var saving by remember { mutableStateOf(false) }
  var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
  val save: (File) -> Unit = { file ->
    if (!saving) {
      saving = true
      Toast.makeText(context, "正在保存…", Toast.LENGTH_SHORT).show()
      scope.launch {
        try {
          withContext(Dispatchers.IO) { RecordGalleryExporter(context).save(file) }
          Toast.makeText(context, "已保存到相册「识别记录」", Toast.LENGTH_LONG).show()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Toast.makeText(context, "保存失败：${error.message ?: "请稍后重试"}", Toast.LENGTH_LONG).show()
        } finally { saving = false }
      }
    }
  }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    val path = pendingPath
    pendingPath = null
    if (granted && path != null) save(File(path))
    else if (path != null) Toast.makeText(context, "未获得存储权限，图片未保存", Toast.LENGTH_LONG).show()
  }
  return { file ->
    if (!saving && pendingPath == null) {
      if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,
          Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        pendingPath = file.absolutePath
        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
      } else save(file)
    }
  }
}
