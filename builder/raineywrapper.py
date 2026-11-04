#!/usr/bin/env python3
"""raineywrapper: turn a game ROM into a standalone Android APK.

    raineywrapper build <rom> [--title T] [--icon icon.png] [--package-suffix s] [--install]
    raineywrapper doctor
"""

import argparse
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
import doctor  # noqa: E402
import readme  # noqa: E402
from doctor import SetupError, Toolchain  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / "player-template"
OUTPUT = ROOT / "output"
SYSTEMS_FILE = Path(__file__).resolve().parent / "systems.json"
KEY_DIR = Path.home() / ".raineywrapper"
KEYSTORE = KEY_DIR / "release.keystore"
KEY_PROPS = KEY_DIR / "keystore.properties"
KEY_ALIAS = "raineywrapper"
APP_ID_PREFIX = "com.raineywrapper"

# Never copied into the build dir: build outputs, dev ROM, machine-specific files.
TEMPLATE_IGNORE = shutil.ignore_patterns("build", ".gradle", ".kotlin", "local.properties", ".idea")


class BuildError(Exception):
    pass


def log(msg: str) -> None:
    print(f"[raineywrapper] {msg}", flush=True)


# --- systems & ROMs ---------------------------------------------------------

def load_systems() -> dict[str, list[dict]]:
    """Returns extension -> candidate systems ({system, core, name, folders, external, bios...}), in file order."""
    data = json.loads(SYSTEMS_FILE.read_text())["systems"]
    by_ext: dict[str, list[dict]] = {}
    for system_id, s in data.items():
        info = {"system": system_id, "core": s.get("core"), "name": s["name"],
                "folders": [f.lower() for f in s.get("folders", [])], "external": bool(s.get("external")),
                "bios": s.get("bios", []), "bios_required": bool(s.get("biosRequired"))}
        for ext in s["extensions"]:
            by_ext.setdefault(ext.lower(), []).append(info)
    return by_ext


# Set by --system: overrides folder-based detection (like the System picker in the app).
SYSTEM_OVERRIDE: str | None = None


def is_ps3_disc(path: Path) -> bool:
    """PS3 disc images are ISO 9660 with the volume name "PS3VOLUME" (primary volume descriptor, sector 16)."""
    try:
        with path.open("rb") as f:
            f.seek(0x8028)
            return f.read(9) == b"PS3VOLUME"
    except OSError:
        return False


def nintendo_disc(path: Path) -> str | None:
    """'wii' or 'gamecube' from the disc header (both use .iso, .rvz, .gcz...), or None."""
    def u32(data: bytes, offset: int, order: str = "big") -> int:
        return int.from_bytes(data[offset:offset + 4], order)

    def from_header(header: bytes) -> str | None:
        if u32(header, 0x18) == 0x5D1C9EA3:
            return "wii"
        if u32(header, 0x1C) == 0xC2339F3D:
            return "gamecube"
        return None

    try:
        with open(path, "rb") as f:
            start = f.read(0x60)
            if start[:4] == b"WBFS":
                return "wii"
            if start[:4] in (b"WIA\x01", b"RVZ\x01"):  # header 2 starts at 0x48 with the disc type
                return {1: "gamecube", 2: "wii"}.get(u32(start, 0x48))
            if u32(start, 0, "little") == 0xB10BC001:  # GCZ: sub-type 0 = GameCube, 1 = Wii
                return {0: "gamecube", 1: "wii"}.get(u32(start, 4, "little"))
            if start[:4] == b"CISO":  # the disc starts after the 32 KB block map
                f.seek(0x8000)
                return from_header(f.read(0x20))
            return from_header(start)
    except OSError:
        return None


def pick_system(systems: dict, ext: str, path: Path) -> dict:
    """When an extension is shared (.chd: PS1, PS2, Dreamcast), the ROM's folder decides.
    Nintendo discs say in their header whether they're GameCube or Wii, which beats the folder."""
    candidates = systems[ext]
    if SYSTEM_OVERRIDE:
        forced = next((c for c in candidates if c["system"] == SYSTEM_OVERRIDE), None)
        if forced is None:
            names = ", ".join(c["system"] for c in candidates)
            raise BuildError(f"{path.name}: .{ext} files can't be {SYSTEM_OVERRIDE} (possible systems: {names})")
        candidates = [forced]
    if len(candidates) > 1 and any(c["system"] in ("gamecube", "wii") for c in candidates):
        kind = nintendo_disc(path)
        candidates = [c for c in candidates if c["system"] == kind] or candidates
    if len(candidates) > 1 and any(c["system"] == "ps3" for c in candidates) and is_ps3_disc(path):
        candidates = [c for c in candidates if c["system"] == "ps3"]
    folders = [path.parent.name.lower(), path.parent.parent.name.lower()]
    system = next((c for c in candidates if any(f in c["folders"] for f in folders)), candidates[0])
    if system["external"]:
        raise BuildError(
            f"{path.name}: {system['name']} games play in an emulator app on the device, so they're converted "
            "with the RaineyWrapper app on the handheld, not here."
        )
    return system


