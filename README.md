<p align="center"><img src="App image.png" width="180" alt="RaineyWrapper icon"></p>

# RaineyWrapper

Turn your game backups into standalone Android apps. Each game gets its own icon on your
home screen and boots straight into the game: no frontend, no menus.

Made for Android gaming handhelds (Retroid, AYN Odin, Anbernic and similar), and works on any
64-bit Android 8.0+ device. Games are converted **on the device itself** with the RaineyWrapper
app, or on a computer with the command-line builder.

> No games, BIOS files or other copyrighted content are included. Use your own legally
> obtained game backups or homebrew.

## Supported systems

| System | Emulator inside each game app | Files |
|---|---|---|
| Game Boy Advance | mGBA | `.gba` |
| Game Boy / Game Boy Color | mGBA | `.gb .gbc .sgb` |
| Super Nintendo | bsnes | `.sfc .smc .swc .fig` |
| Genesis / Mega Drive | ClownMDEmu | `.md .gen .smd .bin` |
| Master System / Game Gear | SMS Plus GX | `.sms .gg` |
| PlayStation | PCSX ReARMed | `.cue .chd .pbp` |
| Nintendo 64 | Mupen64Plus-Next | `.z64 .n64 .v64` |
| PSP | PPSSPP | `.iso .cso .pbp .chd` |
| Dreamcast | Flycast | `.chd .cdi .gdi .cue` |
| GameCube | Dolphin | `.iso .rvz .gcm .gcz .ciso` |
| Wii | Dolphin | `.wbfs .iso .rvz .wia .gcz .ciso` |
| PlayStation 2 (needs your own BIOS) | PCSX2 (parallel-ps2, Vulkan) | `.iso .chd .cso .cue` |
| Xbox | opens in [hakuX](https://heldgames.com/guides/hakux-xbox-android-handhelds), installed separately | `.xiso.iso`, or `.iso` in an `xbox` folder |

Cartridge games can also be inside `.zip` files.

The emulators listed are the ones in the released APK. Builds made with `./raineywrapper converter`
without `--public` use Snes9x for Super Nintendo and Genesis Plus GX for Genesis, Master System
and Game Gear instead; those two are licensed for non-commercial use only, so they aren't in the
released APK.

## Install

1. Download the APK from the [Releases](../../releases) page and open it on your device.
2. Allow your file manager or browser to install apps if Android asks.
3. Open RaineyWrapper and allow file access so it can find your games.

## Converting a game

1. Browse to a game (d-pad + A, or touch).
2. Check the title and icon. A cover image with the same name as the game, next to it or in an
   `images`, `covers` or `boxart` folder, is used automatically.
3. Choose **Put the game inside the app** (works on its own) or **Play from where the file is
   now** (small app; keep the file in place). Disc-based systems default to the second.
4. Tap **Convert and install**.

If Google Play Protect says "App scan recommended", tap **More details → Install without
scanning**. ("Scan app" uploads the whole game file to Google.)

### In-game menu

Press **Back** or **Start + Select** for save/load state, reset, screen filters (sharp, smooth,
upscale, CRT, LCD), internal resolution for 3D systems, the Wii controller type (Wii Remote +
Nunchuk, Classic Controller or sideways Wii Remote), an FPS counter, touch controls and quit.

Touch controls include analog sticks for systems that have them: the left stick for N64, PSP,
Dreamcast, GameCube, Wii and PS2, and a right stick for the N64 C-buttons, GameCube C-stick,
Wii pointer and PS2.

Games always run at their own speed (60 or 50 fps), even on screens that switch between 60, 90
and 120 Hz.

### Tips

- Put games in folders named after their system (`psx`, `ps2`, `dreamcast`, `gamecube`, …) so
  shared formats like `.chd` and `.iso` are detected correctly. (GameCube and Wii discs are told
  apart automatically.) You can also pick the system by hand.
- PS2 needs a BIOS dumped from your own console: put the 4 MB `.bin` in a `bios` folder (for
  example `ps2/bios`) and RaineyWrapper bundles the one matching the game's region.
- A Dreamcast BIOS in `bios/dc/` is used automatically when present (optional).

## Building from source

Requirements: macOS or Linux, JDK 17, Android SDK (platform 37, build-tools 37), NDK
29.0.14206865, CMake 3.22.1, Python 3.

```sh
cores/fetch_cores.sh mgba pcsx_rearmed snes9x genesis_plus_gx mupen64plus_next_gles3 ppsspp dolphin flycast pcsx2
./raineywrapper doctor                 # checks the toolchain and says what's missing
./raineywrapper converter --public     # build the RaineyWrapper app (distributable cores)
./raineywrapper package                # release folder + zip on the Desktop
./raineywrapper build game.gba --install   # convert one game on the computer
```

Experimental: `./raineywrapper build game.gba --target mac --install` makes a standalone macOS app
(RetroArch inside, one game per app). 2D systems work; PS2 doesn't run on Intel Macs yet.

The first build creates a signing key in `~/.raineywrapper/`. Keep it: updates only install
over apps signed with the same key.

### Project layout

| Folder | What it is |
|---|---|
| `player-template/` | The game app (Kotlin): loads one game, menu, touch controls, saves |
| `libretrodroid/` | Fork of [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) with Vulkan support, GL state isolation and other fixes for hardware-rendered cores |
| `converter/` | The RaineyWrapper Android app; `patcher/` turns the player into a game APK on the device |
| `builder/` | Python command-line builder and `systems.json`, the list of systems and cores |
| `cores/` | Script that downloads emulator cores from the libretro buildbot |

## License

RaineyWrapper is free software under the [GNU GPL v3.0](LICENSE). It includes LibretroDroid
(GPL-3.0) and the emulator cores listed in [LICENSES-public.md](LICENSES-public.md), each under
its own license.

Game apps you create contain the game you converted. Sharing an app made from a commercial game
is sharing a copy of that game, so only share apps made from homebrew whose license allows it.
