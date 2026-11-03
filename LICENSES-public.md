# RaineyWrapper: licenses (public release)

This is the license notice for the **public** build of RaineyWrapper (`./raineywrapper converter --public`).
It differs from the personal build: Snes9x and Genesis Plus GX use non-commercial licenses that can't
be distributed together with GPL code, so the public build uses GPL-compatible emulators instead.

RaineyWrapper as a whole, and every game app it creates, is distributed under the
**GNU General Public License v3.0**, because it includes LibretroDroid (GPL-3.0). The complete
source code is in `RaineyWrapper-source.zip` alongside this file.

## Inside the RaineyWrapper app and the game apps it makes

| Component | Used for | License | Source |
|---|---|---|---|
| RaineyWrapper (converter, player, builder) | everything | GPL-3.0 | `RaineyWrapper-source.zip` |
| LibretroDroid 0.14.0, modified with Vulkan support (source in `libretrodroid/`) | running emulator cores | GPL-3.0-or-later | https://github.com/Swordfish90/LibretroDroid/tree/0.14.0 |
| mGBA libretro core | GBA / Game Boy / Game Boy Color | MPL-2.0 | https://github.com/libretro/mgba |
| bsnes libretro core | SNES | GPL-3.0 | https://github.com/libretro/bsnes |
| ClownMDEmu libretro core | Genesis / Mega Drive | AGPL-3.0 | https://github.com/Clownacy/clownmdemu-libretro |
| SMS Plus GX libretro core | Master System / Game Gear | GPL-2.0-or-later | https://github.com/libretro/smsplus-gx |
| PCSX ReARMed libretro core | PlayStation | GPL-2.0-or-later | https://github.com/libretro/pcsx_rearmed |
| Flycast libretro core | Dreamcast | GPL-2.0-or-later | https://github.com/flyinghead/flycast |
| Dolphin libretro core + system files | GameCube | GPL-2.0-or-later | https://github.com/libretro/dolphin |
| PCSX2 libretro core (parallel-ps2) | PlayStation 2 | GPL-3.0 | https://github.com/libretro/parallel-ps2 |
| Mupen64Plus-Next libretro core (GLES3) | Nintendo 64 | GPL-2.0 | https://github.com/libretro/mupen64plus-libretro-nx |
| PPSSPP libretro core + system files | PSP | GPL-2.0-or-later | https://github.com/hrydgard/ppsspp |
| ARSCLib 1.4.0 | editing app manifests | Apache-2.0 | https://github.com/REAndroid/ARSCLib |
| apksig 9.5.0 | signing apps | Apache-2.0 | https://android.googlesource.com/platform/tools/apksig |
| Bouncy Castle 1.86 | signing keys | MIT | https://www.bouncycastle.org |
| AndroidX, Kotlin | app framework | Apache-2.0 | https://developer.android.com/jetpack/androidx |

The emulator cores are the libretro buildbot nightly builds for Android arm64-v8a
(https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/) from 29–30 October 2026.
Their source is available at the repositories listed above.

**Written offer:** for at least three years from the date you received this release, the publisher
will provide, on request, a copy of the complete corresponding source code for any GPL- or
AGPL-licensed component above, at no more than the cost of physically performing the transfer.

## Not included

- **No games, BIOS files or other copyrighted content** are included. You supply your own legally
  obtained game backups or homebrew.
- **PS2 BIOS:** PS2 games need a BIOS dumped from your own console. It isn't included.
- **Xbox** games are played in hakuX, a separate (closed-source) emulator app you install yourself.
  It isn't part of this release.

## Sharing the game apps you create

Game apps created by RaineyWrapper contain the game you converted. Sharing an app that contains a
commercial game is sharing a copy of that game, which is copyright infringement whatever the
software licenses above say. Only share apps made from homebrew whose license allows it.
