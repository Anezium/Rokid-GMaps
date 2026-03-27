package com.rokid.hud.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import com.rokid.hud.shared.cache.DiskTileCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class TileManager(context: Context, cacheSizeMb: Int = 100, private val onTileLoaded: () -> Unit) {

    companion object {
        private const val TAG = "TileManager"
        private val TILE_URLS = arrayOf(
            "https://basemaps.cartocdn.com/dark_all/%d/%d/%d@2x.png",
            "https://basemaps.cartocdn.com/dark_all/%d/%d/%d.png",
            "https://tile.openstreetmap.org/%d/%d/%d.png"
        )
        private const val USER_AGENT = "RokidHudMaps/1.0 (Android; Rokid Glasses)"
        private const val CACHE_SIZE = 200
    }

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    private val pending = ConcurrentHashMap<String, Boolean>()
    private val executor = Executors.newFixedThreadPool(4)
    private val diskCache = DiskTileCache(context, cacheSizeMb.toLong() * 1024 * 1024)

    var onTileRequestViaProxy: ((z: Int, x: Int, y: Int, id: String) -> Unit)? = null

    fun getTile(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = "$zoom/$x/$y"
        cache.get(key)?.let { return it }

        if (pending.putIfAbsent(key, true) == null) {
            // Check disk cache first (on background thread)
            executor.submit {
                val diskBytes = diskCache.get(zoom, x, y)
                if (diskBytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(diskBytes, 0, diskBytes.size)
                    if (bmp != null) {
                        cache.put(key, bmp)
                        pending.remove(key)
                        onTileLoaded()
                        return@submit
                    }
                }
                // Not on disk — try proxy or network
                val proxy = onTileRequestViaProxy
                if (proxy != null) {
                    proxy(zoom, x, y, key)
                } else {
                    fetchTile(zoom, x, y, key)
                }
            }
        }
        return null
    }

    fun deliverTile(key: String, base64Data: String?) {
        try {
            pending.remove(key)
            if (base64Data.isNullOrEmpty()) return
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                cache.put(key, bmp)
                // Save to disk cache
                val parts = key.split("/")
                if (parts.size == 3) {
                    diskCache.put(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), bytes)
                }
                onTileLoaded()
            }
        } catch (e: Exception) {
            Log.w(TAG, "deliverTile $key failed: ${e.message}")
            pending.remove(key)
        }
    }

    fun updateCacheSize(mb: Int) {
        diskCache.updateMaxSize(mb)
    }

    private fun fetchTile(zoom: Int, x: Int, y: Int, key: String) {
        try {
            for (template in TILE_URLS) {
                try {
                    val url = URL(String.format(template, zoom, x, y))
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000

                    try {
                        if (conn.responseCode == 200) {
                            val inputBytes = conn.inputStream.readBytes()
                            val bmp = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size)
                            if (bmp != null) {
                                cache.put(key, bmp)
                                diskCache.put(zoom, x, y, inputBytes)
                                onTileLoaded()
                                return
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Tile fetch ($key) from ${template.substringBefore("%d")}: ${e.message}")
                }
            }
        } finally {
            pending.remove(key)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        diskCache.shutdown()
    }
}
