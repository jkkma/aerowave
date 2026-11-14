package com.aerowave.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class RadioMediaSourceFactory private constructor(
  private val localMedia: DefaultMediaSourceFactory,
  private val local: DataSource.Factory,
  private val network: (MediaItem) -> DataSource.Factory,
  private val createFactory: (DataSource.Factory) -> DefaultMediaSourceFactory,
) : MediaSource.Factory by localMedia {
  internal fun dataSource(mediaItem: MediaItem): DataSource.Factory {
    val configuration = requireNotNull(mediaItem.localConfiguration)
    // Only app-selected document tracks and alarm tones need local data sources.
    // A station's factory stays HTTP-only for every child URI it loads.
    val localItem = Util.inferContentTypeForUriAndMimeType(configuration.uri, configuration.mimeType) ==
      C.CONTENT_TYPE_OTHER && when (configuration.uri.scheme) {
        "content", "android.resource", "file" -> true
        else -> false
      }
    return if (localItem) local else network(mediaItem)
  }

  override fun createMediaSource(mediaItem: MediaItem): MediaSource =
    createFactory(dataSource(mediaItem)).createMediaSource(mediaItem)

  companion object {
    fun create(
      context: Context,
      userAgent: String,
      allowRelay: Boolean,
      extractors: ExtractorsFactory,
      loadErrors: LoadErrorHandlingPolicy? = null,
      diagnostics: AlarmStartupDiagnostics? = null,
    ): RadioMediaSourceFactory {
      fun factory(dataSource: DataSource.Factory): DefaultMediaSourceFactory =
        DefaultMediaSourceFactory(dataSource, extractors).apply {
          if (loadErrors != null) setLoadErrorHandlingPolicy(loadErrors)
        }
      fun networkFactory(item: MediaItem? = null): DataSource.Factory {
        val configuration = item?.localConfiguration
        val address = configuration?.uri?.toString().orEmpty()
        val loopback = allowRelay && configuration != null &&
          Util.inferContentTypeForUriAndMimeType(configuration.uri, configuration.mimeType) == C.CONTENT_TYPE_OTHER &&
          NetworkGuard.isLoopbackUrl(address)
        val credentialOrigin = address.toHttpUrlOrNull()?.takeIf { NetworkGuard.basicAuthorization(it) != null }
        val client = NetworkGuard.client(loopback, userAgent, credentialOrigin)
        val network = OkHttpDataSource.Factory(diagnostics?.withHttpEvents(client) ?: client)
          .setUserAgent(userAgent)
          .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
        return diagnostics?.withTransferEvents(network) ?: network
      }
      val local = DefaultDataSource.Factory(context, networkFactory())
      return RadioMediaSourceFactory(factory(local), local, ::networkFactory, ::factory)
    }
  }
}
