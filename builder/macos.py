"""Standalone macOS apps: a game wrapped in a trimmed RetroArch.app that boots straight into it.

Bundle layout (Game Title.app/Contents):
    MacOS/raineywrapper      launcher script (CFBundleExecutable)
    MacOS/RetroArch          RetroArch itself (universal)
    Frameworks/              RetroArch's frameworks (MoltenVK for Vulkan cores)
    Resources/core/          the one libretro core (universal dylib)
    Resources/game/          the game files
    Resources/system/        BIOS / core support files, copied to the save folder on first run
    Resources/game.icns      icon

Saves, states and settings live in ~/Library/Application Support/RaineyWrapper/<app id>/ so
they survive replacing or updating the app.
"""

import os
import plistlib
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path

RETROARCH_VERSION = "1.22.2"
RETROARCH_DMG = f"https://buildbot.libretro.com/stable/{RETROARCH_VERSION}/apple/osx/universal/RetroArch_Metal.dmg"
CORE_URL = "https://buildbot.libretro.com/nightly/apple/osx/{arch}/latest/{core}_libretro.dylib.zip"
CACHE = Path.home() / ".raineywrapper" / "cache" / "macos"

# Cores that render through the GPU need a Vulkan (MoltenVK) or GL context; the rest look best
# on RetroArch's native Metal driver.
HARDWARE_RENDERED = {"n64", "psp", "dreamcast", "gamecube", "wii", "ps2"}

# Same tuning as the Android player where it isn't hardware-specific (see GameActivity.coreVariables).
CORE_OPTIONS = {
    "gamecube": {"dolphin_shader_compilation_mode": "3", "dolphin_log_level": "2", "dolphin_osd_enabled": "disabled"},
    "wii": {"dolphin_shader_compilation_mode": "3", "dolphin_log_level": "2", "dolphin_osd_enabled": "disabled",
            "dolphin_wiimote_default_device": "nunchuk", "dolphin_ir_mode": "0"},
    "ps2": {"pcsx2_renderer": "Vulkan"},
}

LAUNCHER = """#!/bin/bash
# RaineyWrapper launcher: starts RetroArch straight into this app's game.
CONTENTS="$(cd "$(dirname "$0")/.." && pwd)"
RES="$CONTENTS/Resources"
DATA="$HOME/Library/Application Support/RaineyWrapper/{app_id}"
mkdir -p "$DATA/saves" "$DATA/states" "$DATA/system" "$DATA/screenshots" "$DATA/config"

# BIOS and core support files: copied once, never overwriting files the core has changed.
if [ -d "$RES/system" ]; then
  cp -Rn "$RES/system/." "$DATA/system/" 2>/dev/null
fi

# Core options are only seeded; changes made in the menu are kept.
if [ ! -f "$DATA/config/core-options.cfg" ] && [ -f "$RES/core-options.cfg" ]; then
  cp "$RES/core-options.cfg" "$DATA/config/core-options.cfg"
fi

cat > "$DATA/config/retroarch.cfg" <<EOF
video_driver = "{video_driver}"
video_fullscreen = "true"
video_windowed_fullscreen = "true"
video_vsync = "true"
audio_driver = "coreaudio"
input_joypad_driver = "mfi"
menu_driver = "rgui"
input_menu_toggle = "f1"
input_menu_toggle_gamepad_combo = "4"
input_exit_emulator = "escape"
quit_press_twice = "false"
pause_nonactive = "false"
config_save_on_exit = "false"
menu_show_load_core = "false"
menu_show_load_content = "false"
menu_show_online_updater = "false"
menu_show_core_updater = "false"
content_show_playlists = "false"
savefile_directory = "$DATA/saves"
savestate_directory = "$DATA/states"
system_directory = "$DATA/system"
screenshot_directory = "$DATA/screenshots"
core_options_path = "$DATA/config/core-options.cfg"
global_core_options = "true"
history_list_enable = "false"
content_runtime_log = "false"
playlist_directory = "$DATA/playlists"
content_favorites_path = "$DATA/playlists/favorites.lpl"
log_dir = "$DATA/logs"
cache_directory = "$DATA/cache"
input_remapping_directory = "$DATA/config/remaps"
rgui_config_directory = "$DATA/config"
EOF

{environment}exec "$CONTENTS/MacOS/RetroArch" --config "$DATA/config/retroarch.cfg" -L "$RES/core/{core}_libretro.dylib" "$RES/game/{rom}"
"""


def log(msg: str) -> None:
    print(f"[raineywrapper] {msg}", flush=True)


def _download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url) as response, open(tmp, "wb") as out:
        shutil.copyfileobj(response, out)
    tmp.rename(dest)


def ensure_retroarch() -> Path:
    """RetroArch.app from the official universal DMG, cached once."""
    app = CACHE / "RetroArch.app"
    if app.exists():
        return app
    dmg = CACHE / "RetroArch.dmg"
    if not dmg.exists():
        log(f"Downloading RetroArch {RETROARCH_VERSION} for macOS (about 230 MB, once)...")
        _download(RETROARCH_DMG, dmg)
    mount = Path(tempfile.mkdtemp(prefix="raineywrapper-dmg-"))
    subprocess.run(["hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint", str(mount), str(dmg)],
                   check=True, capture_output=True)
    try:
        shutil.copytree(mount / "RetroArch.app", app, symlinks=True)
    finally:
        subprocess.run(["hdiutil", "detach", str(mount)], capture_output=True)
    return app


