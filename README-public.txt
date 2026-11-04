RaineyWrapper 1.0
=================

Turn your game backups into standalone Android apps. Each game gets its own icon on
your home screen and starts straight into the game: no frontend, no menus.

Made for Android gaming handhelds (Retroid, AYN Odin, Anbernic and similar) and works
on any 64-bit Android 8.0+ device.

No games are included. You need your own legally obtained game backups or homebrew.


SUPPORTED SYSTEMS
-----------------
Built in (nothing else to install):
  - Game Boy Advance, Game Boy, Game Boy Color   (.gba .gb .gbc .sgb)
  - Super Nintendo                               (.sfc .smc .swc .fig)
  - Sega Genesis / Mega Drive                    (.md .gen .smd .bin)
  - Sega Master System, Game Gear                (.sms .gg)
  - PlayStation                                  (.cue .chd .pbp)
  - Nintendo 64                                  (.z64 .n64 .v64)
  - PSP                                          (.iso .cso .pbp .chd)
  - Dreamcast                                    (.chd .cdi .gdi .cue)
  - GameCube                                     (.iso .rvz .gcm .gcz .ciso)
  - Wii                                          (.wbfs .iso .rvz .wia .gcz .ciso)
  - PlayStation 2 (needs your own PS2 BIOS)      (.iso .chd .cso .cue)

Needs an emulator app installed (the game's icon opens it straight into the game):
  - Xbox            hakuX                          (.xiso.iso .iso)

ROMs inside .zip files work for cartridge systems.


INSTALLING
----------
1. Copy the RaineyWrapper .apk to your device and open it.
2. If Android asks, allow your file manager or browser to "install unknown apps".
3. Open RaineyWrapper. On first launch it asks for access to your files so it can
   find your games. Allow it.


CONVERTING A GAME
-----------------
1. Browse to a game and select it (with the d-pad and A, or by touch).
2. Check the title and icon. A cover image with the same name as the game, in the
   same folder or in an "images" / "covers" / "boxart" folder next to it, is used
   automatically. You can also pick one yourself.
3. Choose where the game lives:
     - Put the game inside the app: works on its own; uses extra storage.
     - Play from where the file is now: small app, but keep the game file where it is.
   Disc-based games (PS1, PSP, PS2, Dreamcast, GameCube, Wii) default to the second option.
4. Press "Convert and install" and confirm Android's install prompt.

The first time, Android asks you to allow RaineyWrapper to install apps.

If Google Play Protect shows "App scan recommended", tap "More details" and then
"Install without scanning". "Scan app" uploads the whole game file to Google.


PLAYING
-------
- Back, or Start+Select, opens the in-game menu: save state, load state, reset,
  screen filter (sharp, smooth, upscale, CRT, LCD), internal resolution for 3D systems,
  Wii controller type, FPS counter, touch controls on/off, and quit.
- In-game saves are kept automatically.
- On-screen touch controls appear automatically on devices without buttons. Systems with
  analog sticks get on-screen sticks (two for N64, GameCube, Wii and PS2).
- Wii games play with a Wii Remote + Nunchuk by default: the left stick is the Nunchuk stick
  and the right stick moves the pointer. Classic Controller and sideways Wii Remote can be
  picked in the menu.
- Games always run at their own speed, even on 90/120 Hz screens.
- Converting a game that's already installed updates it and keeps its saves.

Folder names help tell systems apart: put games in folders named psx, psp, ps2,
dreamcast, n64, snes, megadrive, gamegear and so on. You can also change the system
on the convert screen.


PS2
---
PS2 games need a BIOS dumped from your own PS2 (a 4 MB .bin file, e.g. scph39001.bin).
Put it in a "bios" folder, for example next to your games in ps2/bios, and RaineyWrapper
includes it automatically, picking the one that matches the game's region.

PS2, GameCube and Wii need a fast device; on mid-range chips many games run at full speed,
but the most demanding ones may slow down. Dreamcast games run without a BIOS; if a dc_boot.bin is
found in a "bios/dc" folder it's included automatically for better compatibility.


LICENSE
-------
RaineyWrapper is free software under the GNU GPL v3.0. See LICENSES.md for every
component and its license. The full source code is in RaineyWrapper-source.zip.
