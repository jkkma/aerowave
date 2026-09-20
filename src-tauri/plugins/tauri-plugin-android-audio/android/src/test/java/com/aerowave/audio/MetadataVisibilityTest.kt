package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataVisibilityTest {
  @Test
  fun titleChangesStayPrivateWhileDisabledAndRestoreOnEnable() {
    val disabled = PlaybackSnapshot(trackTitle = "First Song")
      .withMetadataEnabled(false)
      .withIncomingTrackTitle("Second Song")

    assertNull(disabled.trackTitle)
    assertEquals("Second Song", disabled.hiddenTrackTitle)

    val enabled = disabled.withMetadataEnabled(true)
    assertEquals("Second Song", enabled.trackTitle)
  }

  @Test
  fun explicitEmptyTitleClearsThePrivateValue() {
    val disabled = PlaybackSnapshot(
      showMetadata = false,
      hiddenTrackTitle = "Previous Song",
    ).withIncomingTrackTitle(null)

    assertNull(disabled.trackTitle)
    assertNull(disabled.hiddenTrackTitle)
    assertNull(disabled.withMetadataEnabled(true).trackTitle)
  }

  @Test
  fun nullInStreamUpdateWhileHiddenDoesNotRestoreThePreviousSong() {
    val state = PlaybackSnapshot(trackTitle = "Previous Song")
      .withMetadataEnabled(false)
      .withIncomingTrackTitle("Current Song")
      .withIncomingTrackTitle(null)

    assertNull(state.hiddenTrackTitle)
    assertNull(state.withMetadataEnabled(true).trackTitle)
  }
}
