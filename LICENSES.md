# Licenses

## Components bundled into generated APKs

Each APK contains LibretroDroid plus only the one core its game needs.

| Component | Version | License | Source |
|---|---|---|---|
| LibretroDroid (RaineyWrapper fork of 0.14.0 with Vulkan support, in `libretrodroid/`) | 0.14.0 + changes | **GPL-3.0-or-later** | https://github.com/Swordfish90/LibretroDroid |
| mGBA libretro core (GBA / GB / GBC games) | buildbot nightly | MPL-2.0 | https://github.com/libretro/mgba |
| PCSX ReARMed libretro core (PS1 games) | buildbot nightly | GPL-2.0-or-later | https://github.com/libretro/pcsx_rearmed |
| Snes9x libretro core (SNES games) | buildbot nightly | **Non-commercial** custom license | https://github.com/libretro/snes9x |
| Genesis Plus GX libretro core (Genesis / Master System / Game Gear) | buildbot nightly | **Non-commercial** custom license | https://github.com/libretro/Genesis-Plus-GX |
| PPSSPP libretro core + PPSSPP system files (PSP games) | buildbot nightly | GPL-2.0-or-later | https://github.com/hrydgard/ppsspp |
| Flycast libretro core (Dreamcast games) | buildbot nightly | GPL-2.0-or-later | https://github.com/flyinghead/flycast |
| Dolphin libretro core + Dolphin system files (GameCube and Wii games) | buildbot nightly | GPL-2.0-or-later | https://github.com/libretro/dolphin |
| PCSX2 libretro core, parallel-ps2 (PS2 games) | buildbot nightly | GPL-3.0 | https://github.com/libretro/parallel-ps2 |
| Mupen64Plus-Next libretro core, GLES3 build (N64 games) | buildbot nightly | GPL-2.0 | https://github.com/libretro/mupen64plus-libretro-nx |
| AndroidX (activity, core, lifecycle) | see `player-template/app/build.gradle.kts` | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin standard library / coroutines | (transitive) | Apache-2.0 | https://github.com/JetBrains/kotlin |

## Systems played in an external emulator app

Xbox game APKs contain no emulator. They open the game in **hakuX** (com.rfandango.haku_x), which must
be installed separately. hakuX is closed-source, so it can't be bundled; its license doesn't apply to
the generated APKs.

## The RaineyWrapper converter app (`converter/`)

The converter carries the player APK (everything above, with all cores) and adds:

| Component | Version | License | Source |
|---|---|---|---|
| ARSCLib (binary manifest editing) | 1.4.0 | Apache-2.0 | https://github.com/REAndroid/ARSCLib |
| apksig (APK signing) | 9.5.0 | Apache-2.0 | https://android.googlesource.com/platform/tools/apksig |
| Bouncy Castle (bcprov, bcpkix) | 1.86 | MIT | https://www.bouncycastle.org |

Because it contains the GPL player plus non-commercial cores, the converter APK is personal-use only too.

## Build-time tools (not shipped inside the APK)

| Tool | License |
|---|---|
| Pillow (icon generation in `builder/`) | MIT-CMU (HPND) |
| Gradle, Android Gradle Plugin, Android SDK | Apache-2.0 / Android SDK License |
| Eclipse Temurin JDK 17 | GPL-2.0 with Classpath Exception |

## Cores planned for later systems

Check these again when they're added, because licenses vary a lot between cores.

| System | Core | License | Note |
|---|---|---|---|
| NES | FCEUmm | GPL-2.0 | |

## What this means for generated APKs

- **Personal use (building and installing on your own devices):** none of these
  licenses place any obligations on you.
- **Distribution:** LibretroDroid is GPL-3.0, so every APK built from this template
  is a combined work under GPL-3.0. If you give an APK to anyone, you must also give
  them (or offer in writing) the complete corresponding source: this repo plus the
  exact core sources. The recipient also gets the right to redistribute it. mGBA's
  MPL-2.0 and PCSX ReARMed's GPL-2.0-or-later are both compatible with GPL-3.0. Mupen64Plus-Next
  bundles several GPL-2.0 projects (mupen64plus, GLideN64, ...). Any part that is GPL-2.0-*only*
  can't be combined with GPL-3.0, so check each one before distributing an N64 APK.
- **Cores with non-commercial licenses** (Snes9x, Genesis Plus GX) can't be combined
  with GPL code and then distributed. That would break either the GPL or the
  non-commercial terms. Treat APKs that contain those cores as personal-use only.
- **ROMs are separate from all of the above.** An APK that bundles a commercial
  game is a copy of that game. Sharing it is copyright infringement, whatever the
  code licenses say. Only homebrew whose license allows it is shareable.
- The signing keystore (`~/.raineywrapper/`, and its copy inside the converter app) is yours alone. Never share it.
