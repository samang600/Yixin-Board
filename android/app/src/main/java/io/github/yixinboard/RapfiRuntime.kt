package io.github.yixinboard

import android.content.Context
import android.os.Build
import io.github.yixinboard.engine.Legality
import io.github.yixinboard.engine.RapfiEngine
import io.github.yixinboard.engine.Rule
import io.github.yixinboard.engine.SearchResult
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android glue for [RapfiEngine]: the executable is shipped as lib/<abi>/librapfi.so (extracted to
 * the native library directory, the only place an app may execute binaries from), and the config
 * and network weights are unpacked from assets/rapfi into private storage on first use.
 */
object RapfiRuntime {
    private const val ASSET_DIR = "rapfi"
    private const val MARKER = ".installed"

    @Volatile private var engine: RapfiEngine? = null

    private fun executable(context: Context) = File(context.applicationInfo.nativeLibraryDir, "librapfi.so")

    /** Rapfi is built for arm64-v8a and x86_64 only (it needs SIMD). */
    fun isAvailable(context: Context): Boolean = executable(context).isFile

    /** Search threads: Rapfi scales with cores, but keep a margin for the UI and the little cores. */
    fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    @Synchronized
    private fun engine(context: Context): RapfiEngine {
        engine?.let { return it }
        val dir = install(context.applicationContext)
        return RapfiEngine(executable(context), File(dir, "config.toml")).also { engine = it }
    }

    /** Copies the assets once per installed app version (≈40 MB of weights, stored uncompressed in the APK). */
    private fun install(context: Context): File {
        val dir = File(context.noBackupFilesDir, ASSET_DIR)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val stamp = "$version/${info.lastUpdateTime}"
        val marker = File(dir, MARKER)
        if (marker.isFile && marker.readText() == stamp) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        val assets = context.assets
        for (name in assets.list(ASSET_DIR).orEmpty()) {
            assets.open("$ASSET_DIR/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it, 1 shl 16) }
            }
        }
        marker.writeText(stamp)
        return dir
    }

    /**
     * Runs Rapfi; returns null if it is unavailable or fails, so the caller can fall back to
     * another engine. Must not be called on the main thread (installs files, blocks while thinking).
     */
    fun think(
        context: Context,
        moves: List<Int>,
        size: Int,
        rule: Rule,
        timeMs: Long,
        cancel: AtomicBoolean,
        progress: (SearchResult) -> Unit,
    ): SearchResult? {
        if (!isAvailable(context)) return null
        return try {
            val r = engine(context).think(moves, size, rule, timeMs, threads(), cancel, progress)
            r.takeIf { Legality.isLegal(moves, size, rule, it.move) }
        } catch (e: IOException) {
            android.util.Log.w("RapfiRuntime", "Rapfi failed: ${e.message}")
            null
        }
    }

    /** Stops the engine process in the background (it is restarted on demand). */
    fun shutdown() {
        val e = engine ?: return
        Thread { e.close() }.apply { isDaemon = true; name = "rapfi-shutdown"; start() }
    }
}
