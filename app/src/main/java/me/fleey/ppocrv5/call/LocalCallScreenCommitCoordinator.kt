package me.fleey.ppocrv5.call

/**
 * Two-phase persistence gate with a short linearization point.
 *
 * [stage] may perform expensive work, but it must not publish or evict data. [prepareCommit]
 * turns the staged value into an invisible transaction. The final validity check and publication
 * are deliberately one operation: a rejected final check rolls the transaction back before it can
 * become observable or evict an older entry.
 */
class LocalCallScreenCommitCoordinator<S, P, T>(
  private val canStart: () -> Boolean,
  private val stage: () -> S,
  private val canPrepare: () -> Boolean,
  private val prepareCommit: (S) -> P,
  private val commitIfValid: (P) -> T?,
  private val discardStaged: (S) -> Unit,
  private val rollbackPrepared: (P) -> Unit,
  private val afterCommit: (T) -> Unit,
) {
  fun commit() {
    if (!canStart()) return
    val staged = stage()
    if (!canPrepare()) {
      discardStaged(staged)
      return
    }
    val prepared = try {
      prepareCommit(staged)
    } catch (error: Throwable) {
      discardStaged(staged)
      throw error
    }
    val committed = try {
      commitIfValid(prepared)
    } catch (error: Throwable) {
      rollbackPrepared(prepared)
      throw error
    }
    if (committed == null) {
      rollbackPrepared(prepared)
      return
    }
    afterCommit(committed)
  }
}
