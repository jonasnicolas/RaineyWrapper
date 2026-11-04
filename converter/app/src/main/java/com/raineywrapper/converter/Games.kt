package com.raineywrapper.converter

import android.content.Context
import com.raineywrapper.patcher.ApkPatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/** Shown to the user as-is. */
class UserError(message: String) : Exception(message)

data class GameSystem(
    val id: String,
    val name: String,
    /** Buildbot core name, e.g. "mgba"; null for systems that use an external emulator app. */
    val core: String?,
    val extensions: List<String>,
    /** Folder names that identify this system when an extension is shared (".chd" for PS1, PS2, Dreamcast). */
    val folders: List<String>,
    /** Disc-based systems default to playing from storage, since their images are huge. */
    val disc: Boolean,
    /** Emulator apps the game is handed to when there's no bundled core (PS2, Dreamcast). */
    val external: List<ExternalEmulator>,
    /** BIOS file names relative to the core's system folder, bundled when found; may end in "*.bin". */
    val bios: List<String>,
    /** The game can't start without a BIOS (PS2). */
    val biosRequired: Boolean,
    /** Shown on the convert screen, e.g. "install the PS3 firmware in aPS3e first". */
    val note: String? = null,
) {
    val coreLibrary get() = core?.let { "lib${it}_libretro_android.so" }
    val isExternal get() = external.isNotEmpty()
}

data class ExternalEmulator(
    val name: String,
    val packageName: String,
    val activity: String,
    val action: String,
    val extra: String?,
    /** "path" to hand over the plain file path instead of a content:// URI. */
    val uri: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("package", packageName).put("activity", activity).put("action", action)
        .apply {
            if (extra != null) put("extra", extra)
            if (uri != null) put("uri", uri)
        }
}

/** systems.json is copied from builder/ at build time, so desktop and device agree on extensions. */
object Systems {
    @Volatile private var cache: List<GameSystem>? = null

    fun all(context: Context): List<GameSystem> = cache ?: run {
        val json = JSONObject(context.assets.open("systems.json").bufferedReader().use { it.readText() })
        val systems = json.getJSONObject("systems")
        systems.keys().asSequence().map { id ->
            val s = systems.getJSONObject(id)
            GameSystem(
                id = id,
                name = s.getString("name"),
                core = s.optString("core").ifEmpty { null },
                extensions = s.getJSONArray("extensions").strings().map { it.lowercase() },
                folders = s.optJSONArray("folders")?.strings()?.map { it.lowercase() } ?: emptyList(),
                disc = s.optBoolean("disc", false),
                external = s.optJSONArray("external")?.let { arr ->
                    List(arr.length()) {
                        val e = arr.getJSONObject(it)
                        ExternalEmulator(
                            e.getString("name"), e.getString("package"), e.getString("activity"),
                            e.optString("action", "android.intent.action.VIEW"), e.optString("extra").ifEmpty { null },
                            e.optString("uri").ifEmpty { null },
                        )
                    }
                } ?: emptyList(),
                bios = s.optJSONArray("bios")?.strings() ?: emptyList(),
                biosRequired = s.optBoolean("biosRequired", false),
                note = s.optString("note").ifEmpty { null },
            )
        }.toList().also { cache = it }
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }

    /**
     * The system for a ROM. When several systems share the extension, the ROM's folder
     * (or its parent's) decides, e.g. "dreamcast/Game.chd"; otherwise the first listed wins.
     * Nintendo discs say in their header whether they're GameCube or Wii, which beats the folder.
     */
    fun forFile(context: Context, ext: String, file: File): GameSystem? {
        var candidates = all(context).filter { ext.lowercase() in it.extensions }
        if (candidates.size <= 1) return candidates.firstOrNull()
        if (candidates.any { it.id == "gamecube" || it.id == "wii" }) {
            val kind = nintendoDisc(file)
            candidates = candidates.filter { it.id == kind }.ifEmpty { candidates }
        }
        if (candidates.any { it.id == "ps3" } && isPs3Disc(file)) return candidates.first { it.id == "ps3" }
        val folders = generateSequence(file.parentFile) { it.parentFile }.take(2).map { it.name.lowercase() }.toList()
        return candidates.firstOrNull { sys -> folders.any { it in sys.folders } } ?: candidates.first()
    }

    fun supportedExtensions(context: Context) = all(context).flatMap { it.extensions }.toSet() + "zip"

