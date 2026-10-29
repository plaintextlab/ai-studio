"""Starts ComfyUI with the studio's folders.

Models come from models/comfyui (--models-directory). Custom nodes load from three
places: the pinned ones the installer puts in apps/comfyui/custom_nodes, the studio's
own in recipes/comfyui/nodes, and any you add yourself in data/comfyui/custom_nodes.
ComfyUI only takes the last on the command line, so the others go in an extra paths
file, written on every start (so their absolute paths follow the studio folder when it
moves).

Run with the app folder as the working directory: python launch.py <main.py args...>
"""
import os
import runpy
import shutil
import sys

STUDIO = os.environ["STUDIO_ROOT"]
APP_DIR = os.getcwd()
MODELS = os.path.join(STUDIO, "models", "comfyui")

# ComfyUI's model folder types, created up front so you can see where files go.
MODEL_FOLDERS = [
    "checkpoints", "configs", "loras", "vae", "text_encoders", "diffusion_models",
    "clip_vision", "style_models", "embeddings", "diffusers", "vae_approx", "controlnet",
    "gligen", "upscale_models", "latent_upscale_models", "hypernetworks", "photomaker",
    "classifiers", "model_patches", "audio_encoders",
]
FOLDER_ARGS = ("--base-directory", "--models-directory", "--user-directory",
               "--input-directory", "--output-directory", "--temp-directory")


def seed_workflows(user_dir):
    """Copies the bundled workflows (recipes/comfyui/workflows) into the workflow list.

    Each one is copied once: yours are never overwritten, and one you delete stays
    deleted (copied names are remembered in .studio-seeded)."""
    src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "workflows")
    dst = os.path.join(user_dir, "default", "workflows")
    if not os.path.isdir(src):
        return
    os.makedirs(dst, exist_ok=True)
    marker = os.path.join(dst, ".studio-seeded")
    try:
        with open(marker, encoding="utf-8") as f:
            seeded = set(f.read().splitlines())
    except FileNotFoundError:
        seeded = set()
    for name in sorted(os.listdir(src)):
        if not name.endswith(".json") or name in seeded:
            continue
        if not os.path.exists(os.path.join(dst, name)):
            shutil.copyfile(os.path.join(src, name), os.path.join(dst, name))
        seeded.add(name)
    with open(marker, "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(seeded)) + "\n")


def main():
    args = sys.argv[1:]
    for i, a in enumerate(args[:-1]):
        if a in FOLDER_ARGS:
            os.makedirs(args[i + 1], exist_ok=True)
    data_dir = args[args.index("--base-directory") + 1]
    # ComfyUI lists this at startup but does not create it under --base-directory.
    os.makedirs(os.path.join(data_dir, "custom_nodes"), exist_ok=True)

    seed_workflows(args[args.index("--user-directory") + 1])

    for name in MODEL_FOLDERS:
        os.makedirs(os.path.join(MODELS, name), exist_ok=True)
    config = os.path.join(data_dir, "extra_model_paths.yaml")
    with open(config, "w", encoding="utf-8") as f:
        f.write("# Written by recipes/comfyui/launch.py on every start; edits are lost.\n")
        f.write("studio:\n")
        f.write("    custom_nodes: |\n")
        f.write(f"        {os.path.join(APP_DIR, 'custom_nodes')}\n")
        f.write(f"        {os.path.join(os.path.dirname(os.path.abspath(__file__)), 'nodes')}\n")

    sys.path.insert(0, APP_DIR)
    sys.argv = ["main.py"] + args + ["--extra-model-paths-config", config]
    runpy.run_path(os.path.join(APP_DIR, "main.py"), run_name="__main__")


if __name__ == "__main__":
    main()
