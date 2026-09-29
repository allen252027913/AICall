package me.fleey.ppocrv5.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallSessionStore {
  private val machine = CallStateMachine()
  private val mutableState = MutableStateFlow(machine.state)
  val state = mutableState.asStateFlow()

  @Synchronized
  fun dispatch(event: CallEvent) {
    mutableState.value = machine.reduce(event)
  }
}
