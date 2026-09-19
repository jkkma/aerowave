package com.aerowave.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryResponseTest {
  @Test
  fun folderInfoUsesTheRustWireSchema() {
    val response = FolderInfo("content://music/tree", "Wake music", 17).toJsObject()

    assertEquals(setOf("path", "name", "count"), response.keys().asSequence().toSet())
    assertEquals("content://music/tree", response.getString("path"))
    assertEquals("Wake music", response.getString("name"))
    assertEquals(17, response.getInt("count"))
  }

  @Test
  fun trackPickUsesTheRustWireSchema() {
    val response = TrackPick("content://music/track", "morning.mp3", 23).toJsObject()

    assertEquals(setOf("path", "name", "total"), response.keys().asSequence().toSet())
    assertEquals("content://music/track", response.getString("path"))
    assertEquals("morning.mp3", response.getString("name"))
    assertEquals(23, response.getInt("total"))
  }
}
