"""Starts Wan2GP with its models, LoRAs and outputs in the studio's shared folders.

Wan2GP keeps those paths in wgp_config.json and crashes if that file lacks any of its
many keys, so instead of writing the file ourselves we let Wan2GP create it and adjust
only the path entries that still have Wan2GP's defaults. Paths a user changes in the
UI are left alone. All paths are relative to the app folder, so they stay valid when
the studio folder is moved or copied.

Run with the app folder as the working directory: python launch.py [wgp.py args...]
"""
import os
import runpy
import sys

STUDIO = os.environ["STUDIO_ROOT"]
APP_DIR = os.getcwd()


def rel(*parts):
    return os.path.relpath(os.path.join(STUDIO, *parts), APP_DIR)


MODELS = rel("models", "wan2gp")
OUTPUTS = rel("outputs", "wan2gp")
LORAS = rel("models", "wan2gp", "loras")
SAVE_KEYS = ("save_path", "image_save_path", "audio_save_path", "mesh_save_path")


def studio_paths(config):
    if config.get("checkpoints_paths") in (None, ["ckpts", "."]):
        config["checkpoints_paths"] = [MODELS, "."]
    if config.get("loras_root") in (None, "loras"):
        config["loras_root"] = LORAS
    for key in SAVE_KEYS:
        if config.get(key) in (None, "outputs"):
            config[key] = OUTPUTS
    return config


FOLDER_ARGS = ("--config", "--settings", "--workspaces-dir", "--deepy-sessions-dir")


def main():
    # Wan2GP creates its default folders itself, but not ones passed as arguments.
    folders = [MODELS, OUTPUTS, LORAS]
    args = sys.argv[1:]
    folders += [args[i + 1] for i, a in enumerate(args[:-1]) if a in FOLDER_ARGS]
    for d in folders:
        os.makedirs(d, exist_ok=True)
    sys.path.insert(0, APP_DIR)

    from shared.utils import config_store

    read_config, write_config = config_store.read_config, config_store.write_config

    def is_main_config(filename):
        return os.path.basename(filename) == "wgp_config.json"

    def read_patched(filename):
        config = read_config(filename)
        return studio_paths(config) if is_main_config(filename) else config

    def write_patched(config, filename):
        # Patching in place also fixes the in-memory config on the very first launch.
        if is_main_config(filename):
            studio_paths(config)
        return write_config(config, filename)

    # wgp.py imports these names after this point, so it gets the patched versions.
    config_store.read_config, config_store.write_config = read_patched, write_patched

    sys.argv = ["wgp.py"] + sys.argv[1:]
    runpy.run_path(os.path.join(APP_DIR, "wgp.py"), run_name="__main__")


if __name__ == "__main__":
    main()