@dataclass
class RomSet:
    """The files that make up one game, as they'll be named inside the APK."""
    main: str                                   # file the core loads, e.g. "rom.cue"
    files: dict[str, Path | bytes] = field(default_factory=dict)  # asset name -> source file or content
    title_source: Path | None = None            # file whose name the default title comes from

    @property
    def total_bytes(self) -> int:
        return sum(len(v) if isinstance(v, bytes) else v.stat().st_size for v in self.files.values())


# Extensions that are only ever part of a multi-file game, never loaded directly.
TRACK_EXTENSIONS = {"bin", "img", "wav", "iso"}


def resolve_rom(path: Path, systems: dict, workdir: Path) -> tuple[RomSet, dict]:
    """Works out the game's files (extracting a .zip if needed) and its system."""
    ext = path.suffix.lower().lstrip(".")
    if ext == "zip":
        with zipfile.ZipFile(path) as zf:
            names = [n for n in zf.namelist() if not n.endswith("/")]
            playable = [n for n in names if Path(n).suffix.lower().lstrip(".") in systems]
            if not playable:
                raise BuildError(f"{path.name}: no supported ROM inside the zip (supported: {', '.join(sorted(systems))})")
            out = workdir / "unzipped"
            zf.extractall(out, members=names)
            # Prefer a .cue (it pulls in its tracks) over loose files.
            playable.sort(key=lambda n: Path(n).suffix.lower() not in (".cue", ".gdi"))
            return resolve_rom(out / playable[0], systems, workdir)

    if ext in TRACK_EXTENSIONS and path.with_suffix(".cue").exists():
        raise BuildError(f"{path.name} is a disc track; pass its .cue file instead: {path.with_suffix('.cue').name}")
    if ext not in systems:
        hint = ""
        if ext in TRACK_EXTENSIONS and path.with_suffix(".cue").exists():
            hint = f"\n  This looks like a disc track; pass the .cue file instead: {path.with_suffix('.cue').name}"
        raise BuildError(f"{path.name}: unsupported file type '.{ext}' (supported: {', '.join(sorted(systems))}, zip){hint}")

    system = pick_system(systems, ext, path)
    if ext == "cue":
        return resolve_sheet(path, CUE_FILES), system
    if ext == "gdi":
        return resolve_sheet(path, GDI_FILES), system
    rom_name = "rom." + ext
    return RomSet(main=rom_name, files={rom_name: path}, title_source=path), system


# `FILE "Track 01.bin" BINARY`
CUE_FILES = re.compile(r'^(\s*FILE\s+)"?(.+?)"?(\s+\S+\s*)$', re.MULTILINE | re.IGNORECASE)
# Dreamcast `1 0 4 2352 "Track 01.bin" 0` (quotes optional)
GDI_FILES = re.compile(r'^(\s*\d+\s+\d+\s+\d+\s+\d+\s+)"?([^"\r\n]+?)"?(\s+-?\d+\s*)$', re.MULTILINE)


def resolve_sheet(sheet: Path, pattern: re.Pattern) -> RomSet:
    """Bundles a .cue/.gdi with every track file it references, renamed to rom.bin / rom_02.bin ...
    so the asset names are predictable, and rewrites the sheet to match."""
    text = sheet.read_text(errors="replace")
    referenced = [m.group(2) for m in pattern.finditer(text)]
    if not referenced:
        raise BuildError(f"{sheet.name}: no track files listed")

    main = "rom" + sheet.suffix.lower()
    rom = RomSet(main=main, title_source=sheet)
    renames = {}
    for i, ref in enumerate(dict.fromkeys(referenced)):
        src = sheet.parent / ref
        if not src.exists():
            raise BuildError(f"{sheet.name} refers to '{ref}', which isn't next to it")
        new = "rom" + ("" if i == 0 else f"_{i + 1:02d}") + src.suffix.lower()
        renames[ref] = new
        rom.files[new] = src

    # Keep the file name quoted: some cores' cue parsers (PCSX2) reject unquoted names.
    rom.files[main] = pattern.sub(lambda m: f'{m.group(1)}"{renames[m.group(2)]}"{m.group(3)}', text).encode()
    return rom


