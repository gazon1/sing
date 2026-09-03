package com.singularity.todo

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.memory.MemoryCache

/**
 * Desktop-specific [ImageLoader] configured for a 512 MB heap target.
 *
 * Default Coil3 uses 25% of available heap for bitmap memory cache.
 * On a 512 MB heap that is ~128 MB; here we cap it at 10% (~50 MB),
 * which is sufficient for typical attachment thumbnails and eliminates
 * the risk of Coil reclaiming memory under GC pressure.
 *
 * The disk cache is disabled by default on JVM — desktop attachments
 * are local files, so the filesystem page cache is sufficient.
 */
private fun buildDesktopImageLoader(coilContext: PlatformContext): ImageLoader =
    ImageLoader.Builder(coilContext)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(coilContext, 0.10)
                .build()
        }
        .build()

/**
 * Applies the desktop [ImageLoader] as the composition's singleton.
 *
 * Call this once at the root of the composition tree.
 * All [coil3.compose.AsyncImage] calls in the subtree will use it.
 */
@Composable
fun LocalDesktopImageLoader() {
    setSingletonImageLoaderFactory { coilContext ->
        buildDesktopImageLoader(coilContext)
    }
}
