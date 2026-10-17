package io.jeph.pipkin.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CardHeaderHeightTest {
  @Test
  fun bothPartsKeepTheirHeightWhenTheyFit() {
    assertEquals(100, cardHeaderHeight(100, 48, room = 148, headerFloor = 60, answersFloor = 48, answersScroll = false))
  }

  @Test
  fun aOneLineFieldStaysWholeAndTheHeaderScrolls() {
    // The header gets its floor and all that's left, since the field wants nothing more.
    assertEquals(72, cardHeaderHeight(100, 48, room = 120, headerFloor = 60, answersFloor = 48, answersScroll = false))
    // Below both floors the field still keeps its height, and the header takes what's left.
    assertEquals(32, cardHeaderHeight(100, 48, room = 80, headerFloor = 60, answersFloor = 48, answersScroll = false))
    assertEquals(0, cardHeaderHeight(100, 48, room = 30, headerFloor = 60, answersFloor = 48, answersScroll = false))
  }

  @Test
  fun scrollingPartsSplitWhatIsLeftAboveTheirFloors() {
    assertEquals(94, cardHeaderHeight(300, 400, room = 200, headerFloor = 60, answersFloor = 72, answersScroll = true))
  }

  @Test
  fun aPartThatNeedsLessThanItsShareGivesTheRestAway() {
    assertEquals(50, cardHeaderHeight(50, 400, room = 200, headerFloor = 50, answersFloor = 72, answersScroll = true))
    assertEquals(120, cardHeaderHeight(300, 80, room = 200, headerFloor = 60, answersFloor = 72, answersScroll = true))
  }

  @Test
  fun scrollingPartsSplitTheRoomWhenEvenTheirFloorsDoNotFit() {
    assertEquals(50, cardHeaderHeight(300, 400, room = 100, headerFloor = 60, answersFloor = 72, answersScroll = true))
    assertEquals(0, cardHeaderHeight(300, 400, room = 0, headerFloor = 60, answersFloor = 72, answersScroll = true))
  }
}
