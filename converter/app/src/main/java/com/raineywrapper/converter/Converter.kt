package com.raineywrapper.converter

import android.content.Context
import android.os.StatFs
import com.raineywrapper.patcher.ApkPatcher
import java.io.File

/** ROM -> signed game APK, using the prebuilt player APK bundled in assets/. */
object Converter {
    private const val TEMPLATE_ASSET = "player.apk"
    private const val TEMPLATE_PACKAGE = "com.raineywrapper.template"
    private const val MARGIN = 150L shl 20

    class Request(
        val source: GameSource,
        val title: String,
        val appId: String,
        val image: File?,
        val embed: Boolean,
        /** For external-emulator systems: the chosen emulator first, then fallbacks. */
        val emulators: List<ExternalEmulator> = emptyList(),
    )

    fun interface Status {
        fun update(text: String, progress: Float?)
    }

    fun buildDir(context: Context) = File(context.cacheDir, "build")

    /** Rough free space the conversion needs: the unsigned and signed APKs exist briefly side by side. */
    fun spaceNeeded(source: GameSource, embed: Boolean): Long {
        if (!embed) return MARGIN
        val rom = when (source) {
            is GameSource.Zipped -> source.entrySize
            is GameSource.Sheet -> source.tracks.sumOf { it.second.length() }
            else -> source.file.length()
        }
        return rom * 2 + MARGIN
    }

    fun freeSpace(context: Context) = StatFs(context.cacheDir.absolutePath).availableBytes

    fun convert(context: Context, request: Request, status: Status): File {
        val needed = spaceNeeded(request.source, request.embed)
        val free = freeSpace(context)
        if (free < needed) {
            throw UserError(
                "Not enough free space: converting needs about ${formatSize(needed)}, ${formatSize(free)} available.\n\n" +
                    "Try \"Play from storage\" instead, which doesn't copy the game.",
            )
        }

        status.update("Preparing…", null)
        val template = templateFile(context)
        val dir = buildDir(context).apply {
            deleteRecursively()
            mkdirs()
        }

        val spec = ApkPatcher.Spec().apply {
            templatePackage = TEMPLATE_PACKAGE
            packageName = request.appId
            label = request.title
            versionCode = (System.currentTimeMillis() / 60_000).toInt() // same scheme as the desktop builder
            versionName = "device"
            keepCore = request.source.system.coreLibrary
        }
        status.update("Making the icon…", null)
        spec.replace.putAll(IconMaker.entries(request.title, request.image))
        val (assets, _) = gameAssets(request.source, request.title, request.embed, request.emulators)
        spec.assets.addAll(assets)

        val unsigned = File(dir, "unsigned.apk")
        val signed = File(dir, request.appId + ".apk")
        ApkPatcher.patch(template, unsigned, spec) { done, total ->
            status.update("Packing the game… ${formatSize(done)} of ${formatSize(total)}", if (total > 0) done.toFloat() / total else null)
        }

        status.update("Signing…", null)
        val key = SigningKey.load(context)
        ApkPatcher.sign(unsigned, signed, key.key, key.certs)
        unsigned.delete()
        return signed
    }

    /** The bundled player APK, copied out once per version of this app so ZipFile can read it. */
    private fun templateFile(context: Context): File {
        val file = File(context.filesDir, TEMPLATE_ASSET)
        val marker = File(context.filesDir, "$TEMPLATE_ASSET.version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!file.exists() || !marker.exists() || marker.readText() != version) {
            context.assets.open(TEMPLATE_ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
            marker.writeText(version)
        }
        return file
    }
}
