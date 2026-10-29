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
./studio.sh                   # control panel: start/stop apps, each in its own terminal window
```

Nothing needs installing by hand: the first time an app starts, the studio downloads
what it needs (runtime, Python, the app at its pinned version, its packages and the
models listed in its recipe), checking every file against its pinned checksum. That is
several GB per app (about 50 GB for ComfyUI with its models), once; later starts only
check that everything is in place. After a `git pull` that changes a pin, the next start
updates what changed. `./studio.sh install <app>` does the same without starting it.

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

ComfyUI comes with these workflows (`recipes/comfyui/workflows`, copied into its
workflow list on first start; your edits are kept):

- **1 - Character creation**: a name, the body and the clothes in separate boxes, and a
  face photo; makes a full-body picture of that character, saved as
  `outputs/comfyui/characters/<name>`.
- **2 - Pose scene**: two characters (Model 1 on the left, Model 2 on the right), each
  with their image (for the face) and a description of body and clothes (paste it from
  Character creation), plus what they do and the camera angle. Z-Image Turbo draws the
  scene, which keeps two people touching apart and well formed (SDXL merges them), and
  ReActor puts each one's face back. Optionally copies the poses from a reference photo
  (switch on the green group; Z-Image's pose ControlNet). Makes two variations per run in
  `outputs/comfyui/scenes/<name1>_<name2>`.
- **Prompt to Image Generation WF** (Z-Image Turbo) and **checkpoint_workflow** (SDXL
  with face swap and a pose variation section).

The models they use are pinned in `recipes/comfyui/recipe.sh` (`APP_MODELS`) and
downloaded with the app. Upload your own photos in their **Load Image** nodes first.

ComfyUI comes with the ReActor face swap nodes (`ReActor 🌌 Fast Face Swap`): connect a
generated image to `input_image` and a photo of the face to use (a **Load Image** node)
to `source_image`. Its face restore and analysis models download into `models/comfyui`
on first use (about 2 GB).
The `checkpoint_workflow` has a second section, **Same character, new pose**: load an
image you generated as the character, and any photo of a person in the pose you want
(or a ready OpenPose skeleton, with the `Read pose` node bypassed). IPAdapter keeps the
character's look and body, an OpenPose ControlNet copies the pose, ReActor keeps the face,
and the pose prompt is added to the main positive prompt. Its models (all downloaded
with the app) are
`models/comfyui/clip_vision/CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors` (the
`h94/IP-Adapter` image encoder) plus, per checkpoint type:

| Checkpoint | `models/comfyui/ipadapter/` | `models/comfyui/controlnet/` (pick in the workflow) |
|---|---|---|
| SDXL | `ip-adapter-plus_sdxl_vit-h.safetensors` | `controlnet-openpose-sdxl-1.0.safetensors` (xinsir) |
| SD 1.5 | `ip-adapter-plus_sd15.safetensors` | `control_v11p_sd15_openpose_fp16.safetensors` |

IPAdapter picks its file from the checkpoint type by itself; the ControlNet you switch
by hand (with the SD 1.5 one, set `scale_stick_for_xinsr_cn` to disable). DWPose
downloads its own models into `models/comfyui/annotators` on first use.

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
| `recipes/<app>/` | Per app: pinned commit, models, lock file, launcher (and ComfyUI's workflows) |
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
