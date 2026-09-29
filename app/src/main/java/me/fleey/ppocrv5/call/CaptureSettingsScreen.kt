package me.fleey.ppocrv5.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun CaptureSettingsScreen(initialCount: Int, onSave: (String) -> Boolean, onBack: () -> Unit) {
  var input by rememberSaveable { mutableStateOf(initialCount.toString()) }
  var message by rememberSaveable { mutableStateOf("") }
  var invalid by rememberSaveable { mutableStateOf(false) }
  BackHandler(onBack = onBack)
  Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
      .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      TextButton(onClick = onBack) { Text("返回") }
      Text("设置", style = MaterialTheme.typography.headlineMedium)
      Text("每轮静默识别并保存当前关键帧，保存成功后快速上滑一页。最后一轮也会滑动。")
      OutlinedTextField(
        value = input,
        onValueChange = { input = it; message = ""; invalid = false },
        label = { Text("微信商品采集次数") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        isError = invalid,
        modifier = Modifier.fillMaxWidth(),
      )
      Text("默认 10 次，可填写 1–50 次。识别记录最多保留最近 50 条，超出后会清理最早的记录。")
      Text("此次数只控制商品关键帧采集。长截图会持续向下截取到底，不限制帧数，并另存到相册「微信长截图」。")
      Button(onClick = {
        invalid = WeChatCapturePreference.parse(input) == null
        message = if (invalid) "请输入 1–50 的整数"
          else if (onSave(input)) "已保存，下次采集生效" else "保存失败，请重试"
      }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
      if (message.isNotBlank()) Text(message, color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
  }
}
