"""Starts ComfyUI with the studio's folders.

Models come from models/comfyui (--models-directory). Custom nodes load from two
places: the pinned ones the installer puts in apps/comfyui/custom_nodes, and any you
add yourself in data/comfyui/custom_nodes. ComfyUI only takes the second on the command
line, so the first goes in an extra paths file, written on every start (so its absolute
path follows the studio folder when it moves).

Run with the app folder as the working directory: python launch.py <main.py args...>
"""
import os
import runpy
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


def main():
    args = sys.argv[1:]
    for i, a in enumerate(args[:-1]):
        if a in FOLDER_ARGS:
            os.makedirs(args[i + 1], exist_ok=True)
    data_dir = args[args.index("--base-directory") + 1]
    # ComfyUI lists this at startup but does not create it under --base-directory.
    os.makedirs(os.path.join(data_dir, "custom_nodes"), exist_ok=True)

    for name in MODEL_FOLDERS:
        os.makedirs(os.path.join(MODELS, name), exist_ok=True)
    config = os.path.join(data_dir, "extra_model_paths.yaml")
    with open(config, "w", encoding="utf-8") as f:
        f.write("# Written by recipes/comfyui/launch.py on every start; edits are lost.\n")
        f.write("studio:\n")
        f.write(f"    custom_nodes: {os.path.join(APP_DIR, 'custom_nodes')}\n")

    sys.path.insert(0, APP_DIR)
    sys.argv = ["main.py"] + args + ["--extra-model-paths-config", config]
    runpy.run_path(os.path.join(APP_DIR, "main.py"), run_name="__main__")


if __name__ == "__main__":
    main()
