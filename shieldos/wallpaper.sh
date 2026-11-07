#!/usr/bin/env bash
# Turn any photo into a shieldOS wallpaper: 1920x1080, a dark gradient at the top for the clock and buttons,
# a light blur baked in under the dock, and no EXIF/GPS left in the file.
#
# Usage: shieldos/wallpaper.sh <photo> [out.jpg]      (default out: local/wallpaper.jpg)
#
# Needs ImageMagick 7 (`magick`). Tunables, as environment variables:
#   GRADIENT_ALPHA   top gradient opacity, 0 to 1            (default 0.80)
#   GRADIENT_HEIGHT  gradient height in px of the 1080p frame (default 700)
#   DOCK_BLUR        blur under the dock, ImageMagick sigma  (default 4; 0 = no baked blur)
#   DOCK_RECT        the dock band "x0,y0 x1,y1 radius" in the 1080p frame (default "114,805 1806,1025 67")
#   QUALITY          JPEG quality (default 63; the launcher shows it on a TV, keep it small)
#
# The baked blur is only for the dock band of the shieldos theme at 6 columns with 2 rows below. If the dock
# moves (other columns, other rows), take a screenshot (tools/tv.py screencap or adb exec-out screencap -p),
# measure the band, halve the numbers if the capture is 3840x2160, and pass them in DOCK_RECT. With
# liquid_glass / dock_blur the launcher blurs live anyway, so DOCK_BLUR=0 is fine there.
set -euo pipefail

[ $# -ge 1 ] || { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
command -v magick >/dev/null || { echo "ImageMagick 7 (magick) is required: brew install imagemagick" >&2; exit 1; }

SRC=$1
OUT=${2:-"$(cd "$(dirname "$0")/.." && pwd)/local/wallpaper.jpg"}
GRADIENT_ALPHA=${GRADIENT_ALPHA:-0.80}
GRADIENT_HEIGHT=${GRADIENT_HEIGHT:-700}
DOCK_BLUR=${DOCK_BLUR:-4}
DOCK_RECT=${DOCK_RECT:-"114,805 1806,1025 67"}
QUALITY=${QUALITY:-63}

read -r P0 P1 RADIUS <<<"$DOCK_RECT"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$(dirname "$OUT")"

# 1. Fill 16:9, crop centred, light sharpening, then the top gradient. PNG in between: no JPEG generations.
magick "$SRC" -auto-orient -colorspace sRGB -filter Lanczos -resize '1920x1080^' \
  -gravity center -extent 1920x1080 -unsharp 0x0.55+0.45+0.02 \
  \( -size "1920x${GRADIENT_HEIGHT}" "gradient:rgba(0,0,0,${GRADIENT_ALPHA})-rgba(0,0,0,0)" \) \
  -gravity north -composite "$TMP/base.png"

if [ "$DOCK_BLUR" != 0 ]; then
  # 2. A blurred copy, shown only inside a rounded rectangle slightly inside the dock band.
  magick "$TMP/base.png" -blur "0x${DOCK_BLUR}" "$TMP/blurred.png"
  magick -size 1920x1080 xc:black -fill white -stroke none \
    -draw "roundrectangle ${P0} ${P1} ${RADIUS},${RADIUS}" "$TMP/mask.png"
  magick composite -compose over "$TMP/blurred.png" "$TMP/base.png" "$TMP/mask.png" "$TMP/final.png"
else
  cp "$TMP/base.png" "$TMP/final.png"
fi

# 3. Export: no metadata, progressive, 4:2:0.
magick "$TMP/final.png" -strip -sampling-factor 4:2:0 -interlace Plane -quality "$QUALITY" "$OUT"
magick identify -format '%f: %wx%h, %b\n' "$OUT"
