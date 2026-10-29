# Wan2GP (WanGP): text/image-to-video and image generation, tuned for low VRAM.
# To update: pick a new commit, set APP_COMMIT + APP_SRC_SHA256, run
# `./studio.sh lock wan2gp`, then `./studio.sh install wan2gp` and test.

APP_TITLE="Wan2GP"
APP_VERSION="18.21"
APP_REPO="deepbeepmeep/WanGP"
APP_COMMIT="9d25c601f3c7074ca03c50f41b307a7c517835f9"
APP_SRC_SHA256="ab129a557518e9e7912323026f934bfcd1a696e30053be72061aebd7a1653534"

APP_PYTHON="3.11"
APP_PORT=7860

# Upstream's "RTX 30/40/50" profile: torch 2.10 + CUDA 13, Triton, Sage Attention 2.
# Left out because they only ship source that needs the CUDA toolkit to build:
# Sparge Attention, Flash Attention. Wan2GP falls back to Sage/SDPA without them.
APP_TORCH_BACKEND="cu130"
APP_EXTRA_INDEX="https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/ort-cuda-13-nightly/pypi/simple/"
APP_UPSTREAM_REQUIREMENTS="requirements.txt"

# Source-only packages with C/C++ code: built once with the bundled zig compiler
# and kept in cache/wheels, so later installs reuse them.
APP_BUILD_WHEELS="insightface diffq"

# Arguments for launch.py (passed on to wgp.py), one per line.
# $1 = data/wan2gp, $2 = port, $3 = host (127.0.0.1, or 0.0.0.0 with --lan).
# Settings, queue and workspaces live in data/ so updating the app source keeps them.
app_launch_args() {
    printf '%s\n' --server-name "$3" --server-port "$2" \
        --config "$1" --settings "$1/settings" \
        --workspaces-dir "$1/workspaces" --deepy-sessions-dir "$1/deepy_sessions"
}

# Checked after install; a failure here means the install is not usable.
APP_SMOKE_TEST='
import torch, torchvision, torchaudio, triton, sageattention, insightface, onnxruntime, gradio, diffusers, decord
assert torch.cuda.is_available(), "PyTorch cannot see the GPU"
x = torch.randn(256, 256, device="cuda"); (x @ x).sum().item()
assert "CUDAExecutionProvider" in onnxruntime.get_available_providers(), "onnxruntime has no CUDA support"
print(f"torch {torch.__version__}, GPU {torch.cuda.get_device_name(0)}")
'
