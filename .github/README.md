# shieldOS

**A tvOS-style home screen for the NVIDIA SHIELD TV, built as a fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv).**

CleanYourTV strips the ads and bloat from Android TV and replaces the stock home screen with a clean
launcher. shieldOS keeps all of that and pushes the launcher's look further towards Apple TV's tvOS —
tiles that lift and lean under focus, real frosted glass, a Liquid Glass rim on the dock and the menus,
and a tvOS-like Home where the app grid slides up under a blurred wallpaper. It is tuned and tested on an
NVIDIA SHIELD TV Pro (2019) running Android TV 11, but every feature is an ordinary theme key, so it runs
on any TV the upstream launcher supports.

> **Screenshots:** coming soon, taken with a neutral theme. Until then, see upstream's
> [What you get](../README.md#what-you-get) for the base launcher.

- [What this fork adds](#what-this-fork-adds)
- [Quick start on a SHIELD](#quick-start-on-a-shield)
- [Your own setup, kept out of git (`local/`)](#your-own-setup-kept-out-of-git-local)
- [SHIELD TV notes](#shield-tv-notes)
- [Staying in sync with upstream](#staying-in-sync-with-upstream)
- [Rolling back](#rolling-back)
- [Repository layout](#repository-layout)
- [Credits and license](#credits-and-license)

---

## What this fork adds

All new behaviour is **off by default** in the launcher (`launcher/app/src/main/res/values/config.xml`), exactly
like upstream's own opt-in keys, and switched on by the fork's theme in [`shieldos/theme.json`](../shieldos/theme.json).
A stock CleanYourTV theme pushed to a shieldOS build looks the way it does upstream.

### New theme keys

| Key | Values | What it does |
|---|---|---|
| `tvos_focus` | bool | Focus lives on the tile itself instead of a moving glass box: the focused tile grows, lifts on a soft drop shadow, leans briefly towards the D-pad direction with its gloss sliding the same way, then keeps a slow, faint sway while it stays focused. Grid tiles only show their name while focused; dock tiles stay icon-only. |
| `tvos_home` | bool | tvOS-style Home: the dock sits low with the first grid row peeking under it. **Down** slides the dock to the top, hides the clock and header and fades in a full-screen blur of the wallpaper; **Back** returns to the dock. The clock moves, small, to the top right. Needs `dock_row`; `rows_below_dock` is ignored. |
| `grid_peek` | 0–120 (dp) | With `tvos_home`, how much of the first grid row shows under the dock. Default 28. |
| `liquid_glass` | bool | Liquid Glass on the dock band, the Settings button and popup menus: the blurred wallpaper bent inwards along the rim like the edge of a lens, a faint colour fringe and a specular rim lit from the top left. Drawn live with shaders every frame, so it never lags when the row scrolls. Needs `dock_blur` > 0. |
| `dock_blur` | 0–10 | Strength of every glass surface (dock band, Settings button, grid backdrop, menus, Launcher settings' backdrop). 0 is clear glass, 10 the most frosted, default 3. Also adjustable on the TV (see below). |

The full reference of every key, upstream's and these, is in [`themes/README.md`](../themes/README.md#keys).

### Launcher changes

- **Real backdrop blur on Android 11.** `BlurBackdropView` blurs the local wallpaper (never the UI itself) with a
  small RenderScript pass on a downscaled bitmap, clipped to the dock's rounded band, the round Settings button and
  popup panels. If a vendor build lacks RenderScript, the launcher falls back to a tinted glass and keeps running.
- **Liquid Glass renderer** (`LiquidGlass`, `FrostDrawable`): lens rim, chromatic fringe and specular light, with
  the size-bound layers built once off the UI thread.
- **tvOS focus motion** (`TvosFocusMotion`, `TileShadowView`, `TileShadowDrawable`, `tile_gloss`): one
  interruptible animation state per tile, unit-tested in `TvosFocusMotionTest`.
- **Glass menus:** popup panels start from the already-blurred wallpaper and switch to a blurred copy of Home
  (via `PixelCopy`) as soon as it is ready, so menus appear on their first frame.
- **Glass blur slider** in *Launcher settings*: Left/Right from *None* to *Max*, applied instantly. Like the other
  on-TV choices it wins until the next theme push or *Follow theme*.
- **Start screen saver** entry in the Settings button's long-press menu: starts the TV's configured dream
  (Aerial Views on my SHIELD) or CleanYourTV's clock fallback.
- Refreshed settings screen styling (medium-weight captions, glass choice pills), English, French and Hebrew strings
  for every new label.

### The `shieldos` theme

[`shieldos/theme.json`](../shieldos/theme.json) turns it all on: a six-column frosted dock with even
padding and no title, two labelled grid rows, icon-only top buttons, app-colour glow without the ambient glow,
`tvos_focus`, `tvos_home`, `liquid_glass` and `dock_blur: 3`. Its dock is a generic example (Netflix, YouTube,
Prime Video, Disney+, Plex, Chromecast): put your own in `local/` (next section). Apps that are not installed are
skipped and the free slots are filled from the grid.

---

## Quick start on a SHIELD

Requirements: a Mac or Linux machine with Python 3, `adb`, JDK 17 + Android SDK 34 (or Docker, see
`tools/docker-build.md`), and ImageMagick 7 for wallpapers. On the SHIELD: *Settings › Device Preferences ›
About › Build* (tap 7×) to unlock developer options, then *Developer options › Network debugging* on.

```bash
git clone https://github.com/BenCorb/shieldOS.git && cd shieldOS
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
```

1. **Build and install the launcher** (it becomes Home only once it has started successfully):

   ```bash
   cd launcher && TV_HOST=<shield ip> ./build-and-install.sh && cd ..
   ```

2. **Debloat** (optional, reversible), once the launcher is Home: the list ends with the stock launcher, and
   `tools/disable.sh` refuses to disable the app that owns Home while nothing else can take over. The SHIELD
   list from upstream is conservative and documents what to keep:

   ```bash
   TV_HOST=<shield ip> tools/disable.sh examples/nvidia-shield-tv-pro-2019/debloat.txt
   ```

3. **Set up your local overrides and push the look:**

   ```bash
   mkdir -p local
   cp shieldos/local.example/shield.env.example local/shield.env   # set TV_HOST
   cp shieldos/local.example/theme.json local/theme.json           # set your dock
   shieldos/wallpaper.sh ~/Pictures/some-photo.jpg                 # optional
   shieldos/apply.sh --dry-run && shieldos/apply.sh
   ```

The rest of upstream's tooling (add-ons, `tools/manage.py`, `tools/check.py`) works unchanged — see the
[upstream README](../README.md). With a coding agent, point it at [`PROMPT.md`](../PROMPT.md) from this checkout:
[`AGENTS.md`](../AGENTS.md) tells it to keep your dock and wallpaper in `local/` and push with `shieldos/apply.sh`.

### Reporting problems

Bugs in what this fork adds (the keys above, `shieldos/`) belong here. Anything that also happens on upstream
CleanYourTV — debloat lists, a TV model, the tools — is best reported
[upstream](https://github.com/Gathin23/cleanyourtv/issues), where the TV knowledge lives.

---

## Your own setup, kept out of git (`local/`)

shieldOS is meant to be both **shareable** and **the checkout you maintain your own box from**. The rule is simple:
anything that describes *your* SHIELD lives in `local/` (gitignored as a whole) or in `CLAUDE.local.md` /
`AGENTS.local.md` (also gitignored), never in a tracked file.

| Path | Read by | What |
|---|---|---|
| `local/shield.env` | `apply.sh` | `TV_HOST=<shield ip>`; an exported `TV_HOST` wins |
| `local/theme.json` | `apply.sh` | Overrides merged over `shieldos/theme.json` (dock apps, wallpaper keys, any theme key). Keys starting with `_` are comments |
| `local/wallpaper.jpg` or `local/wallpapers/` | `apply.sh` | Your wallpaper or rotating collection |
| `local/build/` | written by `apply.sh` | The merged theme actually pushed; safe to delete |
| `local/backup/`, `local/debloat/`, `local/apks/`, `local/notes/` | you | Dumps, your own debloat batches, sideloaded APKs + SHA-256, notes |
| `CLAUDE.local.md` | Claude Code | Private instructions for a coding agent: your IP, what must stay installed, house rules |

`shieldos/apply.sh` merges the two configs, refuses any value the launcher would silently drop (using the same
checker as the repo's tests), copies your wallpaper, writes everything to `local/build/theme/` and hands it to
upstream's `tools/theme.sh`. `tests/test_shieldos_fork.py` fails if `local/` or `CLAUDE.local.md` ever stop being
ignored.

### Wallpapers

`shieldos/wallpaper.sh <photo> [out.jpg]` turns any photo into a launcher-ready wallpaper (default output
`local/wallpaper.jpg`):

- 1920×1080, centred crop, light sharpening, Lanczos resize;
- an 80 % black gradient over the top 700 px so the clock and buttons stay readable;
- an optional light blur baked in **only** under the dock band (`DOCK_BLUR=0` to skip it — with `dock_blur` /
  `liquid_glass` the launcher blurs live anyway);
- EXIF/GPS stripped, progressive JPEG at quality 63 (~200 KB).

Tunables are environment variables (`GRADIENT_ALPHA`, `GRADIENT_HEIGHT`, `DOCK_BLUR`, `DOCK_RECT`, `QUALITY`);
run the script without arguments for details. If you change `columns` or the rows below the dock, re-measure the
dock band on a screenshot and pass it as `DOCK_RECT` (halve the numbers if the capture is 3840×2160).
Keep `wallpaper_dim` around 20: the gradient is already in the picture.

---

## SHIELD TV notes

Tested on a **SHIELD TV Pro (2019, `mdarcy`)**, Android TV 11, Shield Experience 9.2.4. The 2019 "tube" model
(`sif`) shares the software and should behave the same; the 2017 model is upstream-supported but untested here.

- **Keep these enabled** (upstream's SHIELD list already leaves them alone):
  `com.google.android.tvrecommendations` (disabling it breaks the SHIELD phone remote's key input after a reboot),
  `com.nvidia.ota` (firmware updates), `com.google.android.apps.mediashell` (Chromecast),
  `com.nvidia.shield.smbserver` and the storage providers (SMB share, USB drives for Plex),
  `com.google.android.katniss` + `com.google.android.speech.pumpkin` if you use the remote's microphone.
- **Plex Media Server** on the SHIELD keeps working with the launcher replaced; nothing in the debloat touches it.
- **Screen saver:** [Aerial Views](https://github.com/theothernt/AerialViews) pairs well with the look and is what
  the *Start screen saver* menu entry launches when it is the configured dream. Pick it in *Settings › Device
  Preferences › Screen saver*, or over ADB with `adb shell settings put secure screensaver_components <component>`
  (find the component with `adb shell dumpsys package com.neilturner.aerialviews | grep -i dream`).
- **Performance:** the blur works on a downscaled wallpaper and the Liquid Glass layers are cached per size, to
  keep the Tegra X1+ smooth at 4K output. A before/after `tools/bench.py` comparison is still to be published. If
  you see jank, lower `dock_blur` or turn `liquid_glass` off first.
- **AirPlay:** a SHIELD can act as an AirPlay receiver with
  [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPLv3, separate project).
  A tvOS-like "close when the phone disconnects" behaviour may be published later as its own fork.
- **ADB over the network:** leaving network debugging on makes maintenance easy, but only on a trusted LAN.
  Never forward port 5555 from your router.

---

## Staying in sync with upstream

```bash
git remote add upstream https://github.com/Gathin23/cleanyourtv.git   # once
git fetch upstream
git merge upstream/main
python3 -m unittest discover -s tests                                  # upstream's checks + the fork's
```

The fork is built to merge cleanly: the root `README.md` and its translations are untouched (this page lives in
`.github/README.md`, which GitHub shows first), new defaults stay off in `config.xml`, and the fork's own files sit
in `shieldos/` and a handful of new launcher classes. Conflicts, if any, are usually in `MainActivity.java`.

The five new keys are on the screenshot wanted list in `tests/test_switches.py` until screenshots from a SHIELD
with a neutral theme are added to `docs/switches/`.

---

## Rolling back

Everything upstream offers still applies:

```bash
TV_HOST=<shield ip> tools/theme.sh --reset                                       # drop the theme overlay
TV_HOST=<shield ip> tools/restore.sh examples/nvidia-shield-tv-pro-2019/debloat.txt
```

Restoring re-enables `com.google.android.tvlauncher` and makes it Home again; the CleanYourTV launcher stays installed
until you remove it from *Settings › Apps*.

---

## Repository layout

| Path | Origin |
|---|---|
| `shieldos/` | **fork** — the theme, `apply.sh`, `wallpaper.sh`, the `local/` template, notes for coding agents (`shieldos/AGENTS.md`) |
| `.github/README.md` | **fork** — this page |
| `tests/test_shieldos_fork.py` | **fork** — theme validity, `local/` stays ignored |
| `launcher/…/BlurBackdropView.java`, `LiquidGlass.java`, `FrostDrawable.java`, `TvosFocusMotion.java`, `TileShadow*.java` | **fork** |
| `launcher/…/MainActivity.java`, `AppAdapter.java`, `Config.java`, `DockDecoration.java`, `LauncherSettingsActivity.java`, layouts, strings | upstream, modified |
| everything else | upstream CleanYourTV, unchanged |
| `local/`, `CLAUDE.local.md` | yours, never committed |

---

## Credits and license

shieldOS is a fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) by Gathin and its contributors, who did
the hard part: the debloat research across hundreds of TVs, the launcher, the tooling and the restore path. Please
star and support the upstream project.

MIT License, as upstream — see [`LICENSE`](../LICENSE) (copyright © 2026 Gathin; fork modifications © 2026 BenCorb,
same terms). Bundled icons and fonts keep their own licenses (`launcher/ICONS-LICENSE-FLUENT-EMOJI.txt`,
`launcher/FONT-LICENSE-OFL.txt`). "NVIDIA" and "SHIELD" are trademarks of NVIDIA Corporation; "Apple TV" and "tvOS"
are trademarks of Apple Inc. This project is not affiliated with either.
