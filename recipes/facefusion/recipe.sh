# FaceFusion: face swap (and face enhancement) for images and videos.
# To update: pick a new release tag, set APP_COMMIT + APP_SRC_SHA256, run
# `./studio.sh lock facefusion`, then `./studio.sh install facefusion` and test.

APP_TITLE="FaceFusion"
APP_VERSION="3.9.1"
APP_REPO="facefusion/facefusion"
APP_COMMIT="72470819a0373be3388b3929c8f8f311f418fc3c"
APP_SRC_SHA256="461c90d90ef6a4d088ea71a53e88f9173cfbbcf5a8015e0f09a7e4ac34b1169a"

APP_PYTHON="3.12"
APP_PORT=7870

# Upstream installs the CPU onnxruntime from requirements.txt and swaps in the GPU
# build ("cuda@13" choice: onnxruntime-gpu 1.30.0). Its CUDA/cuDNN libraries normally
# come from conda; here they come from NVIDIA's pip wheels (the [cuda,cudnn] extras).
APP_UPSTREAM_REQUIREMENTS="requirements.txt"
APP_REQUIREMENTS_EXCLUDE="onnxruntime"

# Arguments for launch.py, one per line.
# $1 = data/facefusion, $2 = port, $3 = host (127.0.0.1, or 0.0.0.0 with --lan).
app_launch_args() {
    printf '%s\n' --studio-host "$3" --studio-port "$2" run \
        --jobs-path "$1/jobs" \
        --output-path "$STUDIO_ROOT/outputs/facefusion" \
        --execution-providers cuda
}

APP_SMOKE_TEST='
import onnxruntime
onnxruntime.preload_dlls()
providers = onnxruntime.get_available_providers()
assert "CUDAExecutionProvider" in providers, f"onnxruntime has no CUDA support: {providers}"
import cv2, gradio, numpy
print(f"onnxruntime {onnxruntime.__version__} with CUDA, gradio {gradio.__version__}")
'
