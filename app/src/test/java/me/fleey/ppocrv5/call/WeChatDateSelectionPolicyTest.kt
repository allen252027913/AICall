package me.fleey.ppocrv5.call

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatDateSelectionPolicyTest {
  private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

  @Test
  fun `September 11 selects September 12`() {
    assertEquals(
      12,
      WeChatDateSelectionPolicy.tomorrowDayOfMonth(
        millis(year = 2026, month = Calendar.SEPTEMBER, day = 11),
        shanghai,
      ),
    )
  }

  @Test
  fun `last day of month rolls over to day one`() {
    assertEquals(
      1,
      WeChatDateSelectionPolicy.tomorrowDayOfMonth(
        millis(year = 2026, month = Calendar.SEPTEMBER, day = 30),
        shanghai,
      ),
    )
  }

  @Test
  fun `last day of year rolls over to day one`() {
    assertEquals(
      1,
      WeChatDateSelectionPolicy.tomorrowDayOfMonth(
        millis(year = 2026, month = Calendar.DECEMBER, day = 31),
        shanghai,
      ),
    )
  }

  private fun millis(year: Int, month: Int, day: Int): Long =
    GregorianCalendar(shanghai).apply {
      clear()
      set(year, month, day, 12, 0, 0)
    }.timeInMillis
}
