package me.fleey.ppocrv5.call

data class SpeechRequest(
  val text: String,
  val utterance: SpeechUtterance,
)

data class SpeechCompletion(
  val nextSpeech: SpeechRequest? = null,
  val finished: Boolean = false,
  val processingQuery: String? = null,
  val ignored: Boolean = false,
)

class LocalCallTurnCoordinator {
  private data class ActiveTurn(
    val generation: Long,
    val queryToken: Long,
    val query: String,
    var spokenText: String,
    var speechStage: SpeechStage? = SpeechStage.ACKNOWLEDGEMENT,
    var acknowledgementFinished: Boolean = false,
    var operationFinished: Boolean = false,
    var result: String = "",
    val silent: Boolean = false,
  )

  private var activeTurn: ActiveTurn? = null

  @Synchronized
  fun beginSilent(generation: Long, queryToken: Long, query: String) {
    activeTurn = ActiveTurn(generation, queryToken, query, "", speechStage = null, silent = true)
  }

  @Synchronized
  fun completeSilently(generation: Long, queryToken: Long): Boolean {
    val turn = activeTurn ?: return false
    if (!turn.silent || turn.generation != generation || turn.queryToken != queryToken) return false
    activeTurn = null
    return true
  }

  @Synchronized
  fun begin(generation: Long, queryToken: Long, query: String, intent: IntentMatch): SpeechRequest {
    activeTurn = ActiveTurn(
      generation = generation,
      queryToken = queryToken,
      query = query,
      spokenText = intent.acknowledgement,
    )
    return SpeechRequest(
      intent.acknowledgement,
      SpeechUtterance(queryToken, SpeechStage.ACKNOWLEDGEMENT),
    )
  }

  @Synchronized
  fun currentSpokenText(): String = activeTurn?.spokenText.orEmpty()

  @Synchronized
  fun isActive(generation: Long, queryToken: Long): Boolean {
    val turn = activeTurn ?: return false
    return turn.generation == generation && turn.queryToken == queryToken
  }

  @Synchronized
  fun completeOperation(generation: Long, queryToken: Long, result: String): SpeechRequest? {
    val turn = activeTurn?.takeIf {
      it.generation == generation && it.queryToken == queryToken
    } ?: return null
    if (turn.silent || turn.operationFinished) return null
    turn.operationFinished = true
    turn.result = result
    if (!turn.acknowledgementFinished) return null
    return resultRequest(turn)
  }

  @Synchronized
  fun completeSpeech(utterance: SpeechUtterance): SpeechCompletion {
    val turn = activeTurn?.takeIf { it.queryToken == utterance.queryToken }
      ?: return SpeechCompletion(ignored = true)
    if (turn.speechStage != utterance.stage) return SpeechCompletion(ignored = true)
    return when (utterance.stage) {
      SpeechStage.ACKNOWLEDGEMENT -> {
        turn.acknowledgementFinished = true
        if (turn.operationFinished) {
          SpeechCompletion(nextSpeech = resultRequest(turn))
        } else {
          turn.spokenText = ""
          turn.speechStage = null
          SpeechCompletion(processingQuery = turn.query)
        }
      }

      SpeechStage.RESULT -> {
        activeTurn = null
        SpeechCompletion(finished = true)
      }
    }
  }

  @Synchronized
  fun interrupt(): Long? {
    val queryToken = activeTurn?.queryToken ?: return null
    activeTurn = null
    return queryToken
  }

  @Synchronized
  fun clear() {
    activeTurn = null
  }

  private fun resultRequest(turn: ActiveTurn): SpeechRequest {
    turn.spokenText = turn.result
    turn.speechStage = SpeechStage.RESULT
    return SpeechRequest(turn.result, SpeechUtterance(turn.queryToken, SpeechStage.RESULT))
  }
}
