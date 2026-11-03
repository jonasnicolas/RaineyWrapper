"""Environment checks. Each check reports exactly what's missing and how to fix it."""

import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

COMPILE_SDK = "37"
REQUIRED_JDK = 17


@dataclass
class Toolchain:
    java_home: Path
    sdk_dir: Path
    adb: Path | None


class SetupError(Exception):
    pass


def _java_version(java_home: Path) -> int | None:
    java = java_home / "bin" / "java"
    if not java.exists():
        return None
    out = subprocess.run([str(java), "-version"], capture_output=True, text=True).stderr
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not m:
        return None
    major = int(m.group(1))
    return int(m.group(2)) if major == 1 else major


def find_java_home() -> Path:
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    if Path("/usr/libexec/java_home").exists():
        r = subprocess.run(["/usr/libexec/java_home", "-v", str(REQUIRED_JDK)], capture_output=True, text=True)
        if r.returncode == 0:
            candidates.append(Path(r.stdout.strip()))
    for home in candidates:
        v = _java_version(home)
        if v is not None and v >= REQUIRED_JDK:
            return home
    raise SetupError(
        f"JDK {REQUIRED_JDK} not found.\n"
        "  Fix (macOS, no admin needed):\n"
        "    1. Download the 'JDK 17 .tar.gz' for macOS from https://adoptium.net/temurin/releases/?version=17\n"
        "    2. mkdir -p ~/Library/Java/JavaVirtualMachines && tar -xzf <file>.tar.gz -C ~/Library/Java/JavaVirtualMachines\n"
        "  Or set JAVA_HOME to an existing JDK 17+ install."
    )


def find_sdk() -> Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var) and Path(os.environ[var]).is_dir():
            return Path(os.environ[var])
    default = Path.home() / "Library" / "Android" / "sdk"
    if default.is_dir():
        return default
    raise SetupError(
        "Android SDK not found (checked ANDROID_HOME, ANDROID_SDK_ROOT, ~/Library/Android/sdk).\n"
        "  Fix:\n"
        "    1. Download 'Command line tools only' for Mac from https://developer.android.com/studio#command-tools\n"
        "    2. mkdir -p ~/Library/Android/sdk/cmdline-tools && unzip the zip there, then rename\n"
        "       the extracted 'cmdline-tools' folder to 'latest'\n"
        "    3. Run the sdkmanager command printed by: python3 builder/raineywrapper.py doctor"
    )


def check_sdk_packages(sdk: Path) -> None:
    missing = []
    if not any((sdk / "platforms").glob(f"android-{COMPILE_SDK}*")):
        missing.append(f"platforms;android-{COMPILE_SDK}.0")
    build_tools = sdk / "build-tools"
    if not (build_tools.is_dir() and any(build_tools.iterdir())):
        missing.append(f"build-tools;{COMPILE_SDK}.0.0")
    if not (sdk / "platform-tools" / "adb").exists():
        missing.append("platform-tools")
    if not (sdk / "licenses" / "android-sdk-license").exists():
        missing.insert(0, "(licenses not accepted)")
    if missing:
        sm = sdk / "cmdline-tools" / "latest" / "bin" / "sdkmanager"
        pkgs = " ".join(f'"{p}"' for p in missing if not p.startswith("("))
        raise SetupError(
            "Android SDK is missing: " + ", ".join(missing) + "\n"
            "  Fix:\n"
            "    export JAVA_HOME=$(/usr/libexec/java_home -v 17)\n"
            f"    {sm} --licenses\n"
            + (f"    {sm} {pkgs}" if pkgs else "")
        )


def find_adb(sdk: Path) -> Path | None:
    adb = sdk / "platform-tools" / "adb"
    if adb.exists():
        return adb
    found = shutil.which("adb")
    return Path(found) if found else None


def check_pillow() -> None:
    try:
        import PIL  # noqa: F401
    except ImportError:
        raise SetupError(
            "Python package Pillow is missing (used to make launcher icons).\n"
            "  Fix: run the builder through ./raineywrapper (it sets up builder/.venv), or:\n"
            "    python3 -m pip install -r builder/requirements.txt"
        )


def check_core(template: Path, core: str) -> None:
    so = template / "app/src/main/jniLibs/arm64-v8a" / f"lib{core}_libretro_android.so"
    if not so.exists():
        raise SetupError(
            f"Emulator core '{core}' hasn't been downloaded.\n"
            f"  Fix: cores/fetch_cores.sh {core}"
        )


def run_all() -> Toolchain:
    java_home = find_java_home()
    sdk = find_sdk()
    check_sdk_packages(sdk)
    check_pillow()
    return Toolchain(java_home=java_home, sdk_dir=sdk, adb=find_adb(sdk))
