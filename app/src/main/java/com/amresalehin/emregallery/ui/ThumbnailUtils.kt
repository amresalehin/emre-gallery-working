package com.amresalehin.emregallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import kotlin.math.max

/**
 * Common thumbnail and image caching utilities for Emre Gallery.
 * Unifies cache keys across all screens (Home, Albums, Favourites, Bin, Archive, Search)
 * so thumbnails are cached once, shared instantly across screens, and used as instantaneous
 * zero-latency placeholders when expanding the fullscreen media viewer.
 */
object ThumbnailUtils {
    const val THUMBNAIL_DIMENSION = 420

    fun mediaThumbnailKey(uri: String): String {
        val clean = if (uri.contains("?")) uri.substringBefore("?") else uri
        return "${clean}_thumb"
    }

    fun buildThumbnailRequest(
        context: Context,
        uri: String,
        widthPx: Int = THUMBNAIL_DIMENSION,
        heightPx: Int = THUMBNAIL_DIMENSION,
        useRgb565: Boolean = false,
        isTrashed: Boolean = false
    ): ImageRequest {
        val baseUri = Uri.parse(uri)
        val effectiveUri = if (isTrashed && baseUri.getQueryParameter("includeTrashed") == null) {
            baseUri.buildUpon()
                .appendQueryParameter("includeTrashed", "1")
                .appendQueryParameter("android:query-arg-match-trashed", "1")
                .build()
        } else {
            baseUri
        }
        val cleanKeyUri = if (uri.contains("?")) uri.substringBefore("?") else uri

        val builder = ImageRequest.Builder(context)
            .data(effectiveUri)
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            // Grid thumbnails should appear on the first available frame; the outline is already
            // visible, so a crossfade only adds perceived latency while rapidly scrolling.
            .crossfade(false)
            .diskCacheKey(mediaThumbnailKey(cleanKeyUri))
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCacheKey(mediaThumbnailKey(cleanKeyUri))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .allowHardware(true)
            .allowRgb565(true)

        if (useRgb565) {
            builder.bitmapConfig(Bitmap.Config.RGB_565)
        }
        return builder.build()
    }

    fun buildViewerRequest(
        context: Context,
        uri: String,
        isVideo: Boolean,
        isTrashed: Boolean = false
    ): ImageRequest {
        val baseUri = Uri.parse(uri)
        val effectiveUri = if (isTrashed && baseUri.getQueryParameter("includeTrashed") == null) {
            baseUri.buildUpon()
                .appendQueryParameter("includeTrashed", "1")
                .appendQueryParameter("android:query-arg-match-trashed", "1")
                .build()
        } else {
            baseUri
        }
        val cleanKeyUri = if (uri.contains("?")) uri.substringBefore("?") else uri

        val builder = ImageRequest.Builder(context)
            .data(effectiveUri)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)

        if (isVideo) {
            builder
                .size(THUMBNAIL_DIMENSION, THUMBNAIL_DIMENSION)
                .precision(Precision.INEXACT)
                .crossfade(true)
                .crossfade(150)
                .diskCacheKey(mediaThumbnailKey(cleanKeyUri))
                .memoryCacheKey(mediaThumbnailKey(cleanKeyUri))
        } else {
            val dm = context.resources.displayMetrics
            val maxDim = max(dm.widthPixels, dm.heightPixels).coerceIn(1600, 2560)
            builder
                .size(maxDim, maxDim)
                .precision(Precision.INEXACT)
                .placeholderMemoryCacheKey(mediaThumbnailKey(cleanKeyUri))
                .diskCacheKey("${cleanKeyUri}:viewer-scaled")
                .memoryCacheKey("${cleanKeyUri}:viewer-scaled")
                .crossfade(true)
                .crossfade(150)
        }
        return builder.build()
    }
}
