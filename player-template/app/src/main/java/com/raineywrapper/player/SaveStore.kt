package com.raineywrapper.player

import android.content.Context
import android.util.Log
import java.io.File

/** App-private storage for in-game saves (SRAM / memory cards) and save states. */
class SaveStore(context: Context) {

    val savesDir = File(context.filesDir, "saves").apply { mkdirs() }
    val systemDir = File(context.filesDir, "system").apply { mkdirs() }
    private val sramFile = File(savesDir, "game.srm")
    private val stateFile = File(savesDir, "game.state")

    init {
        // Builds before linked mode named the save after the ROM asset ("rom.srm").
        val legacy = File(savesDir, "rom.srm")
        if (!sramFile.exists() && legacy.exists()) legacy.renameTo(sramFile)
    }

    fun readSram(): ByteArray? = sramFile.readIfPresent()

    fun writeSram(bytes: ByteArray) = write(sramFile, bytes)

    fun hasState() = stateFile.exists() && stateFile.length() > 0

    fun readState(): ByteArray? = stateFile.readIfPresent()

    fun writeState(bytes: ByteArray) = write(stateFile, bytes)

    private fun File.readIfPresent() = takeIf { it.exists() && it.length() > 0 }?.readBytes()

    @Synchronized
    private fun write(file: File, bytes: ByteArray): Boolean {
        // Some cores report empty data before the game has initialised it; never
        // overwrite a real save with nothing.
        if (bytes.isEmpty()) return false
        return try {
            val tmp = File(savesDir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        } catch (e: Exception) {
            Log.e("RaineyWrapper", "Failed to write ${file.name}", e)
            false
        }
    }
}
