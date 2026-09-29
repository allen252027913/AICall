package me.fleey.ppocrv5.call

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AndroidSpeechOutput(
  context: Context,
  private val onFinished: (SpeechUtterance) -> Unit,
  private val onError: (SpeechUtterance, String) -> Unit,
) : AutoCloseable {
  private var ready = false
  private val enqueuePolicy = SpeechEnqueuePolicy(TextToSpeech.SUCCESS)
  private val tts: TextToSpeech

  init {
    tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
      override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
          tts.language = Locale.SIMPLIFIED_CHINESE
          tts.setSpeechRate(1.2f)
        }
      }
    })
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = Unit
      override fun onDone(utteranceId: String?) {
        SpeechUtterance.decode(utteranceId)?.let(onFinished)
      }
      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) {
        SpeechUtterance.decode(utteranceId)?.let { utterance ->
          this@AndroidSpeechOutput.onError(utterance, "系统 TTS 播放失败")
          onFinished(utterance)
        }
      }
    })
  }

  fun speak(text: String, utterance: SpeechUtterance) {
    if (!ready) {
      onError(utterance, "系统 TTS 尚未就绪，文字回复已显示")
      onFinished(utterance)
      return
    }
    val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utterance.encode())
    enqueuePolicy.handle(
      resultCode = result,
      utterance = utterance,
      onError = { failed -> onError(failed, "系统 TTS 无法加入播放队列") },
      onFinished = onFinished,
    )
  }

  fun stop() = tts.stop()

  override fun close() {
    tts.stop()
    tts.shutdown()
  }
}
