# `studio.sh doctor`: checks the host and the studio folder, and says in plain words
# what is wrong and how to fix it. Exit code is the number of hard failures.

DOCTOR_FAIL=0
fail() { err "$1"; [ -n "${2:-}" ] && printf '      %sfix:%s %s\n' "$C_YLW" "$C_RST" "$2" >&2; DOCTOR_FAIL=$((DOCTOR_FAIL + 1)); }
hint() { warn "$1"; [ -n "${2:-}" ] && printf '      %stip:%s %s\n' "$C_DIM" "$C_RST" "$2"; }

doctor_platform() {
    log "System"
    case "$STUDIO_PLATFORM" in
        linux-x64) ok "Platform: $STUDIO_PLATFORM" ;;
        *) fail "Platform $STUDIO_PLATFORM is not supported yet (only linux-x64 for now)." ;;
    esac
    local need; for need in tar xz; do
        command -v "$need" >/dev/null 2>&1 || fail "'$need' is missing on this system." "Install it with your package manager (it is part of every standard Linux install)."
    done
    command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 \
        || fail "Neither curl nor wget is installed; downloads are impossible." "Install curl."

    local mem_kb; mem_kb="$(awk '/^MemTotal/ {print $2}' /proc/meminfo 2>/dev/null)"
    if [ -n "$mem_kb" ]; then
        local mem_gb=$((mem_kb / 1024 / 1024))
        if [ "$mem_gb" -lt 15 ]; then hint "RAM: ${mem_gb} GB. Video models offload to RAM; 32 GB is recommended." "Close other programs; use the smallest model presets."
        else ok "RAM: ${mem_gb} GB"; fi
    fi
}

doctor_gpu() {
    log "GPU"
    if [ ! -r /proc/driver/nvidia/version ] && ! command -v nvidia-smi >/dev/null 2>&1; then
        fail "No NVIDIA driver found. Image/video generation needs an NVIDIA GPU with its driver installed." \
             "Install the proprietary NVIDIA driver ($MIN_NVIDIA_DRIVER or newer) from your distro, then reboot."
        return
    fi
    local drv=""
    if command -v nvidia-smi >/dev/null 2>&1; then
        drv="$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -1)"
    fi
    [ -n "$drv" ] || drv="$(grep -oE '[0-9]{3}\.[0-9]+(\.[0-9]+)?' /proc/driver/nvidia/version 2>/dev/null | head -1)"
    if [ -z "$drv" ]; then
        fail "NVIDIA driver is installed but not responding." "Reboot; if it persists, reinstall the driver (often happens after a kernel update)."
        return
    fi
    if [ "${drv%%.*}" -lt "$MIN_NVIDIA_DRIVER" ]; then
        fail "NVIDIA driver $drv is too old (need $MIN_NVIDIA_DRIVER+ for CUDA 13)." "Update the NVIDIA driver from your distro."
    else
        ok "NVIDIA driver $drv"
    fi
    command -v nvidia-smi >/dev/null 2>&1 || return
    local name mem cap gb
    while IFS=, read -r name mem cap; do
        mem="${mem// /}"; cap="${cap// /}"
        gb=$(( (mem + 512) / 1024 ))
        ok "GPU: $name, ${gb} GB VRAM (compute $cap)"
        if   [ "$gb" -lt 6 ];  then hint "Less than 6 GB VRAM: video generation will be very limited."
        elif [ "$gb" -lt 10 ]; then hint "${gb} GB VRAM: use the 8GB model presets."
        fi
        case "$cap" in [1-6].*) fail "GPU compute capability $cap is too old for current PyTorch (need 7.0+)." ;; esac
    done < <(nvidia-smi --query-gpu=name,memory.total,compute_cap --format=csv,noheader,nounits 2>/dev/null)
}