    /** PS3 disc images are ISO 9660 with the volume name "PS3VOLUME" (primary volume descriptor, sector 16). */
    private fun isPs3Disc(file: File): Boolean = try {
        java.io.RandomAccessFile(file, "r").use { f ->
            if (f.length() < 0x8028 + 9) return false
            f.seek(0x8028)
            val name = ByteArray(9).also { f.readFully(it) }
            String(name, Charsets.US_ASCII) == "PS3VOLUME"
        }
    } catch (e: Exception) {
        false
    }

    /** "wii" or "gamecube" from the disc header (both use .iso, .rvz, .gcz...), or null. */
    private fun nintendoDisc(file: File): String? = try {
        java.io.RandomAccessFile(file, "r").use { f ->
            fun read(offset: Long, size: Int): ByteArray? {
                if (f.length() < offset + size) return null
                f.seek(offset)
                return ByteArray(size).also { f.readFully(it) }
            }
            fun ByteArray.u32(at: Int, little: Boolean = false): Long {
                val b = (0 until 4).map { this[at + it].toLong() and 0xFF }
                val ordered = if (little) b.reversed() else b
                return ordered.fold(0L) { acc, v -> (acc shl 8) or v }
            }
            fun fromHeader(h: ByteArray?): String? = when {
                h == null -> null
                h.u32(0x18) == 0x5D1C9EA3L -> "wii"
                h.u32(0x1C) == 0xC2339F3DL -> "gamecube"
                else -> null
            }
            val start = read(0, 0x60) ?: return null
            val magic = String(start, 0, 4, Charsets.ISO_8859_1)
            when {
                magic == "WBFS" -> "wii"
                // WIA/RVZ: header 2 starts at 0x48 with the disc type.
                magic == "WIA\u0001" || magic == "RVZ\u0001" -> when (start.u32(0x48)) { 1L -> "gamecube"; 2L -> "wii"; else -> null }
                // GCZ: sub-type 0 = GameCube, 1 = Wii.
                start.u32(0, little = true) == 0xB10BC001L -> when (start.u32(4, little = true)) { 0L -> "gamecube"; 1L -> "wii"; else -> null }
                // CISO: the disc starts after the 32 KB block map.
                magic == "CISO" -> fromHeader(read(0x8000, 0x20))
                else -> fromHeader(start)
            }
        }
    } catch (_: java.io.IOException) {
        null
    }
}

/** Track files that only make sense together with a .cue sheet. */
private val TRACK_EXTENSIONS = setOf("bin", "img", "iso", "wav")
/** Already-compressed formats; deflating them again only costs time. */
private val PRECOMPRESSED = setOf("chd", "pbp", "zip", "7z")

/** Lower-case extension; "Game.xiso.iso" counts as "xiso" so Xbox images are recognised anywhere. */
val File.ext get() = if (name.lowercase().endsWith(".xiso.iso")) "xiso" else extension.lowercase()

/** A ROM the user picked, worked out into what the APK needs. */
sealed class GameSource(val file: File, val system: GameSystem) {
    /** Name the default title is derived from (the ROM inside a zip, not the zip). */
    abstract val displayName: String
    open val canLink get() = true

    class Single(file: File, system: GameSystem) : GameSource(file, system) {
        override val displayName get() = file.name
    }

    class Zipped(file: File, system: GameSystem, val entryName: String, val entrySize: Long) : GameSource(file, system) {
        override val displayName get() = File(entryName).name
        override val canLink get() = false // cores need an extracted file
    }

    /** A .cue or .gdi sheet plus the track files it lists. */
    class Sheet(file: File, system: GameSystem, val tracks: List<Pair<String, File>>) : GameSource(file, system) {
        override val displayName get() = file.name
    }

