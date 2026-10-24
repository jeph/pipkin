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

  @Test
  fun thePillNamingWhoAskedLeavesTheAnswersTheirRoom() {
    // The case at font scale 2.0: a two-line title and three choices, in a card held to half the chat.
    val title = 80
    val pill = 44
    val answers = 180
    fun answersShown(asker: Int): Int {
      val max = dialogMaxHeight(height = 800, left = 500, belowDialog = 200, floor = 0, extra = asker)
      // Buttons and gaps take 120 of the card.
      val room = max - 120
      val header = cardHeaderHeight(title + asker, answers, room, title + asker, title + 24, answersScroll = true)
      return minOf(answers, room - header)
    }
    // Without the pill's room on top of the half, the same card would show 156 of the answers' 180.
    assertEquals(answers, answersShown(asker = 0))
    assertEquals(answers, answersShown(asker = pill))
  }
}