def title_from_filename(path: Path) -> str:
    # "Mario Kart - Super Circuit (USA) [!].gba" -> "Mario Kart - Super Circuit"
    stem = path.stem.removesuffix(".nkit").removesuffix(".xiso")  # "Game (USA).nkit.iso" / ".xiso.iso"
    name = re.sub(r"[\(\[].*?[\)\]]", "", stem)
    return re.sub(r"\s+", " ", name.replace("_", " ")).strip(" -") or stem


def sanitize_package_part(text: str) -> str:
    part = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")
    if not part:
        raise BuildError(f"can't make a package name from '{text}'; pass --package-suffix")
    if not part[0].isalpha():
        part = "g" + part
    return part


def safe_filename(title: str) -> str:
    return re.sub(r'[\\/:*?"<>|]+', "", title).strip() or "game"


# --- signing ----------------------------------------------------------------

def ensure_keystore(tc: Toolchain) -> dict[str, str]:
    """Creates the signing key once and reuses it so updates install over old versions."""
    if KEYSTORE.exists() and KEY_PROPS.exists():
        props = dict(l.split("=", 1) for l in KEY_PROPS.read_text().splitlines() if "=" in l)
        return props
    if KEYSTORE.exists():
        raise BuildError(f"{KEYSTORE} exists but {KEY_PROPS} (its password) is missing; restore it from backup")

    KEY_DIR.mkdir(mode=0o700, exist_ok=True)
    password = secrets.token_urlsafe(24)
    log(f"Creating signing key at {KEYSTORE} (back this folder up!)")
    subprocess.run([
        str(tc.java_home / "bin" / "keytool"), "-genkeypair", "-noprompt",
        "-keystore", str(KEYSTORE), "-storetype", "PKCS12",
        "-alias", KEY_ALIAS, "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
        "-dname", "CN=RaineyWrapper Personal Builds",
        "-storepass", password, "-keypass", password,
    ], check=True, capture_output=True)
    KEY_PROPS.write_text(f"storePassword={password}\nkeyAlias={KEY_ALIAS}\n")
    KEY_PROPS.chmod(0o600)
    KEYSTORE.chmod(0o600)
    return {"storePassword": password, "keyAlias": KEY_ALIAS}


# --- build ------------------------------------------------------------------

def place_file(src: Path | bytes, dest: Path) -> None:
    if isinstance(src, bytes):
        dest.write_bytes(src)
        return
    try:
        os.link(src, dest)  # disc images are big; avoid copying when on the same disk
    except OSError:
        shutil.copyfile(src, dest)


def copy_template(build_dir: Path, tc: Toolchain) -> Path:
    """Copies the player template to build_dir with an empty assets/game; returns app/src/main."""
    shutil.copytree(TEMPLATE, build_dir, ignore=TEMPLATE_IGNORE)
    (build_dir / "local.properties").write_text(f"sdk.dir={tc.sdk_dir}\n")
    main = build_dir / "app" / "src" / "main"
    game_dir = main / "assets" / "game"
    shutil.rmtree(game_dir, ignore_errors=True)
    game_dir.mkdir(parents=True)
    return main


def write_label_and_icons(main: Path, title: str, icon: Path | None) -> None:
    import icons

    (main / "res" / "values" / "strings.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <string name="app_name">{android_string(title)}</string>\n</resources>\n'
    )
    icons.write_icons(main / "res", title, icon)


