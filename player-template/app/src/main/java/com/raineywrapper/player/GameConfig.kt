package com.raineywrapper.player

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Per-game settings, read from assets/game/game.json (written by the builder or converter app). */
data class GameConfig(
    val title: String,
    /** Core library file name inside jniLibs, e.g. "libmgba_libretro_android.so". */
    val core: String,
    /**
     * File the core loads. For embedded games this is an asset name inside assets/game/
     * (e.g. "rom.cue"); for linked games it's an absolute path on device storage.
     */
    val rom: String,
    /** Every embedded file belonging to the game (a .cue plus its .bin tracks, for example). */
    val files: List<String>,
    /** Uncompressed size of [files], for the progress display and free-space check. */
    val totalBytes: Long,
    val system: String,
    /** True when the ROM stays where it is on device storage instead of being inside the APK. */
    val linked: Boolean,
    /** For systems without a bundled core (PS2): installed emulator apps to hand the game to, in order of preference. */
    val external: List<ExternalEmulator>,
    /** BIOS files bundled under assets/game/system/, as paths relative to the core's system directory. */
    val systemFiles: List<String>,
) {
    data class ExternalEmulator(
        val name: String,
        val packageName: String,
        val activity: String,
        val action: String,
        /** Intent extra carrying the game URI (NetherSX2's "bootPath"); null means pass it as the intent data. */
        val extra: String?,
        /** Hand over the plain file path instead of a content:// URI (NetherSX2 checks the file exists by path). */
        val passPath: Boolean,
    )

    companion object {
        private const val ASSET_DIR = "game"
        private const val SPACE_MARGIN = 50L shl 20

        fun load(context: Context): GameConfig {
            val json = context.assets.open("$ASSET_DIR/game.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val rom = obj.getString("rom")
            val files = obj.optJSONArray("files")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: listOf(rom)
            return GameConfig(
                title = obj.getString("title"),
                core = obj.getString("core"),
                rom = rom,
                files = files,
                totalBytes = obj.optLong("totalBytes", 0L),
                system = obj.optString("system", ""),
                linked = obj.optBoolean("linked", false),
                external = obj.optJSONArray("external")?.let { arr ->
                    List(arr.length()) {
                        val e = arr.getJSONObject(it)
                        ExternalEmulator(
                            e.getString("name"), e.getString("package"), e.getString("activity"),
                            e.optString("action", "android.intent.action.VIEW"), e.optString("extra").ifEmpty { null },
                            e.optString("uri") == "path",
                        )
                    }
                } ?: emptyList(),
                systemFiles = obj.optJSONArray("systemFiles")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            )
        }
    }

    /**
     * Copies the core's support files (assets/coredata/<core>/, e.g. PPSSPP's fonts) into the
     * core's system directory. Redone after each install so updated files are picked up.
     */
    fun installCoreData(context: Context, systemDir: File) {
        val coreName = core.removePrefix("lib").removeSuffix("_libretro_android.so")
        val root = "coredata/$coreName"
        if (context.assets.list(root).isNullOrEmpty()) return
        val marker = File(systemDir, ".coredata-$coreName")
        if (marker.exists() && marker.readText() == installStamp(context)) return
        copyAssetTree(context, root, systemDir)
        marker.writeText(installStamp(context))
    }

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetTree(context, "$assetPath/$child", File(target, child))
    }

    /** Copies bundled BIOS files into the core's system directory (once; also for linked games). */
    fun installSystemFiles(context: Context, systemDir: File) {
        for (name in systemFiles) {
            val target = File(systemDir, name)
            if (target.exists()) continue
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            context.assets.open("$ASSET_DIR/system/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        }
    }

    /** Whether the game files are ready to load without copying anything. */
    fun isExtracted(context: Context): Boolean {
        if (linked) return true
        val dir = romDir(context)
        val marker = File(dir, ".version")
        return marker.exists() && marker.readText() == installStamp(context) && files.all { File(dir, it).exists() }
    }

    /**
     * Cores need real file paths, so embedded game files are copied out of the APK once.
     * They're re-copied whenever the APK is reinstalled or updated. [onProgress] gets 0..1.
     */
    fun extractRom(context: Context, onProgress: (Float) -> Unit = {}): File {
        if (linked) {
            val file = File(rom)
            if (!file.exists()) {
                throw IOException("Game file not found:\n$rom\n\nIt may have been moved, renamed or deleted, or its SD card removed.")
            }
            return file
        }
        val dir = romDir(context)
        if (isExtracted(context)) return File(dir, rom)

        dir.listFiles()?.forEach { it.delete() }
        val free = StatFs(dir.absolutePath).availableBytes
        if (totalBytes > 0 && free < totalBytes + SPACE_MARGIN) {
            throw IOException(
                "Not enough storage: the game needs ${totalBytes.toMb()} MB free on first launch, " +
                    "${free.toMb()} MB available.",
            )
        }

        val buffer = ByteArray(1 shl 20)
        var copied = 0L
        for (name in files) {
            val tmp = File(dir, "$name.tmp")
            context.assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (totalBytes > 0) onProgress(copied.toFloat() / totalBytes)
                    }
                }
            }
            tmp.renameTo(File(dir, name))
        }
        File(dir, ".version").writeText(installStamp(context))
        return File(dir, rom)
    }

    private fun romDir(context: Context) = File(context.filesDir, "rom").apply { mkdirs() }

    /** Changes on every install, so a reconverted game with a new ROM is always re-copied. */
    private fun installStamp(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.longVersionCode}-${it.lastUpdateTime}" }

    private fun Long.toMb() = this shr 20
}
