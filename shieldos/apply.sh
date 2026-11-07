#!/usr/bin/env bash
# Push the shieldOS look to a Shield, with your own overrides from local/ on top.
#
# Usage: shieldos/apply.sh            merge, then push with tools/theme.sh
#        shieldos/apply.sh --dry-run  merge only, print the result, touch nothing on the TV
#
# Inputs, all optional except TV_HOST for a real push:
#   shieldos/theme.json  the shared shieldOS look (tracked)
#   local/theme.json     your overrides: dock_apps, wallpaper keys, anything (gitignored)
#   local/wallpaper.jpg  your wallpaper (gitignored); see shieldos/wallpaper.sh
#   local/wallpapers/    or a rotating collection (gitignored)
#   local/shield.env     TV_HOST=<shield ip> (gitignored); an exported TV_HOST wins
#
# The merged theme is written to local/build/theme/, so nothing personal ever lands in a tracked path.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
DRY_RUN=0
case "${1:-}" in
  --dry-run) DRY_RUN=1 ;;
  "") ;;
  *) echo "usage: shieldos/apply.sh [--dry-run]" >&2; exit 2 ;;
esac

if [ -f local/shield.env ] && [ -z "${TV_HOST:-}" ]; then
  # shellcheck disable=SC1091
  . local/shield.env
fi

OUT=local/build/theme
rm -rf "$OUT"
mkdir -p "$OUT"

python3 -I - "$ROOT/shieldos/theme.json" "$ROOT/local/theme.json" "$ROOT/$OUT/config.json" <<'PY'
import json, sys
from pathlib import Path

base_path, overlay_path, out_path = map(Path, sys.argv[1:4])
config = json.loads(base_path.read_text(encoding="utf-8"))
if overlay_path.is_file():
    overlay = json.loads(overlay_path.read_text(encoding="utf-8"))
    if not isinstance(overlay, dict):
        sys.exit(f"{overlay_path}: expected a JSON object")
    # Keys starting with "_" are comments for you, not for the launcher.
    config.update({k: v for k, v in overlay.items() if not k.startswith("_")})
    print(f"merged {overlay_path.relative_to(Path.cwd())} over {base_path.relative_to(Path.cwd())}")
else:
    print(f"no {overlay_path.relative_to(Path.cwd())}: using {base_path.relative_to(Path.cwd())} as is")

# Refuse values the launcher would silently drop, with the same checker the repo's tests use.
sys.path.insert(0, str(Path.cwd() / "tests"))
try:
    import test_shipped_config_values as checker
except ImportError:
    checker = None
if checker is not None:
    kinds = checker.readers(checker.CONFIG_JAVA.read_text(encoding="utf-8"))
    found = checker.problems(config, kinds)
    if found:
        for key, why in found:
            print(f"  {key}: {why}", file=sys.stderr)
        sys.exit("the merged theme has values the launcher would drop; nothing was pushed")
out_path.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
PY

if [ -f local/wallpaper.jpg ]; then
  cp local/wallpaper.jpg "$OUT/wallpaper.jpg"
  echo "wallpaper: local/wallpaper.jpg"
fi
if [ -d local/wallpapers ]; then
  cp -R local/wallpapers "$OUT/wallpapers"
  echo "wallpapers: local/wallpapers/"
fi

if [ "$DRY_RUN" = 1 ]; then
  echo "--- $OUT/config.json"
  cat "$OUT/config.json"
  echo "dry run: nothing pushed."
  exit 0
fi

: "${TV_HOST:?set TV_HOST or create local/shield.env (see shieldos/local.example/)}"
export TV_HOST
exec tools/theme.sh "$OUT"