def prepare_project(build_dir: Path, tc: Toolchain, rom: RomSet, system: dict, title: str, icon: Path | None,
                    bios: Path | None = None) -> None:
    ensure_libretrodroid(tc)
    main = copy_template(build_dir, tc)
    game_dir = main / "assets" / "game"
    for name, src in rom.files.items():
        place_file(src, game_dir / name)
    system_files = []
    if bios:
        # e.g. "pcsx2/bios/*.bin" + scph39001.bin -> assets/game/system/pcsx2/bios/scph39001.bin
        target = system["bios"][0].replace("*.bin", bios.name) if system["bios"] else bios.name
        (game_dir / "system" / target).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(bios, game_dir / "system" / target)
        system_files.append(target)
    (game_dir / "game.json").write_text(json.dumps({
        "title": title,
        "system": system["system"],
        "core": f"lib{system['core']}_libretro_android.so",
        "rom": rom.main,
        "files": list(rom.files),
        "totalBytes": rom.total_bytes,
        "systemFiles": system_files,
    }, indent=2))

    # Only ship the core this game needs.
    jni = main / "jniLibs" / "arm64-v8a"
    keep = f"lib{system['core']}_libretro_android.so"
    for so in jni.glob("*.so"):
        if so.name != keep:
            so.unlink()
    for data in (main / "assets" / "coredata").glob("*"):
        if data.name != system["core"]:
            shutil.rmtree(data)

    write_label_and_icons(main, title, icon)


def android_string(text: str) -> str:
    # XML-escape, then escape the characters Android's string parser treats specially.
    s = escape(text).replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    return "\\" + s if s.startswith(("@", "?")) else s


def gradle_env(tc: Toolchain, keys: dict | None) -> dict:
    env = dict(os.environ)
    env["JAVA_HOME"] = str(tc.java_home)
    env["ANDROID_HOME"] = str(tc.sdk_dir)
    if keys:
        # Passwords go through the environment so they don't show up in `ps`.
        env["ORG_GRADLE_PROJECT_raineywrapperStorePassword"] = keys["storePassword"]
        env["ORG_GRADLE_PROJECT_raineywrapperKeyPassword"] = keys["storePassword"]
    return env


def signing_args(keys: dict) -> list[str]:
    return [f"-PraineywrapperKeystore={KEYSTORE}", f"-PraineywrapperKeyAlias={keys.get('keyAlias', KEY_ALIAS)}"]


