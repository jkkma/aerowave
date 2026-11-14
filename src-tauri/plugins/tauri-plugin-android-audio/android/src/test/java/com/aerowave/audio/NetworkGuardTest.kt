package com.aerowave.audio

import java.net.InetAddress
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
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

  @Test fun nativeHttpUsesExplicitChildCredentialsAndKeepsCredentialsOnTheirOrigin() {
    val root = MockWebServer()
    val child = MockWebServer()
    val rootCredential = Credentials.basic("root", "pass")
    val childCredential = Credentials.basic("child", "key")
    root.start(InetAddress.getByName("127.0.0.1"), 0)
    child.start(InetAddress.getByName("127.0.0.1"), 0)
    try {
      val rootUrl = root.url("/").newBuilder().host("127.0.0.1").build()
      val childUrl = child.url("/").newBuilder().host("127.0.0.1").build()
      fun serve(server: MockWebServer, routes: Map<String, Pair<String?, String?>>) {
        server.dispatcher = object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val (authorization, redirect) = routes[request.path] ?: return MockResponse().setResponseCode(404)
            if (request.getHeader("Authorization") != authorization) return MockResponse().setResponseCode(403)
            return if (redirect == null) MockResponse().setResponseCode(204)
            else MockResponse().setResponseCode(302).addHeader("Location", redirect)
          }
        }
      }
      serve(root, mapOf("/manifest" to Pair(rootCredential, null), "/override" to Pair(childCredential, null),
        "/redirect" to Pair(rootCredential, childUrl.resolve("plain").toString())))
      serve(child, mapOf("/plain" to Pair(null, null), "/explicit" to Pair(childCredential, childUrl.resolve("sibling").toString()),
        "/sibling" to Pair(childCredential, null), "/back" to Pair(childCredential, rootUrl.resolve("manifest").toString())))
      val credentialOrigin = rootUrl.newBuilder().username("root").password("pass").build()
      val client = NetworkGuard.client(true, "Aerowave/test", credentialOrigin)
      val override = rootUrl.newBuilder().username("child").password("key").build()
      val explicit = childUrl.newBuilder().username("child").password("key").build()
      for (url in listOf(rootUrl.resolve("manifest")!!, rootUrl.resolve("redirect")!!,
        childUrl.resolve("plain")!!, override.resolve("override")!!,
        explicit.resolve("explicit")!!, explicit.resolve("back")!!)) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
          assertEquals(response.request.url.encodedPath, 204, response.code)
        }
      }
    } finally {
      child.shutdown()
      root.shutdown()
    }
  }
}