    companion object {
        fun resolve(context: Context, file: File, systemOverride: GameSystem? = null): GameSource {
            if (!file.canRead()) throw UserError("Can't read ${file.name}.")
            val ext = file.ext
            if (ext == "zip") return resolveZip(context, file, systemOverride)

            val cue = File(file.parentFile, file.nameWithoutExtension + ".cue")
            if (ext in TRACK_EXTENSIONS && cue.exists()) {
                throw UserError("${file.name} is one track of a disc.\n\nPick ${cue.name} instead.")
            }
            val system = systemOverride ?: Systems.forFile(context, ext, file)
                ?: throw UserError("${file.name}: .$ext files aren't supported.")
            if (ext == "cue") return Sheet(file, system, parseSheet(file, CUE_FILES))
            if (ext == "gdi") return Sheet(file, system, parseSheet(file, GDI_FILES))
            return Single(file, system)
        }

        private fun resolveZip(context: Context, file: File, systemOverride: GameSystem?): GameSource {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                if (entries.any { File(it.name).ext == "cue" }) {
                    throw UserError("${file.name} contains a disc image (.cue).\n\nUnzip it first, then pick the .cue file.")
                }
                for (entry in entries) {
                    val system = systemOverride ?: Systems.forFile(context, File(entry.name).ext, file) ?: continue
                    if (system.isExternal) continue
                    return Zipped(file, system, entry.name, entry.size)
                }
            }
            throw UserError("${file.name} doesn't contain a supported ROM.")
        }

        /** `FILE "Track 01.bin" BINARY` */
        private val CUE_FILES = Regex("""^\s*FILE\s+"?(.+?)"?\s+\S+\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        /** `1 0 4 2352 "Track 01.bin" 0` or `1 0 4 2352 track01.bin 0` */
        private val GDI_FILES = Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+(?:"([^"]+)"|(\S+))\s+-?\d+\s*$""", RegexOption.MULTILINE)

        private fun parseSheet(sheet: File, pattern: Regex): List<Pair<String, File>> {
            val refs = pattern.findAll(sheet.readText())
                .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.distinct().toList()
            if (refs.isEmpty()) throw UserError("${sheet.name} doesn't list any tracks.")
            return refs.map { ref ->
                val track = File(sheet.parentFile, ref)
                if (!track.exists()) throw UserError("${sheet.name} needs \"$ref\", which isn't in the same folder.")
                ref to track
            }
        }
    }
}

/** Folders that commonly hold BIOS files, relative to each storage root. */
private val BIOS_FOLDERS = listOf(
    "bios", "BIOS", "Bios", "RetroArch/system", "retroarch/system", "Roms/bios", "ROMs/bios", "ps2/bios", "bios/ps2", "bios/pcsx2/bios",
)

/** PS2 BIOS dumps are 4 MB; anything else matching "*.bin" in a bios folder isn't one. */
private const val PS2_BIOS_SIZE = 4L * 1024 * 1024

/**
 * BIOS files for [system] found on the device: next to the ROM's system folder
 * (e.g. /storage/XXXX/bios when the ROM is in /storage/XXXX/psx) or in a common bios folder.
 */
fun findBios(rom: File, system: GameSystem): Map<String, File> {
    if (system.bios.isEmpty()) return emptyMap()
    val roots = mutableListOf<File>()
    rom.parentFile?.parentFile?.let { roots += it }
    roots += android.os.Environment.getExternalStorageDirectory()
    File("/storage").listFiles()?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }?.let { roots += it }
    rom.parentFile?.let { roots += it } // e.g. ps2/bios next to ps2/game.iso
    val dirs = roots.flatMap { root -> BIOS_FOLDERS.map { File(root, it) } }.filter { it.isDirectory }.distinctBy { it.canonicalPath }
    return system.bios.mapNotNull { name ->
        if (name.endsWith("*.bin")) {
            // Any BIOS dump: model-specific names (scph39001.bin, SCPH-70012.bin, ...).
            val folder = name.removeSuffix("*.bin")
            val candidates = dirs
                .flatMap { dir -> dir.listFiles().orEmpty().sortedBy { it.name.lowercase() } }
                .filter { it.isFile && it.ext == "bin" && !it.name.startsWith(".") && it.length() == PS2_BIOS_SIZE }
            val regional = regionDigits(rom.name)
            val best = candidates.firstOrNull { bios -> modelRegionDigit(bios.name) in regional } ?: candidates.firstOrNull()
            best?.let { (folder + it.name) to it }
        } else {
            dirs.firstNotNullOfOrNull { dir -> File(dir, name).takeIf { it.isFile } ?: File(dir, name.lowercase()).takeIf { it.isFile } }?.let { name to it }
        }
    }.toMap()
}

/** PS2 model numbers end in a region digit: SCPH-39001 is USA, SCPH-30004R Europe, SCPH-10000 Japan. */
private fun modelRegionDigit(biosName: String): Char? =
    Regex("""(\d{4,5})""").find(biosName)?.groupValues?.get(1)?.last()