def version_code_now() -> int:
    return int(time.time() // 60)  # increases every build, so updates always install


def run_gradle(build_dir: Path, tc: Toolchain, app_id: str, keys: dict | None) -> Path:
    """Release build of the player. Without keys the APK is left unsigned."""
    env = gradle_env(tc, keys)
    cmd = [
        str(build_dir / "gradlew"), "assembleRelease", "--console=plain", "-q",
        f"-PraineywrapperAppId={app_id}",
        f"-PraineywrapperVersionCode={version_code_now()}",
        f"-PraineywrapperVersionName={time.strftime('%Y.%m.%d')}",
    ] + (signing_args(keys) if keys else [])
    log("Running Gradle release build (the first build takes a few minutes)...")
    result = subprocess.run(cmd, cwd=build_dir, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        tail = "\n".join((result.stdout + result.stderr).strip().splitlines()[-40:])
        raise BuildError(f"Gradle build failed:\n{tail}")
    apk = build_dir / "app/build/outputs/apk/release" / ("app-release.apk" if keys else "app-release-unsigned.apk")
    if not apk.exists():
        raise BuildError(f"Gradle finished but {apk.name} was not produced")
    return apk


def verify_signature(tc: Toolchain, apk: Path) -> None:
    tools = sorted((tc.sdk_dir / "build-tools").iterdir())
    apksigner = tools[-1] / "apksigner"
    env = dict(os.environ, JAVA_HOME=str(tc.java_home))
    r = subprocess.run([str(apksigner), "verify", str(apk)], capture_output=True, text=True, env=env)
    if r.returncode != 0:
        raise BuildError(f"APK signature check failed: {r.stderr.strip()}")


def install(tc: Toolchain, apk: Path, app_id: str) -> None:
    if not tc.adb:
        raise BuildError("adb not found; install it with: sdkmanager \"platform-tools\"")
    devices = [l.split()[0] for l in subprocess.run([str(tc.adb), "devices"], capture_output=True, text=True)
               .stdout.splitlines()[1:] if l.strip().endswith("device")]
    if not devices:
        raise BuildError(
            "No device found by adb. Check that:\n"
            "  1. Developer options and USB debugging are on (Settings > About > tap Build number 7x)\n"
            "  2. The cable supports data and the 'Allow USB debugging' prompt was accepted\n"
            "  3. `adb devices` lists the device as 'device' (not 'unauthorized')"
        )
    log(f"Installing on {devices[0]}...")
    r = subprocess.run([str(tc.adb), "-s", devices[0], "install", "-r", str(apk)], capture_output=True, text=True)
    out = r.stdout + r.stderr
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
        raise BuildError(
            f"{app_id} is already installed with a different signing key.\n"
            f"  Uninstall it first (this deletes its saves): adb uninstall {app_id}"
        )
    if r.returncode != 0 or "Success" not in out:
        raise BuildError(f"adb install failed:\n{out.strip()}")
    log("Installed.")


MAC_OUTPUT = OUTPUT / "mac"
CORE_DATA = TEMPLATE / "app" / "src" / "main" / "assets" / "coredata"


def build_mac(rom_path: Path, title: str | None, icon: Path | None, package_suffix: str | None,
              do_install: bool, bios: Path | None) -> Path:
    """Standalone macOS app (see macos.py). Needs no Android toolchain."""
    import macos

    systems = load_systems()
    with tempfile.TemporaryDirectory(prefix="raineywrapper-mac-") as tmp:
        rom, system = resolve_rom(rom_path, systems, Path(tmp))
        if not system.get("core"):
            raise BuildError(f"{system['name']} isn't supported on macOS yet")
        if system["bios_required"] and not bios:
            raise BuildError(f"{system['name']} games need a BIOS: pass --bios path/to/your-bios.bin")
        title = title or title_from_filename(rom.title_source)
        app_id = f"{APP_ID_PREFIX}.{sanitize_package_part(package_suffix or title)}"
        log(f"{title}  [{system['name']}, core: {system['core']}]  ->  macOS app {app_id}")
        if system["system"] == "ps2":
            log("Warning: PS2 on macOS is experimental. On an Intel Mac with Iris graphics the game "
                "loads but never draws a frame; it is untested on Apple Silicon.")

        # BIOS plus the core's support files (PPSSPP fonts, Dolphin's Sys folder), shared with Android.
        system_files: dict = {}
        if bios:
            target = system["bios"][0].replace("*.bin", bios.name) if system["bios"] else bios.name
            system_files[target] = bios
        data = CORE_DATA / system["core"]
        if data.is_dir():
            for item in data.iterdir():
                system_files[item.name] = item

        MAC_OUTPUT.mkdir(parents=True, exist_ok=True)
        out = macos.build_app(MAC_OUTPUT / f"{safe_filename(title)}.app", app_id, title, system,
                              rom.main, rom.files, system_files, icon, time.strftime("%Y.%m.%d"))
    size = sum(f.stat().st_size for f in out.rglob("*") if f.is_file() and not f.is_symlink())
    log(f"Built {out.relative_to(ROOT)} ({size / 1e6:.0f} MB)")
    if do_install:
        apps = Path.home() / "Applications"
        apps.mkdir(exist_ok=True)
        dest = apps / out.name
        shutil.rmtree(dest, ignore_errors=True)
        shutil.copytree(out, dest, symlinks=True)
        log(f"Installed to {dest}")
        return dest
    return out


def build_one(tc: Toolchain, rom_path: Path, title: str | None, icon: Path | None,
              package_suffix: str | None, do_install: bool, bios: Path | None = None) -> Path:
    systems = load_systems()
    with tempfile.TemporaryDirectory(prefix="raineywrapper-") as tmp:
        tmp = Path(tmp)
        rom, system = resolve_rom(rom_path, systems, tmp)
        doctor.check_core(TEMPLATE, system["core"])
        title = title or title_from_filename(rom.title_source)
        app_id = f"{APP_ID_PREFIX}.{sanitize_package_part(package_suffix or title)}"
        log(f"{title}  [{system['name']}, core: {system['core']}]  ->  {app_id}")

        keys = ensure_keystore(tc)
        build_dir = tmp / "project"
        if system["bios_required"] and not bios:
            raise BuildError(f"{system['name']} games need a BIOS: pass --bios path/to/your-bios.bin")
        prepare_project(build_dir, tc, rom, system, title, icon, bios)
        apk = run_gradle(build_dir, tc, app_id, keys)
        verify_signature(tc, apk)

        OUTPUT.mkdir(exist_ok=True)
        out = OUTPUT / f"{safe_filename(title)}.apk"
        shutil.copyfile(apk, out)
    log(f"Built {out.relative_to(ROOT)} ({out.stat().st_size / 1e6:.1f} MB)")
    if do_install:
        install(tc, out, app_id)
    return out


# --- on-device converter ------------------------------------------------------

TEMPLATE_APP_ID = f"{APP_ID_PREFIX}.template"
LIBRETRODROID = ROOT / "libretrodroid"
LIBRETRODROID_AAR = TEMPLATE / "app" / "libs" / "libretrodroid.aar"


def build_libretrodroid(tc: Toolchain) -> Path:
    """Builds RaineyWrapper's LibretroDroid fork (native C++, needs the NDK) into the player template."""
    (LIBRETRODROID / "local.properties").write_text(f"sdk.dir={tc.sdk_dir}\n")
    log("Building LibretroDroid (C++; the first build takes a few minutes)...")
    result = subprocess.run([str(LIBRETRODROID / "gradlew"), ":libretrodroid:assembleRelease", "--console=plain", "-q"],
                            cwd=LIBRETRODROID, env=gradle_env(tc, None), capture_output=True, text=True)
    if result.returncode != 0:
        tail = "\n".join((result.stdout + result.stderr).strip().splitlines()[-40:])
        raise BuildError(f"LibretroDroid build failed:\n{tail}")
    LIBRETRODROID_AAR.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(LIBRETRODROID / "libretrodroid/build/outputs/aar/libretrodroid-release.aar", LIBRETRODROID_AAR)
    log(f"Built {LIBRETRODROID_AAR.relative_to(ROOT)}")
    return LIBRETRODROID_AAR


def ensure_libretrodroid(tc: Toolchain) -> None:
    if not LIBRETRODROID_AAR.exists():
        build_libretrodroid(tc)
CONVERTER = ROOT / "converter"
CONVERTER_APP_ID = "com.raineywrapper.converter"


PUBLIC_OUTPUT = OUTPUT / "public"
PUBLIC_CORES = ROOT / "cores" / "public"


def public_core_swaps() -> tuple[set[str], set[str]]:
    """(non-commercially licensed cores to remove, GPL-compatible cores to add), from systems.json.
    Per system, since one core can be replaced by different ones (Genesis vs Master System)."""
    systems = [s for s in json.loads(SYSTEMS_FILE.read_text())["systems"].values() if s.get("publicCore")]
    return {s["core"] for s in systems}, {s["publicCore"] for s in systems}


def public_systems_json() -> str:
    data = json.loads(SYSTEMS_FILE.read_text())
    for s in data["systems"].values():
        if s.get("publicCore"):
            s["core"] = s.pop("publicCore")
    return json.dumps(data, indent=2)


def ensure_public_cores() -> None:
    missing = [c for c in sorted(public_core_swaps()[1]) if not (PUBLIC_CORES / f"lib{c}_libretro_android.so").exists()]
    if missing:
        log(f"Downloading public-build cores: {', '.join(missing)}")
        env = dict(os.environ, CORE_DEST=str(PUBLIC_CORES))
        subprocess.run([str(ROOT / "cores" / "fetch_cores.sh"), *missing], check=True, env=env, capture_output=True)


def build_template(tc: Toolchain, public: bool = False) -> Path:
    """Builds the generic, unsigned player APK (all cores, no game) that the
    converter app patches on the device. The public variant swaps non-commercial
    cores for GPL-compatible ones so it can be distributed."""
    ensure_libretrodroid(tc)
    with tempfile.TemporaryDirectory(prefix="raineywrapper-template-") as tmp:
        build_dir = Path(tmp) / "project"
        main = copy_template(build_dir, tc)
        if public:
            ensure_public_cores()
            jni = main / "jniLibs" / "arm64-v8a"
            removed, added = public_core_swaps()
            for core in removed:
                (jni / f"lib{core}_libretro_android.so").unlink(missing_ok=True)
            for core in added:
                shutil.copyfile(PUBLIC_CORES / f"lib{core}_libretro_android.so", jni / f"lib{core}_libretro_android.so")
        (main / "assets" / "game" / "game.json").write_text(json.dumps({
            "title": "RaineyWrapper", "system": "", "core": "", "rom": "", "files": [],
        }))
        write_label_and_icons(main, "RaineyWrapper", None)
        apk = run_gradle(build_dir, tc, TEMPLATE_APP_ID, None)
        out_dir = PUBLIC_OUTPUT if public else OUTPUT
        out_dir.mkdir(parents=True, exist_ok=True)
        out = out_dir / "player-template.apk"
        shutil.copyfile(apk, out)
    log(f"Built {out.relative_to(ROOT)} ({out.stat().st_size / 1e6:.1f} MB)")
    return out


def build_converter(tc: Toolchain, do_install: bool, public: bool = False) -> Path:
    if public and do_install:
        raise BuildError("--public builds are for distribution and are never installed on your device")
    template = build_template(tc, public)
    assets = CONVERTER / "app/src/main/assets"
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(template, assets / "player.apk")
    if public:
        (assets / "systems.json").write_text(public_systems_json())
    else:
        shutil.copyfile(SYSTEMS_FILE, assets / "systems.json")
    (CONVERTER / "local.properties").write_text(f"sdk.dir={tc.sdk_dir}\n")

    keys = ensure_keystore(tc)
    log("Building the RaineyWrapper converter app...")
    cmd = [str(CONVERTER / "gradlew"), ":app:assembleRelease", "--console=plain", "-q",
           f"-PraineywrapperVersionCode={version_code_now()}"] + signing_args(keys)
    result = subprocess.run(cmd, cwd=CONVERTER, env=gradle_env(tc, keys), capture_output=True, text=True)
    if result.returncode != 0:
        tail = "\n".join((result.stdout + result.stderr).strip().splitlines()[-40:])
        raise BuildError(f"Gradle build failed:\n{tail}")
    apk = CONVERTER / "app/build/outputs/apk/release/app-release.apk"
    verify_signature(tc, apk)
    out = (PUBLIC_OUTPUT if public else OUTPUT) / "RaineyWrapper.apk"
    shutil.copyfile(apk, out)
    log(f"Built {out.relative_to(ROOT)} ({out.stat().st_size / 1e6:.1f} MB)")
    if do_install:
        install(tc, out, CONVERTER_APP_ID)
    return out


# --- public release package -------------------------------------------------

# Never shipped in the source package: ROMs, outputs, caches, downloaded binaries, keys, cover art.
SOURCE_SKIP_DIRS = {"roms", "output", ".git", ".gradle", ".kotlin", "build", "bin", ".cxx", ".venv",
                    "__pycache__", ".idea", "coredata", "public", "libs"}
SOURCE_SKIP_PATHS = {"converter/app/src/main/assets", "player-template/app/src/main/assets/game"}
SOURCE_SKIP_FILES = ["*.so", "*.apk", "*.aar", "*.keystore", "*.jks", "local.properties", "*.gba", "*.bin", "*.cue",
                     "*.iso", "*.chd", "*.rvz", "*.zip", ".DS_Store", "*.srm", "*.class", "*.jpg", "*.jpeg"]


def converter_version() -> str:
    m = re.search(r'versionName = "([^"]+)"', (CONVERTER / "app/build.gradle.kts").read_text())
    return m.group(1) if m else "dev"


def write_source_zip(out: Path) -> int:
    import fnmatch
    count = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, dirnames, filenames in os.walk(ROOT):
            rel = Path(dirpath).relative_to(ROOT).as_posix()
            dirnames[:] = [d for d in dirnames if d not in SOURCE_SKIP_DIRS
                           and (f"{rel}/{d}" if rel != "." else d) not in SOURCE_SKIP_PATHS]
            for name in filenames:
                if any(fnmatch.fnmatch(name, pattern) for pattern in SOURCE_SKIP_FILES):
                    continue
                path = Path(dirpath) / name
                z.write(path, "RaineyWrapper/" + path.relative_to(ROOT).as_posix())
                count += 1
    return count


def package_public(tc: Toolchain, dest: Path) -> Path:
    """Builds the public converter and writes the upload folder + zip: APK, README, licenses, source."""
    for path in readme.update():
        log(f"Updated the supported-systems table in {path.name} from systems.json")
    apk = build_converter(tc, False, public=True)
    version = converter_version()
    folder = dest / f"RaineyWrapper-{version}"
    shutil.rmtree(folder, ignore_errors=True)
    folder.mkdir(parents=True)
    shutil.copyfile(apk, folder / f"RaineyWrapper-{version}.apk")
    (folder / "README.txt").write_text(readme.strip_markers((ROOT / "README-public.txt").read_text()))
    shutil.copyfile(ROOT / "LICENSES-public.md", folder / "LICENSES.md")
    shutil.copyfile(ROOT / "LICENSE", folder / "LICENSE.txt")
    files = write_source_zip(folder / "RaineyWrapper-source.zip")
    archive = shutil.make_archive(str(dest / f"RaineyWrapper-{version}"), "zip", dest, folder.name)
    log(f"Source package: {files} files")
    log(f"Release folder: {folder}")
    log(f"Upload this:    {archive} ({Path(archive).stat().st_size / 1e6:.0f} MB)")
    return Path(archive)


# --- CLI --------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(prog="raineywrapper", description="Wrap a ROM into a standalone Android APK.")
    sub = parser.add_subparsers(dest="command", required=True)

    b = sub.add_parser("build", help="build an APK from a ROM")
    b.add_argument("rom", type=Path, help="ROM file (or a .zip containing one)")
    b.add_argument("--title", help="app name shown on the launcher (default: from the file name)")
    b.add_argument("--icon", type=Path, help="PNG/JPG for the launcher icon (default: generated from the title)")
    b.add_argument("--package-suffix", help=f"use {APP_ID_PREFIX}.<suffix> instead of a name derived from the title")
    b.add_argument("--install", action="store_true", help="install on the connected device with adb")
    b.add_argument("--bios", type=Path, help="BIOS file to bundle (required for PS2)")
    b.add_argument("--system", help="force the system (e.g. ps2) when the ROM's folder doesn't say; see systems.json")
    b.add_argument("--target", choices=["android", "mac"], default="android",
                   help="android: APK (default); mac: standalone macOS app")

    sub.add_parser("doctor", help="check that the JDK, Android SDK and Pillow are installed")
    pk = sub.add_parser("package", help="build the public release and write the upload folder + zip")
    pk.add_argument("--dest", type=Path, default=Path.home() / "Desktop", help="where to write it (default: Desktop)")
    sub.add_parser("libretrodroid", help="rebuild the LibretroDroid fork (C++) used by the player")
    t = sub.add_parser("template", help="build the generic player APK used by the on-device converter")
    t.add_argument("--public", action="store_true", help="distributable variant with GPL-compatible cores only")
    c = sub.add_parser("converter", help="build the RaineyWrapper Android app (converts ROMs on the device)")
    c.add_argument("--install", action="store_true", help="install on the connected device with adb")
    c.add_argument("--public", action="store_true",
                   help="distributable variant: swaps non-commercial cores for GPL-compatible ones; output/public/")
    r = sub.add_parser("readme", help="write the supported-systems tables in the READMEs from systems.json")
    r.add_argument("--check", action="store_true", help="only report whether they're up to date (exit 1 if not)")

    args = parser.parse_args()
    global SYSTEM_OVERRIDE
    SYSTEM_OVERRIDE = getattr(args, "system", None)
    try:
        if args.command == "readme":
            # Only reads systems.json and the READMEs: no JDK or Android SDK needed.
            if args.check:
                out_of_date = readme.stale()
                for path in out_of_date:
                    log(f"{path.name}: supported-systems table is out of date; run ./raineywrapper readme")
                if not out_of_date:
                    log("Supported-systems tables match systems.json.")
                return 1 if out_of_date else 0
            changed = readme.update()
            log("Updated " + ", ".join(p.name for p in changed) if changed else "Tables already match systems.json.")
            return 0
        if args.command == "build" and args.target == "mac":
            # macOS apps need no JDK/Android SDK, only Pillow for the icon.
            doctor.check_pillow()
            if not args.rom.exists():
                raise BuildError(f"ROM not found: {args.rom}")
            for path, what in ((args.icon, "icon"), (args.bios, "BIOS")):
                if path and not path.exists():
                    raise BuildError(f"{what} not found: {path}")
            build_mac(args.rom, args.title, args.icon, args.package_suffix, args.install, args.bios)
            return 0
        tc = doctor.run_all()
        if args.command == "doctor":
            log(f"JDK:         {tc.java_home}")
            log(f"Android SDK: {tc.sdk_dir}")
            log(f"adb:         {tc.adb or 'not found (only needed for --install)'}")
            log("Everything needed to build is installed.")
            return 0
        if args.command == "package":
            package_public(tc, args.dest)
            return 0
        if args.command == "libretrodroid":
            build_libretrodroid(tc)
            return 0
        if args.command == "template":
            build_template(tc, args.public)
            return 0
        if args.command == "converter":
            build_converter(tc, args.install, args.public)
            return 0
        if not args.rom.exists():
            raise BuildError(f"ROM not found: {args.rom}")
        if args.icon and not args.icon.exists():
            raise BuildError(f"icon not found: {args.icon}")
        if args.bios and not args.bios.exists():
            raise BuildError(f"BIOS not found: {args.bios}")
        build_one(tc, args.rom, args.title, args.icon, args.package_suffix, args.install, args.bios)
        return 0
    except (SetupError, BuildError, readme.ReadmeError) as e:
        print(f"\n[raineywrapper] ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
