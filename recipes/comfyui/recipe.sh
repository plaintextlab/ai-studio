# ComfyUI: node-based image/video generation. Loads any checkpoint you drop in
# models/comfyui/checkpoints (SD 1.5, SDXL, Flux, Chroma, ...), plus LoRAs etc.
# To update: pick a new release tag, set APP_COMMIT + APP_SRC_SHA256, run
# `./studio.sh lock comfyui`, then `./studio.sh install comfyui` and test.

APP_TITLE="ComfyUI"
APP_VERSION="0.42.1"
APP_REPO="comfyanonymous/ComfyUI"
APP_COMMIT="63be2f9b82ad0dac88a7ceaa28a9cf3154125a18"
APP_SRC_SHA256="0c0100cb1cb1b3dd07122bf882bd89bee08aa5d3ed3de1e8bfda243e98ad23dd"

APP_PYTHON="3.12"
APP_PORT=8188

# Same torch build as Wan2GP (CUDA 13), so both share the wheel cache.
APP_TORCH_BACKEND="cu130"
APP_UPSTREAM_REQUIREMENTS="requirements.txt"

# Custom nodes, pinned like the app: "name owner/repo commit sha256" per line.
# Their Python packages are listed in extra.in. To update one: set its commit and
# sha256 (of the codeload.github.com tarball), re-lock, reinstall and test.
#   ReActor v0.7.1: face swap (ReActorFaceSwap node). Downloads its models
#   (inswapper_128, face analysis, face restore) into models/comfyui on first use.
#   IPAdapter plus (2025-04-14): keeps a character's look from a reference image.
#   Models: models/comfyui/ipadapter + clip_vision (see README).
#   controlnet_aux (2026-10-11): pose/depth/line preprocessors. Only the packages its
#   DWPose and OpenPose nodes need are in extra.in; the others fail to load (logged at
#   startup). DWPose downloads its models into models/comfyui/annotators on first use.
APP_CUSTOM_NODES="
comfyui-reactor Gourieff/ComfyUI-ReActor 0b377b00d47daf08db5e572e5083435d63a94d48 40afea1b0bdec0c802cafa684a3ee74f50e12e7f6dd05c7917671590dcbf223a
comfyui-ipadapter-plus cubiq/ComfyUI_IPAdapter_plus a0f451a5113cf9becb0847b92884cb10cbdec0ef 7a349d80859a1a603623dd03f3d974ac261d1039a66c8e5b5eb5419d2f4f4e02
comfyui-controlnet-aux Fannovel16/comfyui_controlnet_aux a587a4c1594524c1d2de5cd1a86cd868ff448e1c a8a0e95ee92bafd60c9aa7f89a9cbf8b1a08c3c5da23caf1a7868f76d20406f3
"

# Arguments for launch.py (passed on to main.py), one per line.
# $1 = data/comfyui, $2 = port, $3 = host (127.0.0.1, or 0.0.0.0 with --lan).
# data/comfyui holds your own custom nodes, user settings/workflows and its database;
# models, inputs and outputs use the studio's shared folders.
app_launch_args() {
    printf '%s\n' --listen "$3" --port "$2" \
        --base-directory "$1" \
        --models-directory "$STUDIO_ROOT/models/comfyui" \
        --user-directory "$1/user" \
        --input-directory "$STUDIO_ROOT/inputs/comfyui" \
        --output-directory "$STUDIO_ROOT/outputs/comfyui" \
        --temp-directory "$STUDIO_ROOT/cache/tmp/comfyui" \
        --use-sage-attention --disable-auto-launch
}

APP_SMOKE_TEST='
import torch, torchvision, sageattention, triton, safetensors, aiohttp, comfy_kitchen
import onnxruntime, onnx, cv2, segment_anything
assert torch.cuda.is_available(), "PyTorch cannot see the GPU"
assert "CUDAExecutionProvider" in onnxruntime.get_available_providers(), "onnxruntime has no CUDA support"
x = torch.randn(256, 256, device="cuda"); (x @ x).sum().item()
print(f"torch {torch.__version__}, GPU {torch.cuda.get_device_name(0)}")
'
