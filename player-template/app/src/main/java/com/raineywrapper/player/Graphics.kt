package com.raineywrapper.player

import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable

/** Post-processing applied when the frame is drawn to the screen; switchable while playing. */
enum class ScreenFilter(val label: String, val config: ShaderConfig) {
    SHARP("Sharp pixels", ShaderConfig.Sharp),
    SMOOTH("Smooth", ShaderConfig.Default),
    UPSCALE("Upscale (smooths edges)", ShaderConfig.CUT2()),
    CRT("CRT scanlines", ShaderConfig.CRT),
    LCD("LCD grid", ShaderConfig.LCD),
}

/**
 * Internal rendering resolution for 3D systems: the core renders at a multiple of the console's
 * native resolution. Cores read these options at startup, so a change needs a restart.
 */
object InternalResolution {
    class Level(val label: String, val variable: Variable)

    /** Levels per system, native first. Systems without upscaling get an empty list. */
    fun levels(system: String): List<Level> = when (system) {
        // PCSX2 offers 1x, 2x, 4x, 8x; 4x and up is far beyond a handheld GPU.
        "ps2" -> listOf(
            Level("1x (native)", Variable("pcsx2_upscale_multiplier", "1x (Native)")),
            Level("2x", Variable("pcsx2_upscale_multiplier", "2x")),
        )
        "gamecube" -> (1..3).map { Level(if (it == 1) "1x (native)" else "${it}x", Variable("dolphin_efb_scale", "$it")) }
        "dreamcast" -> listOf("640x480", "1280x960", "1920x1440").mapIndexed { i, res ->
            Level(if (i == 0) "1x (native)" else "${i + 1}x", Variable("reicast_internal_resolution", res))
        }
        "psp" -> listOf("480x272", "960x544", "1440x816").mapIndexed { i, res ->
            Level(if (i == 0) "1x (native)" else "${i + 1}x", Variable("ppsspp_internal_resolution", res))
        }
        // Mupen64Plus-Next's default 640x480 is its "native" level (N64 games are mostly 320x240).
        "n64" -> listOf("640x480", "1280x960", "1920x1440").mapIndexed { i, res ->
            Level(if (i == 0) "1x (native)" else "${i + 1}x", Variable("mupen64plus-43screensize", res))
        }
        // PCSX ReARMed's NEON renderer has a single enhanced (2x) mode.
        "psx" -> listOf(
            Level("1x (native)", Variable("pcsx_rearmed_neon_enhancement_enable", "disabled")),
            Level("2x", Variable("pcsx_rearmed_neon_enhancement_enable", "enabled")),
        )
        else -> emptyList()
    }
}
