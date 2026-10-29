# Isolated environment: every cache, config and temp file lands inside the studio
# folder, so the host system is never touched. Sourced by studio.sh.

studio_env() {
    local R="$STUDIO_ROOT" D="$STUDIO_ROOT/data"
    local RT="$STUDIO_ROOT/runtime/$STUDIO_PLATFORM"

    # Remembered for doctor output; apps never see the real home.
    export STUDIO_REAL_HOME="${STUDIO_REAL_HOME:-$HOME}"

    # Catch-all: tools that ignore XDG (insightface, gradio, ...) write to $HOME.
    export HOME="$D/home"
    export XDG_CACHE_HOME="$R/cache/xdg"
    export XDG_CONFIG_HOME="$D/config"
    export XDG_DATA_HOME="$D/share"
    export XDG_STATE_HOME="$D/state"
    export TMPDIR="$R/cache/tmp"
    export TMP="$TMPDIR" TEMP="$TMPDIR"

    # Model / ML caches
    export HF_HOME="$R/models/huggingface"
    export TORCH_HOME="$R/models/torch"
    # Plain HTTPS downloads: the hf_xet backend has stalled mid-download (open but
    # idle connections, no error); HTTPS resumes reliably from the partial file.
    export HF_HUB_DISABLE_XET=1
    export TRITON_CACHE_DIR="$R/cache/triton"
    export CUDA_CACHE_PATH="$R/cache/nv"
    export MPLCONFIGDIR="$R/cache/matplotlib"
    export GRADIO_TEMP_DIR="$R/cache/gradio"
    export NUMBA_CACHE_DIR="$R/cache/numba"

    # Python / uv: ignore anything the user has configured on the host.
    export UV_CACHE_DIR="$R/cache/uv"
    if ! supports_symlinks; then
        # uv's cache needs symlinks (exFAT/NTFS lack them), even with UV_NO_CACHE.
        # It is disposable, so put it in a host temp area; installers wipe it afterwards.
        export UV_CACHE_DIR; UV_CACHE_DIR="$(host_temp_dir)/ai-studio-uv-cache-$(id -u)"
    fi
    export UV_LINK_MODE=copy          # no hardlinks/symlinks: works on exFAT/NTFS
    export UV_NO_CONFIG=1
    export UV_PYTHON_DOWNLOADS=never  # Python comes only from our pinned builds
    export PIP_CACHE_DIR="$R/cache/pip"
    export PIP_CONFIG_FILE=/dev/null
    export PYTHONNOUSERSITE=1
    export PYTHONUTF8=1
    unset PYTHONPATH PYTHONHOME PYTHONSTARTUP VIRTUAL_ENV CONDA_PREFIX CONDA_DEFAULT_ENV
    # A system CUDA toolkit on LD_LIBRARY_PATH can shadow the CUDA libs bundled in
    # the PyTorch wheels and cause hard-to-read errors.
    unset LD_LIBRARY_PATH

    toolchain_env

    # Apps that use GitPython must not crash when git is not installed.
    export GIT_PYTHON_REFRESH=quiet

    # No telemetry
    export HF_HUB_DISABLE_TELEMETRY=1 GRADIO_ANALYTICS_ENABLED=False DO_NOT_TRACK=1

    export PATH="$RT/bin:$RT/ffmpeg/bin:$PATH"

    mkdir -p "$HOME" "$XDG_CACHE_HOME" "$XDG_CONFIG_HOME" "$XDG_DATA_HOME" "$XDG_STATE_HOME" \
        "$TMPDIR" "$HF_HOME" "$TORCH_HOME" "$R/outputs" "$R/inputs"
}

# Runs a host program (terminal, browser) with the user's real home and config, not
# the studio's isolated ones.
host_cmd() {
    env -u XDG_CONFIG_HOME -u XDG_CACHE_HOME -u XDG_DATA_HOME -u XDG_STATE_HOME \
        -u TMPDIR -u TMP -u TEMP HOME="$STUDIO_REAL_HOME" "$@"
}

# Bundled compiler: builds source-only packages and is the C compiler Triton needs at
# runtime. Used even if the host has gcc, so results are the same everywhere.
# Also called right after zig is installed, since studio_env ran before it existed.
toolchain_env() {
    local R="$STUDIO_ROOT"
    if [ -x "$R/runtime/$STUDIO_PLATFORM/zig/zig" ]; then
        export CC="$R/lib/toolchain/zig-cc" CXX="$R/lib/toolchain/zig-c++"
        export LDSHARED="$CC -shared" LDCXXSHARED="$CXX -shared"
    fi
    export ZIG_GLOBAL_CACHE_DIR="$R/cache/zig" ZIG_LOCAL_CACHE_DIR="$R/cache/zig"
}

# Path to a pinned Python interpreter, e.g. studio_python 3.12
studio_python() {
    echo "$STUDIO_ROOT/runtime/$STUDIO_PLATFORM/python/$1/bin/python$1"
}
