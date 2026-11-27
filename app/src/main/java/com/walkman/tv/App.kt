package com.walkman.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.walkman.tv.di.AppContainer
import com.whl.quickjs.android.QuickJSLoader

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Load the QuickJS native lib once for the whole process (custom-source engine).
        QuickJSLoader.init()
        container = AppContainer(this)
        // ExoPlayer must be created on the main thread.
        container.initPlayback()
        container.bootstrap()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                // Keep memory cache modest for 1-2GB RAM Android TV boxes to avoid GC jank.
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil_covers"))
                .maxSizeBytes(50L * 1024 * 1024)
                .build()
        }
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    companion object {
        lateinit var instance: App
            private set
        lateinit var container: AppContainer
            private set
    }
}
