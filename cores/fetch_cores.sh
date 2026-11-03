#!/bin/sh
# Downloads arm64-v8a libretro cores from the libretro buildbot into the player template.
# Usage: cores/fetch_cores.sh [core ...]   (default: mgba)
# All cores the template ships: mgba pcsx_rearmed snes9x genesis_plus_gx mupen64plus_next_gles3 ppsspp
set -eu
BUILDBOT="https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"
# CORE_DEST overrides where cores go (the public build keeps its replacement cores in cores/public/).
DEST="${CORE_DEST:-$(cd "$(dirname "$0")/.." && pwd)/player-template/app/src/main/jniLibs/arm64-v8a}"
mkdir -p "$DEST"
[ $# -eq 0 ] && set -- mgba
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for core in "$@"; do
  so="${core}_libretro_android.so"
  echo "Fetching $so"
  curl -fsSL -o "$TMP/$so.zip" "$BUILDBOT/$so.zip"
  unzip -q -o "$TMP/$so.zip" -d "$TMP"
  # Android only packages native libs named lib*.so
  mv "$TMP/$so" "$DEST/lib$so"

  # Some cores need support files in their system folder. They ship in the template under
  # assets/coredata/<core>/; only APKs using that core keep them, and the player installs them.
  case "$core" in
    ppsspp)
      echo "Fetching PPSSPP system files"
      DATA="$(dirname "$DEST")/../assets/coredata/ppsspp"
      rm -rf "$DATA" && mkdir -p "$DATA"
      curl -fsSL -o "$TMP/PPSSPP.zip" "https://buildbot.libretro.com/assets/system/PPSSPP.zip"
      unzip -q -o "$TMP/PPSSPP.zip" -d "$DATA"
      ;;
    dolphin)
      echo "Fetching Dolphin system files"
      DATA="$(dirname "$DEST")/../assets/coredata/dolphin"
      rm -rf "$DATA" && mkdir -p "$DATA"
      curl -fsSL -o "$TMP/Dolphin.zip" "https://buildbot.libretro.com/assets/system/Dolphin.zip"
      unzip -q -o "$TMP/Dolphin.zip" -d "$DATA"
      ;;
  esac
done
ls -la "$DEST"
