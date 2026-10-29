# Pinned runtime components. Every download is verified against these hashes.
# To update: change the version + hash together, run `./studio.sh install-runtime`,
# then `./studio.sh doctor`. Never point at "latest".

# --- uv (package installer) ----------------------------------------------
UV_VERSION="0.13.3"
UV_URL_linux_x64="https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz"
UV_SHA256_linux_x64="e4f169ec380f1793b898a6f1fcbacd22db25cf7ca0349a6e96ffb85b69998d04"

# --- Python (python-build-standalone, relocatable) -------------------------
PBS_TAG="20261013"
PY_DEFAULT="3.12"
PY_FULL_3_11="3.11.17"
PY_FULL_3_12="3.12.15"
PY_FULL_3_13="3.13.16"
PY_SHA256_3_11_linux_x64="846bf682e2b67393f2affa5e5c0994e446abc3a7cc5bb699890c36346823644c"
PY_SHA256_3_12_linux_x64="5d546fc004fc2435fa142eb8f42ed8de5eb4725963dc22f7e6671014a80eb560"
PY_SHA256_3_13_linux_x64="e588e50db882fa6a89f926d9a9ad64e445183af0eda5bda226a2c151b17db8a6"
PBS_TRIPLE_linux_x64="x86_64-unknown-linux-gnu"

# --- FFmpeg (BtbN static build; month-end builds are kept long term) -----
FFMPEG_TAG="autobuild-2026-09-30-13-08"
FFMPEG_NAME_linux_x64="ffmpeg-n8.1.3-9-g29e619e767-linux64-gpl-8.1"
FFMPEG_SHA256_linux_x64="97ce978979194b5cf7e06a5e68020dbdaa7a4f3294c5452b6a1bc347100dbb79"

# --- Zig (portable C/C++ compiler, from the ziglang PyPI wheel) ------------
# Builds the few packages that only ship source (e.g. insightface) and serves as
# the C compiler Triton needs at runtime. Nothing is installed on the host.
ZIG_VERSION="0.15.2"
ZIG_URL_linux_x64="https://files.pythonhosted.org/packages/db/81/f9f5c7fd68d80b8edff2a641a1c95ac4125356387d298374227040b8e1e8/ziglang-0.15.2-py3-none-manylinux_2_12_x86_64.manylinux2010_x86_64.musllinux_1_1_x86_64.whl"
ZIG_SHA256_linux_x64="806316453d1ede7174ed3fefb8e5348154629089c3bb5fe812dfd0e172fac130"

# --- Host requirements ----------------------------------------------------
# Apps use CUDA 13 wheels (Wan2GP: torch 2.10 cu130; FaceFusion: onnxruntime cuda@13),
# which need driver >= 580. RTX 50xx (Blackwell) needs at least CUDA 12.8 anyway.
MIN_NVIDIA_DRIVER="580"
