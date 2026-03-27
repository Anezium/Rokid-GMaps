package com.rokid.hud.shared.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

class DiskTileCache(context: Context, private var maxSizeBytes: Long = 100L * 1024 * 1024) {

    companion object {
        private const val TAG = "DiskTileCache"
        private const val CACHE_DIR_NAME = "map_tiles"
    }

    private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
    private val executor = Executors.newSingleThreadExecutor()

    init {
        cacheDir.mkdirs()
    }

    fun get(z: Int, x: Int, y: Int): ByteArray? {
        val file = tileFile(z, x, y)
        if (!file.exists()) return null
        return try {
            // Touch lastModified for LRU
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "Read tile $z/$x/$y failed: ${e.message}")
            null
        }
    }

    fun put(z: Int, x: Int, y: Int, bytes: ByteArray) {
        executor.execute {
            try {
                val file = tileFile(z, x, y)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                evictIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "Write tile $z/$x/$y failed: ${e.message}")
            }
        }
    }

    fun clear() {
        executor.execute {
            try {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                Log.i(TAG, "Cache cleared")
            } catch (e: Exception) {
                Log.w(TAG, "Clear cache failed: ${e.message}")
            }
        }
    }

    fun sizeBytes(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun updateMaxSize(mb: Int) {
        maxSizeBytes = mb.toLong() * 1024 * 1024
        executor.execute { evictIfNeeded() }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun tileFile(z: Int, x: Int, y: Int): File {
        return File(cacheDir, "$z/$x/$y.png")
    }

    private fun evictIfNeeded() {
        try {
            val files = cacheDir.walkTopDown().filter { it.isFile }.toMutableList()
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxSizeBytes) return

            // Sort by lastModified ascending (oldest first)
            files.sortBy { it.lastModified() }
            for (file in files) {
                if (totalSize <= maxSizeBytes) break
                val size = file.length()
                if (file.delete()) {
                    totalSize -= size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eviction failed: ${e.message}")
        }
    }
}
