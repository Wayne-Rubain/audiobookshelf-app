package com.audiobookshelf.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CoverImageLoader"

/** Longest edge for covers attached to browse list items (Android Auto lists, queue). */
const val BROWSE_COVER_MAX_PX = 384

/** Longest edge for the now playing cover (notification, lock screen, Android Auto player). */
const val NOW_PLAYING_COVER_MAX_PX = 1024

/** Loads [uri] as a bitmap via Glide, falling back to the app icon if the load fails. */
suspend fun resolveUriAsBitmap(context: Context, uri: Uri): Bitmap? {
  return withContext(Dispatchers.IO) {
    try {
      Glide.with(context)
        .asBitmap()
        .load(uri)
        .override(NOW_PLAYING_COVER_MAX_PX, NOW_PLAYING_COVER_MAX_PX)
        .placeholder(R.drawable.icon)
        .error(R.drawable.icon)
        .submit()
        .get()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load cover bitmap for uri: $uri", e)

      Glide.with(context)
        .asBitmap()
        .load(Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon))
        .submit()
        .get()
    }
  }
}

/**
 * Decodes a local cover image downsampled so its longest edge is at most [maxPx].
 * Covers can be several thousand pixels wide, and a full size decode costs tens of MB per cover.
 * Returns null if the cover cannot be read.
 */
fun decodeLocalCoverBitmap(context: Context, uri: Uri, maxPx: Int): Bitmap? {
  return try {
    if (Build.VERSION.SDK_INT >= 28) {
      val source = ImageDecoder.createSource(context.contentResolver, uri)
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        // Software bitmaps can be parceled to Android Auto and other media controllers
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        val longestEdge = maxOf(width, height)
        if (longestEdge > maxPx) {
          val scale = maxPx.toFloat() / longestEdge
          decoder.setTargetSize(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1)
          )
        }
      }
    } else {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
      }
      var sampleSize = 1
      while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxPx) {
        sampleSize *= 2
      }
      val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
      context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
      }
    }
  } catch (e: Exception) {
    Log.w(TAG, "Failed to decode local cover $uri: ${e.message}")
    null
  } catch (e: OutOfMemoryError) {
    Log.w(TAG, "Out of memory decoding local cover $uri")
    null
  }
}