def ensure_core(core: str) -> Path:
    """A universal (Intel + Apple Silicon) build of a libretro core, cached."""
    universal = CACHE / "cores" / f"{core}_libretro.dylib"
    if universal.exists():
        return universal
    thin = []
    for arch in ("x86_64", "arm64"):
        archive = CACHE / "cores" / arch / f"{core}_libretro.dylib.zip"
        if not archive.exists():
            log(f"Downloading the {core} core ({arch})...")
            _download(CORE_URL.format(arch=arch, core=core), archive)
        with zipfile.ZipFile(archive) as z:
            z.extract(f"{core}_libretro.dylib", archive.parent)
        thin.append(archive.parent / f"{core}_libretro.dylib")
    subprocess.run(["lipo", "-create", *map(str, thin), "-output", str(universal)], check=True)
    return universal


def write_icns(path: Path, title: str, image: Path | None) -> None:
    """macOS-style icon: the art in a rounded square with the standard margin, or the title."""
    from PIL import Image, ImageDraw

    import icons

    size = 1024
    inner = 824  # Apple's icon grid: 100 px margin around a 824 px rounded square
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if image:
        from PIL import ImageOps
        art = ImageOps.fit(Image.open(image).convert("RGBA"), (inner, inner), Image.LANCZOS)
    else:
        bg, fg = icons._placeholder_layers(title)  # same look as the Android title icons
        art = Image.alpha_composite(bg.convert("RGBA"), fg).resize((inner, inner), Image.LANCZOS)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, inner, inner), radius=int(inner * 0.225), fill=255)
    canvas.paste(art, ((size - inner) // 2, (size - inner) // 2), mask)

    with tempfile.TemporaryDirectory() as tmp:
        iconset = Path(tmp) / "game.iconset"
        iconset.mkdir()
        for px in (16, 32, 128, 256, 512):
            canvas.resize((px, px), Image.LANCZOS).save(iconset / f"icon_{px}x{px}.png")
            canvas.resize((px * 2, px * 2), Image.LANCZOS).save(iconset / f"icon_{px}x{px}@2x.png")
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(path)], check=True)


def build_app(out: Path, app_id: str, title: str, system: dict, rom_main: str,
              rom_files: dict, system_files: dict, icon: Path | None, version: str) -> Path:
    """Assembles Title.app. rom_files / system_files map destination names to a Path or bytes."""
    retroarch = ensure_retroarch()
    core = ensure_core(system["core"])

    if out.exists():
        shutil.rmtree(out)
    # Everything except the 180 MB menu artwork (the RGUI menu needs none) and Apple's signature,
    # which no longer matches once the bundle is changed.
    shutil.copytree(retroarch, out, symlinks=True, ignore=shutil.ignore_patterns(
        "assets.zip", "_CodeSignature", "embedded.provisionprofile", "AppIcon.icns"))
    contents = out / "Contents"
    resources = contents / "Resources"

    (resources / "core").mkdir()
    shutil.copyfile(core, resources / "core" / core.name)

    def place(files: dict, folder: Path) -> None:
        for name, src in files.items():
            dest = folder / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            if isinstance(src, bytes):
                dest.write_bytes(src)
            elif src.is_dir():
                shutil.copytree(src, dest)
            else:
                try:
                    os.link(src, dest)  # disc images are big
                except OSError:
                    shutil.copyfile(src, dest)

    place(rom_files, resources / "game")
    if system_files:
        place(system_files, resources / "system")

    options = CORE_OPTIONS.get(system["system"])
    if options:
        (resources / "core-options.cfg").write_text("".join(f'{k} = "{v}"\n' for k, v in options.items()))

    write_icns(resources / "game.icns", title, icon)

    vulkan = system["system"] in HARDWARE_RENDERED
    launcher = contents / "MacOS" / "raineywrapper"
    launcher.write_text(LAUNCHER.format(
        app_id=app_id, core=system["core"], rom=rom_main,
        video_driver="vulkan" if vulkan else "metal",
        # MoltenVK's default Metal heaps abort on older Intel GPUs (seen on Iris 6100).
        environment="export MVK_CONFIG_USE_MTLHEAP=0\n\n" if vulkan else "",
    ))
    launcher.chmod(0o755)

    with open(contents / "Info.plist", "rb") as f:
        info = plistlib.load(f)
    for key in ("CFBundleIconName", "CFBundleDocumentTypes", "UTExportedTypeDeclarations", "UTImportedTypeDeclarations"):
        info.pop(key, None)
    info.update({
        "CFBundleExecutable": "raineywrapper",
        "CFBundleIdentifier": app_id,
        "CFBundleName": title[:15],
        "CFBundleDisplayName": title,
        "CFBundleIconFile": "game.icns",
        "CFBundleShortVersionString": version,
        "CFBundleVersion": version,
    })
    with open(contents / "Info.plist", "wb") as f:
        plistlib.dump(info, f)

    # Ad-hoc signature: required for Apple Silicon, harmless on Intel. (Sharing builds without
    # Gatekeeper warnings needs an Apple Developer ID and notarisation.)
    subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(out)], check=True, capture_output=True)
    return out
