package com.aerowave.audio

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGuardTest {
  @Test
  fun hlsRejectsLocalAndSpecialUseAddresses() {
    for (host in listOf(
      "127.0.0.1",
      "10.0.0.1",
      "172.16.0.1",
      "192.168.0.1",
      "169.254.169.254",
      "100.64.0.1",
      "198.18.0.1",
      "192.0.2.1",
      "::1",
      "fc00::1",
      "fe80::1",
      "2001:db8::1",
    )) {
      assertFalse(host, NetworkGuard.addressAllowed(InetAddress.getByName(host), false))
    }
  }

  @Test
  fun ordinaryStreamsAdmitOnlyLoopbackInAdditionToPublicAddresses() {
    assertTrue(NetworkGuard.addressAllowed(InetAddress.getByName("127.0.0.1"), true))
    assertTrue(NetworkGuard.addressAllowed(InetAddress.getByName("8.8.8.8"), false))
    assertFalse(NetworkGuard.addressAllowed(InetAddress.getByName("192.168.0.1"), true))
  }

  @Test(expected = IllegalArgumentException::class)
  fun hlsRejectsLoopbackLiteralBeforeConnecting() {
    NetworkGuard.validateInitialUrl("http://127.0.0.1/live.m3u8", allowLoopback = false)
  }

  @Test(expected = IllegalArgumentException::class)
  fun localFileUrisAreNeverMediaInputs() {
    NetworkGuard.validateInitialUrl("file:///sdcard/music.mp3", allowLoopback = true)
  }
}
