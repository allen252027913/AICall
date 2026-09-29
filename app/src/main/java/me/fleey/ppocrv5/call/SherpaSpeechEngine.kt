package me.fleey.ppocrv5.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SherpaSpeechEngine(private val context: Context) : SpeechEngine {
  @Volatile private var running = false
  @Volatile private var audioRecord: AudioRecord? = null
  private val audioEffectsLifecycle = LocalCallAudioEffectsLifecycle()
  private var recognizer: OnlineRecognizer? = null
  private val generation = AtomicInteger()
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "sherpa-streaming-asr").apply { isDaemon = true }
  }

  @Synchronized
  override fun start(
    inputMode: LocalCallAudioInputMode,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (Throwable) -> Unit,
  ) {
    if (running) return
    running = true
    val runId = generation.incrementAndGet()
    executor.execute {
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
      runCatching {
        val activeRecognizer = recognizer ?: createRecognizer().also { recognizer = it }
        val recorder = createAudioRecord(inputMode).also { audioRecord = it }
        try {
          val inputConfiguration = LocalCallAudioInputPolicy.resolve(
            mode = inputMode,
            echoCancelerAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
          )
          val audioEffects = AudioInputEffects.attach(recorder.audioSessionId, inputConfiguration)
          audioEffectsLifecycle.retain(audioEffects)
          try {
            val stream = activeRecognizer.createStream()
            try {
              var previousPartial = ""
              recorder.startRecording()
              val samples16 = ShortArray(SAMPLES_PER_READ)
              while (running && generation.get() == runId) {
                val count = recorder.read(samples16, 0, samples16.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { samples16[it] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (activeRecognizer.isReady(stream)) activeRecognizer.decode(stream)

                val text = activeRecognizer.getResult(stream).text.trim()
                if (text.isNotBlank() && text != previousPartial) {
                  previousPartial = text
                  onPartial(text)
                }
                if (activeRecognizer.isEndpoint(stream)) {
                  activeRecognizer.reset(stream)
                  previousPartial = ""
                  if (text.isNotBlank()) onFinal(text)
                }
              }
            } finally {
              stream.release()
            }
          } finally {
            audioEffectsLifecycle.release(audioEffects)
          }
        } finally {
          runCatching { recorder.stop() }
          recorder.release()
          if (audioRecord === recorder) audioRecord = null
        }
      }.onFailure {
        if (generation.get() == runId) {
          running = false
          onError(it)
        }
      }
    }
  }

  override fun stop() {
    running = false
    generation.incrementAndGet()
    runCatching { audioRecord?.stop() }
    audioEffectsLifecycle.releaseAll()
  }

  private fun createRecognizer(): OnlineRecognizer {
    val config = OnlineRecognizerConfig(
      featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
      modelConfig = checkNotNull(getModelConfig(type = MODEL_TYPE)),
      endpointConfig = getEndpointConfig(),
      enableEndpoint = true,
    )
    return OnlineRecognizer(context.assets, config)
  }

  @SuppressLint("MissingPermission")
  private fun createAudioRecord(inputMode: LocalCallAudioInputMode): AudioRecord {
    val minimum = AudioRecord.getMinBufferSize(
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    val inputConfiguration = LocalCallAudioInputPolicy.resolve(
      mode = inputMode,
      echoCancelerAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
    )
    val audioSource = if (inputConfiguration.preferVoiceCommunicationSource) {
      MediaRecorder.AudioSource.VOICE_COMMUNICATION
    } else {
      MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
    return AudioRecord(
      audioSource,
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      maxOf(minimum * 2, SAMPLES_PER_READ * 2),
    ).also { check(it.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" } }
  }

  override fun close() {
    stop()
    audioEffectsLifecycle.releaseAll()
    executor.shutdownNow()
    executor.awaitTermination(1, TimeUnit.SECONDS)
    recognizer?.release()
    recognizer = null
  }

  private companion object {
    const val SAMPLE_RATE = 16_000
    const val SAMPLES_PER_READ = 1_600
    const val MODEL_TYPE = 9
  }

  private class AudioInputEffects(
    private val echoCanceler: AcousticEchoCanceler?,
    private val noiseSuppressor: NoiseSuppressor?,
  ) : AutoCloseable {
    override fun close() {
      runCatching { echoCanceler?.release() }
      runCatching { noiseSuppressor?.release() }
    }

    companion object {
      fun attach(
        audioSessionId: Int,
        configuration: LocalCallAudioInputConfiguration,
      ): AudioInputEffects {
        val echoCanceler = if (
          configuration.enableEchoCancellation &&
          runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        ) {
          runCatching {
            AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
          }.getOrNull()
        } else {
          null
        }
        val noiseSuppressor = if (
          configuration.enableNoiseSuppression &&
          runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)
        ) {
          runCatching {
            NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
          }.getOrNull()
        } else {
          null
        }
        return AudioInputEffects(echoCanceler, noiseSuppressor)
      }
    }
  }
}