doctor_folder() {
    log "Studio folder: $STUDIO_ROOT"
    local fstype="" opts=""
    if command -v findmnt >/dev/null 2>&1; then
        fstype="$(findmnt -no FSTYPE -T "$STUDIO_ROOT" 2>/dev/null | head -1)"
        opts="$(findmnt -no OPTIONS -T "$STUDIO_ROOT" 2>/dev/null | head -1)"
    fi
    [ -n "$fstype" ] || fstype="$(stat -f -c %T "$STUDIO_ROOT" 2>/dev/null)"
    ok "Filesystem: ${fstype:-unknown}"
    if ! supports_symlinks; then
        ok "No symlink support (normal for exFAT/NTFS); install cache goes to $UV_CACHE_DIR"
        local tmp_free; tmp_free="$(df -Pk "$(dirname "$UV_CACHE_DIR")" | awk 'NR==2 {print int($4/1024/1024)}')"
        if [ "$tmp_free" -lt 12 ]; then
            hint "Only ${tmp_free} GB free in $(dirname "$UV_CACHE_DIR"); installing PyTorch needs ~12 GB there temporarily." \
                 "Free space in /tmp or /var/tmp before installing apps."
        fi
    fi
    case "$fstype" in
        vfat|msdos) fail "FAT32 cannot hold files over 4 GB; model files are larger." "Use exFAT, NTFS, ext4 or btrfs." ;;
        ntfs|fuseblk) hint "NTFS via FUSE is slower than other filesystems." "Fine for models/outputs; startup will be a bit slower." ;;
    esac

    if can_exec_here; then
        ok "Programs can run from this drive"
    else
        local dev; dev="$(findmnt -no SOURCE -T "$STUDIO_ROOT" 2>/dev/null | head -1)"
        local mnt; mnt="$(findmnt -no TARGET -T "$STUDIO_ROOT" 2>/dev/null | head -1)"
        fail "The drive is mounted with 'noexec', so programs on it cannot run." \
             "sudo mount -o remount,exec ${mnt:-<mountpoint>}   (device ${dev:-?}; add 'exec' to its fstab/automount options to make it permanent)"
    fi
    case "$STUDIO_ROOT" in
        *" "*) hint "The folder path contains spaces; some AI tools break on such paths." "Move or rename it to a path without spaces." ;;
    esac

    local free_gb; free_gb="$(df -Pk "$STUDIO_ROOT" | awk 'NR==2 {print int($4/1024/1024)}')"
    if   [ "$free_gb" -lt 20 ];  then fail "Only ${free_gb} GB free. Apps + one model set need 40 GB or more." "Free up space or use a bigger drive."
    elif [ "$free_gb" -lt 80 ];  then hint "${free_gb} GB free. A few video models alone can use 50+ GB."
    else ok "Free space: ${free_gb} GB"; fi
}

doctor_runtime() {
    log "Runtime (runtime/$STUDIO_PLATFORM)"
    local rt="$STUDIO_ROOT/runtime/$STUDIO_PLATFORM" missing=0
    if [ -x "$rt/bin/uv" ] && v="$("$rt/bin/uv" --version 2>/dev/null)"; then
        [ "$(cat "$rt/bin/.version" 2>/dev/null)" = "uv-$UV_VERSION" ] && ok "$v" || { hint "$v differs from pinned $UV_VERSION"; missing=1; }
    else hint "uv not installed"; missing=1; fi

    local py; py="$(studio_python "$PY_DEFAULT")"
    if [ -x "$py" ] && v="$("$py" --version 2>&1)"; then ok "$v"
    else hint "Python $PY_DEFAULT not installed"; missing=1; fi

    if [ -x "$rt/ffmpeg/bin/ffmpeg" ] && v="$("$rt/ffmpeg/bin/ffmpeg" -hide_banner -version 2>/dev/null | head -1)"; then ok "${v% Copyright*}"
    else hint "ffmpeg not installed"; missing=1; fi

    [ "$missing" -eq 0 ] || hint "Runtime incomplete." "./studio.sh install-runtime"
}

doctor_isolation() {
    log "Isolation"
    ok "HOME for apps: $HOME"
    ok "Caches: $STUDIO_ROOT/cache, models: $STUDIO_ROOT/models"
    if [ -z "${LD_LIBRARY_PATH:-}" ]; then ok "LD_LIBRARY_PATH cleared (system CUDA cannot interfere)"; fi
}

doctor_ports() {
    log "Ports"
    local p busy=""
    for p in 7860 7861 7862 7870; do
        (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null && busy="$busy $p"
    done
    if [ -n "$busy" ]; then hint "Ports in use:$busy (apps will pick another free port)."
    else ok "App ports 7860-7870 are free"; fi
}

run_doctor() {
    doctor_platform
    doctor_gpu
    doctor_folder
    doctor_runtime
    doctor_isolation
    doctor_ports
    echo
    if [ "$DOCTOR_FAIL" -eq 0 ]; then log "${C_GRN}All good.${C_RST}"
    else log "${C_RED}$DOCTOR_FAIL problem(s) found — see the 'fix:' lines above.${C_RST}"; fi
    return "$DOCTOR_FAIL"
}
