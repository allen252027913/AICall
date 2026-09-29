package me.fleey.ppocrv5.call

/** Runs on the screen worker; every boundary must honor cancellation. */
class WeChatCaptureBatch(private val count: Int) {
  init { require(count in 1..50) }
  enum class Stop { COMPLETED, SAVE_FAILED, SWIPE_FAILED, CANCELLED }
  data class Result(val saved: Int, val swiped: Int, val stop: Stop)

  fun run(isActive: () -> Boolean, save: () -> Boolean, swipe: () -> Boolean, settle: () -> Boolean): Result {
    var saved = 0
    var swiped = 0
    repeat(count) {
      if (!isActive()) return Result(saved, swiped, Stop.CANCELLED)
      if (!save()) return Result(saved, swiped, Stop.SAVE_FAILED)
      saved++
      if (!isActive()) return Result(saved, swiped, Stop.CANCELLED)
      if (!swipe()) return Result(saved, swiped, Stop.SWIPE_FAILED)
      swiped++
      if (!isActive() || !settle()) return Result(saved, swiped, Stop.CANCELLED)
    }
    return Result(saved, swiped, Stop.COMPLETED)
  }
}
