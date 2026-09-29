package me.fleey.ppocrv5.call

enum class SpeechStage {
  ACKNOWLEDGEMENT,
  RESULT,
}

data class SpeechUtterance(
  val queryToken: Long,
  val stage: SpeechStage,
) {
  fun encode(): String = "$UTTERANCE_PREFIX${stage.name}:$queryToken"

  companion object {
    private const val UTTERANCE_PREFIX = "local-call:"

    fun decode(utteranceId: String?): SpeechUtterance? {
      val payload = utteranceId?.takeIf { it.startsWith(UTTERANCE_PREFIX) }
        ?.removePrefix(UTTERANCE_PREFIX)
        ?: return null
      val segments = payload.split(':')
      if (segments.size != 2) return null
      val stage = runCatching { SpeechStage.valueOf(segments[0]) }.getOrNull() ?: return null
      val queryToken = segments[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
      return SpeechUtterance(queryToken, stage)
    }
  }
}
