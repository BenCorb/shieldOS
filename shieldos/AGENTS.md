# shieldOS fork: notes for coding agents

This checkout is shieldOS, a fork of CleanYourTV with a tvOS-style look for the NVIDIA SHIELD TV
([../.github/README.md](../.github/README.md)). The root [AGENTS.md](../AGENTS.md) and
[PROMPT.md](../PROMPT.md) still apply, with these differences:

- **One person's setup never goes in a tracked file.** Their TV's IP, dock apps, wallpaper, backups,
  screenshots and notes go in `local/` (gitignored, layout in [local.example/README.md](local.example/README.md)):
  `local/shield.env` (`TV_HOST`), `local/theme.json` (overrides merged over [theme.json](theme.json)) and
  `local/wallpaper.jpg` (made with [wallpaper.sh](wallpaper.sh)). Push the look with [apply.sh](apply.sh)
  (`--dry-run` first) instead of editing `launcher/app/src/main/res/values/config.xml` or a theme under `themes/`.
- **Private agent notes** (the TV's address, what must stay installed, house rules) go in `CLAUDE.local.md` or
  `AGENTS.local.md` at the repo root, both gitignored. Read them when they exist.
- **Fork code:** this folder, and the launcher classes listed under "Repository layout" in the fork README. New
  launcher keys stay off in `config.xml` and are switched on in [theme.json](theme.json); a new key needs its row
  in `themes/README.md` and, until a SHIELD screenshot exists, a place on `SCREENSHOT_WANTED` in
  `tests/test_switches.py`.
- **Before a commit:** no IP address, serial, account name, personal app choice or personal photo in a staged
  file; `python3 -m unittest discover -s tests` passes.
