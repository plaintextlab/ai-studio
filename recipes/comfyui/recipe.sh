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

# Models the bundled workflows (recipes/comfyui/workflows) need, downloaded on install
# or on the first start: "path url sha256" per line, path under models/comfyui (about
# 35 GB in all). Models used through a node (ReActor face restore and analysis, DWPose)
# download themselves on first use; inswapper does not, so it is listed here.
HF="https://huggingface.co"
APP_MODELS="
checkpoints/sd_xl_base_1.0.safetensors $HF/stabilityai/stable-diffusion-xl-base-1.0/resolve/main/sd_xl_base_1.0.safetensors 31e35c80fc4829d14f90153f4c74cd59c90b779f6afe05a74cd6120b893f7e5b
diffusion_models/z_image_turbo_bf16.safetensors $HF/Comfy-Org/Z-Image-Turbo/resolve/main/split_files/diffusion_models/z_image_turbo_bf16.safetensors 2407613050b809ffdff18a4ac99af83ea6b95443ecebdf80e064a79c825574a6
text_encoders/qwen_3_4b.safetensors $HF/Comfy-Org/Z-Image-Turbo/resolve/main/split_files/text_encoders/qwen_3_4b.safetensors 6c671498573ac2f7a5501502ccce8d2b08ea6ca2f661c458e708f36b36edfc5a
vae/ae.safetensors $HF/Comfy-Org/Z-Image-Turbo/resolve/main/split_files/vae/ae.safetensors afc8e28272cd15db3919bacdb6918ce9c1ed22e96cb12c4d5ed0fba823529e38
insightface/inswapper_128.onnx $HF/datasets/Gourieff/ReActor/resolve/main/models/inswapper_128.onnx e4a3f08c753cb72d04e10aa0f7dbe3deebbf39567d4ead6dce08e98aa49e16af
clip_vision/CLIP-ViT-H-14-laion2B-s32B-b79K.safetensors $HF/h94/IP-Adapter/resolve/main/models/image_encoder/model.safetensors 6ca9667da1ca9e0b0f75e46bb030f7e011f44f86cbfb8d5a36590fcd7507b030
ipadapter/ip-adapter-plus_sdxl_vit-h.safetensors $HF/h94/IP-Adapter/resolve/main/sdxl_models/ip-adapter-plus_sdxl_vit-h.safetensors 3f5062b8400c94b7159665b21ba5c62acdcd7682262743d7f2aefedef00e6581
ipadapter/ip-adapter-plus_sd15.safetensors $HF/h94/IP-Adapter/resolve/main/models/ip-adapter-plus_sd15.safetensors a1c250be40455cc61a43da1201ec3f1edaea71214865fb47f57927e06cbe4996
controlnet/controlnet-openpose-sdxl-1.0.safetensors $HF/xinsir/controlnet-openpose-sdxl-1.0/resolve/main/diffusion_pytorch_model.safetensors b8524e557a7df60d081f5d4a0eb109967d107df217943bf88c2d99b9ebcc06c5
controlnet/control_v11p_sd15_openpose_fp16.safetensors $HF/comfyanonymous/ControlNet-v1-1_fp16_safetensors/resolve/main/control_v11p_sd15_openpose_fp16.safetensors 4003c1da17b0e4ba444e02140e1c0d83bb24b79e4dcfd613c3a554d38f0f89c7
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
