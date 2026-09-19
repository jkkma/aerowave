package com.aerowave.audio

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

internal object NetworkGuard {
  fun client(allowLoopback: Boolean, userAgent: String): OkHttpClient =
    OkHttpClient.Builder()
      // A proxy could resolve or connect on our behalf and evade the address check.
      .proxy(Proxy.NO_PROXY)
      .dns(PublicDns(allowLoopback))
      .addNetworkInterceptor(GuardInterceptor(allowLoopback, userAgent))
      .build()

  fun validateInitialUrl(url: String, allowLoopback: Boolean) {
    val uri = try {
      URI(url)
    } catch (error: Exception) {
      throw IllegalArgumentException("The station URL is invalid", error)
    }
    validateDestination(uri.scheme, uri.host, allowLoopback)
  }

  fun isLoopbackUrl(url: String): Boolean {
    return try {
      val host = URI(url).host?.trim('[', ']')?.trimEnd('.')?.lowercase() ?: return false
      host == "localhost" || host.endsWith(".localhost") ||
        ((host.contains(':') || host.matches(Regex("[0-9.]+"))) &&
          InetAddress.getByName(host).isLoopbackAddress)
    } catch (_: Exception) {
      false
    }
  }

  private fun validateDestination(scheme: String?, host: String?, allowLoopback: Boolean) {
    if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
      throw IllegalArgumentException("Only HTTP and HTTPS radio streams are supported")
    }

    val normalized = host.trim('[', ']').trimEnd('.').lowercase()
    if (normalized == "localhost" || normalized.endsWith(".localhost")) {
      if (!allowLoopback) throw IllegalArgumentException("HLS must use a public network address")
      return
    }

    if (normalized.contains(':') || normalized.matches(Regex("[0-9.]+"))) {
      val address = try {
        InetAddress.getByName(normalized)
      } catch (error: Exception) {
        throw IllegalArgumentException("The station URL has an invalid address", error)
      }
      if (!addressAllowed(address, allowLoopback)) {
        throw IllegalArgumentException("The station URL does not lead to an allowed address")
      }
    }
  }

  internal fun addressAllowed(address: InetAddress, allowLoopback: Boolean): Boolean {
    if (address.isLoopbackAddress) return allowLoopback
    if (address is Inet4Address) return publicIpv4(address)
    if (address is Inet6Address) {
      val bytes = address.address
      if (bytes.take(10).all { it.toInt() == 0 } &&
        (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
      ) {
        return publicIpv4(InetAddress.getByAddress(bytes.copyOfRange(12, 16)) as Inet4Address)
      }
      val first = bytes[0].toInt() and 0xff
      val second = bytes[1].toInt() and 0xff
      val publicUnicast = (first and 0xe0) == 0x20
      val documentation = first == 0x20 && second == 0x01 &&
        (bytes[2].toInt() and 0xff) == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8
      return publicUnicast && !documentation
    }
    return false
  }

  private fun publicIpv4(address: Inet4Address): Boolean {
    val bytes = address.address.map { it.toInt() and 0xff }
    val first = bytes[0]
    val second = bytes[1]
    val documentation =
      (first == 192 && second == 0 && bytes[2] == 2) ||
        (first == 198 && second == 51 && bytes[2] == 100) ||
        (first == 203 && second == 0 && bytes[2] == 113)
    return !address.isSiteLocalAddress &&
      !address.isLinkLocalAddress &&
      !address.isMulticastAddress &&
      !address.isAnyLocalAddress &&
      !documentation &&
      first != 0 &&
      first < 224 &&
      !(first == 100 && (second and 0xc0) == 64) &&
      !(first == 198 && (second and 0xfe) == 18) &&
      !(first == 255 && second == 255 && bytes[2] == 255 && bytes[3] == 255)
  }

  private class PublicDns(private val allowLoopback: Boolean) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
      val addresses = Dns.SYSTEM.lookup(hostname)
      if (addresses.isEmpty() || addresses.any { !addressAllowed(it, allowLoopback) }) {
        throw UnknownHostException("$hostname resolves to a non-public address")
      }
      return addresses
    }
  }

  private class GuardInterceptor(
    private val allowLoopback: Boolean,
    private val userAgent: String,
  ) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
      val request = chain.request()
      try {
        validateDestination(request.url.scheme, request.url.host, allowLoopback)
      } catch (error: IllegalArgumentException) {
        throw IOException(error.message, error)
      }
      // A network interceptor runs again after each redirect, so every target is checked.
      return chain.proceed(
        request.newBuilder()
          .header("User-Agent", userAgent)
          .header("Icy-MetaData", "1")
          .build(),
      )
    }
  }
}
