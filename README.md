# ai-studio
All in one AI studio setup for image/video generation, faceswap etc. Runs on Linux

A portable folder: copy it to any drive (exFAT, NTFS, ext4, btrfs) and run it.
Nothing is installed on the host; every download, cache and config stays inside.

## Requirements on the host
- Linux x86_64 with bash, tar, xz and curl (or wget) — present on every standard install
- NVIDIA GPU with driver 580 or newer (the only thing that must be installed on the system)

## Quick start
```bash
./studio.sh doctor            # checks GPU driver, drive, free space; tells you how to fix problems
./studio.sh install wan2gp    # app + pinned dependencies (~15 GB on exFAT, first time only)
./studio.sh                   # control panel: start/stop apps, each in its own terminal window
```

The control panel lists every app with its status, URL and the GPU memory in use.
Select one or more apps (Space, or their number) and press Enter: each opens in its own
terminal window showing its log. Press `x` to stop the selected apps, `a` to stop all,
`l` to make apps started next reachable from your network, `q` to leave the panel (apps
keep running). Pressing Ctrl-C in or closing an app's window also stops that app.

Without the panel: `./studio.sh start <app>` starts in the background and prints the URL,
`./studio.sh run <app>` starts in the current terminal, `./studio.sh stop [app]` stops
one app or all.

| App | Use | URL |
|---|---|---|
| `wan2gp` | Text/image to video, image generation | http://127.0.0.1:7860 |
| `facefusion` | Face swap on images and videos | http://127.0.0.1:7870 |
| `comfyui` | Node workflows; loads any checkpoint (SD 1.5, SDXL, Flux, ...) | http://127.0.0.1:8188 |

ComfyUI models go in `models/comfyui/<type>/` (e.g. checkpoints from Civitai in
`models/comfyui/checkpoints`, LoRAs in `models/comfyui/loras`); press R in the UI to rescan.

ComfyUI comes with the ReActor face swap nodes (`ReActor 🌌 Fast Face Swap`): connect a
generated image to `input_image` and a photo of the face to use (a **Load Image** node)
to `source_image`. Its models download into `models/comfyui` on first use (about 2.5 GB).
Custom nodes the studio installs are pinned in `recipes/comfyui/recipe.sh`
(`APP_CUSTOM_NODES`); ones you add yourself go in `data/comfyui/custom_nodes`.

To use an app from a phone or tablet on the same Wi-Fi, start it with `--lan`
(e.g. `./studio.sh start facefusion --lan`) and open the printed `http://<pc-ip>:<port>/`
address. There is no password, so only do this on a network you trust.

Several apps can run at once, but they share the GPU's memory: if a generation fails
with an out-of-memory error, stop the apps you are not using. Results go to
`outputs/<app>/`, models to `models/<app>/`.

For speed, connect the drive to a USB 3 port (10 Gbps if available): models are
streamed from disk while generating, and a USB 2 link makes loading 20x slower.

## Layout
| Folder | Contents |
|---|---|
| `config/versions.sh` | Every pinned version and checksum |
| `lib/` | Scripts behind `studio.sh` |
| `runtime/<platform>/` | uv, standalone Python builds, ffmpeg, zig (C/C++ compiler) |
| `recipes/<app>/` | Per app: pinned commit, lock file, launcher |
| `apps/<app>/` | App source at the pinned commit |
| `envs/<platform>/<app>/` | The app's own Python with its packages |
| `cache/downloads/` | Verified downloads, kept so reinstalls work offline |
| `data/home/` | `HOME` as the apps see it (catches stray config files) |
| `models/`, `outputs/`, `inputs/` | Your models and results |

## Design rules
- **Everything pinned**: no "latest". Updating means changing a pin and re-testing.
- **No symlinks, no venvs**: each app gets its own standalone Python, so the folder
  survives being copied to filesystems without symlinks and to a different path.
- **Isolated environment**: `HOME`, XDG, Hugging Face, torch, uv and pip caches all point
  inside the folder; `LD_LIBRARY_PATH` is cleared so a system CUDA cannot interfere.