/** Region digits matching a "(USA)" / "(Europe)" / "(Japan)" tag in the game's file name. */
private fun regionDigits(romName: String): Set<Char> = when {
    romName.contains("(USA", ignoreCase = true) -> setOf('1')
    romName.contains("(Europe", ignoreCase = true) -> setOf('2', '3', '4')
    romName.contains("(Japan", ignoreCase = true) -> setOf('0')
    else -> emptySet()
}

/** What goes into assets/game/: game.json plus (when embedding) the ROM files, plus any BIOS files. */
fun gameAssets(source: GameSource, title: String, embed: Boolean, emulators: List<ExternalEmulator> = emptyList()): Pair<List<ApkPatcher.Asset>, Long> {
    val json = JSONObject()
        .put("title", title)
        .put("system", source.system.id)
        .put("core", source.system.coreLibrary ?: "")
    if (emulators.isNotEmpty()) json.put("external", JSONArray(emulators.map { it.toJson() }))
    val assets = mutableListOf<ApkPatcher.Asset>()

    if (!embed) {
        json.put("rom", source.file.absolutePath).put("linked", true).put("files", JSONArray())
    } else {
        when (source) {
            is GameSource.Single -> {
                val name = "rom." + source.file.ext
                assets += ApkPatcher.Asset(name, source.file.length(), source.file.ext !in PRECOMPRESSED) { source.file.inputStream() }
                json.put("rom", name)
            }
            is GameSource.Zipped -> {
                val name = "rom." + File(source.entryName).ext
                assets += ApkPatcher.Asset(name, source.entrySize, true) { openZipEntry(source.file, source.entryName) }
                json.put("rom", name)
            }
            is GameSource.Sheet -> {
                // Rename tracks to predictable asset names and rewrite the sheet to match.
                var text = source.file.readText()
                source.tracks.forEachIndexed { i, (ref, track) ->
                    val name = "rom" + (if (i == 0) "" else "_%02d".format(i + 1)) + "." + track.ext
                    // Keep the name quoted: some cores' cue parsers (PCSX2) reject unquoted names.
                    text = if (text.contains("\"$ref\"")) text.replace("\"$ref\"", "\"$name\"") else text.replace(ref, "\"$name\"")
                    assets += ApkPatcher.Asset(name, track.length(), true) { track.inputStream() }
                }
                val sheetName = "rom." + source.file.ext
                assets += ApkPatcher.Asset.ofBytes(sheetName, text.toByteArray())
                json.put("rom", sheetName)
            }
        }
        json.put("files", JSONArray(assets.map { it.name() }))
    }
    val total = assets.sumOf { it.size() }
    json.put("totalBytes", total)
    val bios = findBios(source.file, source.system)
    if (bios.isNotEmpty()) {
        json.put("systemFiles", JSONArray(bios.keys.toList()))
        for ((name, file) in bios) assets += ApkPatcher.Asset("system/$name", file.length(), true) { file.inputStream() }
    }
    return (listOf(ApkPatcher.Asset.ofBytes("game.json", json.toString(2).toByteArray())) + assets) to total
}

private fun openZipEntry(file: File, entryName: String): InputStream {
    val zip = ZipFile(file)
    return object : FilterInputStream(zip.getInputStream(zip.getEntry(entryName))) {
        override fun close() {
            super.close()
            zip.close()
        }
    }
}

/** "Mario Kart - Super Circuit (USA) [!].gba" -> "Mario Kart - Super Circuit" (same rules as the desktop builder). */
fun titleFromFileName(name: String): String {
    // ".nkit.iso" and similar double extensions: drop the inner format tag too.
    val stem = name.substringBeforeLast('.').removeSuffix(".nkit").removeSuffix(".xiso")
    val cleaned = stem.replace(Regex("""[(\[].*?[)\]]"""), "").replace('_', ' ')
        .replace(Regex("""\s+"""), " ").trim(' ', '-')
    return cleaned.ifEmpty { stem }
}

/** "Mario Kart - Super Circuit" -> "com.raineywrapper.mario_kart_super_circuit" (same rules as the desktop builder). */
fun appIdFor(title: String): String {
    var part = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    if (part.isEmpty()) part = "game"
    if (!part[0].isLetter()) part = "g$part"
    return "com.raineywrapper.$part"
}
