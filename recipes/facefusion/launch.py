"""Starts FaceFusion with its models in the studio's shared models folder.

FaceFusion builds every model path with filesystem.resolve_relative_path('../.assets/...'),
i.e. inside the app folder, which is replaced on every update. Patching that one helper
before the other modules import it moves models to models/facefusion instead.

Run with the app folder as the working directory:
    python launch.py [--studio-host HOST] [--studio-port PORT] <facefusion.py args...>
"""
import os
import runpy
import sys

STUDIO = os.environ["STUDIO_ROOT"]
APP_DIR = os.getcwd()
ASSETS = os.path.join(STUDIO, "models", "facefusion")


def main():
    args = sys.argv[1:]
    # FaceFusion has no host/port options; Gradio reads these variables instead.
    os.environ.setdefault("GRADIO_SERVER_NAME", "127.0.0.1")
    while args[:1] in (["--studio-host"], ["--studio-port"]):
        name = "GRADIO_SERVER_NAME" if args[0] == "--studio-host" else "GRADIO_SERVER_PORT"
        os.environ[name] = args[1]
        args = args[2:]
    for i, a in enumerate(args[:-1]):
        if a in ("--jobs-path", "--output-path"):
            os.makedirs(args[i + 1], exist_ok=True)
    os.makedirs(ASSETS, exist_ok=True)
    sys.path.insert(0, APP_DIR)

    # CUDA/cuDNN come from NVIDIA's pip wheels instead of conda; load them before
    # any ONNX session is created, or the CUDA provider silently falls back to CPU.
    import onnxruntime
    onnxruntime.preload_dlls()

    from facefusion import filesystem

    resolve_relative_path = filesystem.resolve_relative_path

    def studio_relative_path(path):
        if path.startswith("../.assets"):
            return os.path.join(ASSETS, path[len("../.assets"):].lstrip("/"))
        return resolve_relative_path(path)

    filesystem.resolve_relative_path = studio_relative_path

    sys.argv = ["facefusion.py"] + args
    runpy.run_path(os.path.join(APP_DIR, "facefusion.py"), run_name="__main__")


if __name__ == "__main__":
    main()
