# Installs the pinned runtime into runtime/<platform>/:
#   bin/uv            package installer
#   python/<ver>/     standalone Python builds (one per version apps need)
#   ffmpeg/bin/       static ffmpeg + ffprobe
#   zig/              C/C++ compiler (used through lib/toolchain/zig-cc)
# Each component writes a .version stamp so re-runs skip what is already correct.

RT_DIR() { echo "$STUDIO_ROOT/runtime/$STUDIO_PLATFORM"; }

stamp_ok()    { [ -f "$1/.version" ] && [ "$(cat "$1/.version")" = "$2" ]; }
write_stamp() { echo "$2" > "$1/.version"; }

install_uv() {
    local dir; dir="$(RT_DIR)/bin"
    if stamp_ok "$dir" "uv-$UV_VERSION" && [ -x "$dir/uv" ]; then ok "uv $UV_VERSION (already installed)"; return; fi
    local archive; archive="$(fetch "$(pin UV_URL)" "$(pin UV_SHA256)" "uv-$UV_VERSION-$STUDIO_PLATFORM.tar.gz")"
    local tmp="$STUDIO_ROOT/cache/tmp/uv"
    extract_portable "$archive" "$tmp"
    mkdir -p "$dir"
    cp "$tmp"/*/uv "$tmp"/*/uvx "$dir/"
    chmod +x "$dir/uv" "$dir/uvx" 2>/dev/null || true
    rm -rf "$tmp"
    write_stamp "$dir" "uv-$UV_VERSION"
    ok "uv $UV_VERSION"
}

# install_python 3.12
install_python() {
    local ver="$1" key="${1//./_}"
    local full_var="PY_FULL_$key"; local full="${!full_var:-}"
    [ -n "$full" ] || die "Python $ver is not pinned in config/versions.sh"
    local dir; dir="$(RT_DIR)/python/$ver"
    local want="cpython-$full+$PBS_TAG"
    if stamp_ok "$dir" "$want" && [ -x "$dir/bin/python$ver" ]; then ok "Python $full (already installed)"; return; fi

    local name="cpython-$full+$PBS_TAG-$(pin PBS_TRIPLE)-install_only.tar.gz"
    local url="https://github.com/astral-sh/python-build-standalone/releases/download/$PBS_TAG/${name//+/%2B}"
    local archive; archive="$(fetch "$url" "$(pin "PY_SHA256_$key")" "$name")"

    # share/ holds terminfo/man pages: unused, and terminfo has names that differ
    # only in case, which collide on exFAT/NTFS/APFS.
    extract_portable "$archive" "$dir.new" "python/share"
    rm -rf "$dir"; mv "$dir.new/python" "$dir"; rm -rf "$dir.new"
    chmod +x "$dir/bin/python$ver" 2>/dev/null || true
    # Mark it as ours; apps get packages installed straight into it (no venvs:
    # venvs rely on symlinks and absolute paths, which break when the folder moves).
    "$dir/bin/python$ver" -c 'import sys, ssl, sqlite3, ctypes; assert sys.version_info[:2] == tuple(map(int, "'"$ver"'".split(".")))' \
        || die "Python $ver installed but does not run correctly."
    write_stamp "$dir" "$want"
    ok "Python $full"
}

install_ffmpeg() {
    local dir; dir="$(RT_DIR)/ffmpeg"
    local name; name="$(pin FFMPEG_NAME)"
    if stamp_ok "$dir" "$name" && [ -x "$dir/bin/ffmpeg" ]; then ok "ffmpeg (already installed)"; return; fi
    local url="https://github.com/BtbN/FFmpeg-Builds/releases/download/$FFMPEG_TAG/$name.tar.xz"
    local archive; archive="$(fetch "$url" "$(pin FFMPEG_SHA256)" "$name.tar.xz")"
    # ffplay is a desktop player, not needed by any app.
    extract_portable "$archive" "$dir.new" "*/bin/ffplay" "*/doc" "*/man"
    rm -rf "$dir"; mkdir -p "$dir"; mv "$dir.new"/*/bin "$dir/bin"; rm -rf "$dir.new"
    chmod +x "$dir/bin/ffmpeg" "$dir/bin/ffprobe" 2>/dev/null || true
    "$dir/bin/ffmpeg" -hide_banner -version >/dev/null || die "ffmpeg installed but does not run."
    write_stamp "$dir" "$name"
    ok "ffmpeg $(echo "$name" | cut -d- -f2)"
}

install_zig() {
    local dir; dir="$(RT_DIR)/zig"
    if stamp_ok "$dir" "zig-$ZIG_VERSION" && [ -x "$dir/zig" ]; then ok "zig $ZIG_VERSION (already installed)"; return; fi
    local archive; archive="$(fetch "$(pin ZIG_URL)" "$(pin ZIG_SHA256)" "ziglang-$ZIG_VERSION-$STUDIO_PLATFORM.whl")"
    local py; py="$(studio_python "$PY_DEFAULT")"
    rm -rf "$dir.new"; mkdir -p "$dir.new"
    # A wheel is a zip; unzip with our own Python so the host needs no unzip tool.
    "$py" -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$archive" "$dir.new" \
        || die "Failed to extract zig"
    rm -rf "$dir"; mv "$dir.new/ziglang" "$dir"; rm -rf "$dir.new"
    chmod +x "$dir/zig" 2>/dev/null || true
    "$dir/zig" version >/dev/null || die "zig installed but does not run."
    write_stamp "$dir" "zig-$ZIG_VERSION"
    ok "zig $ZIG_VERSION (C/C++ compiler)"
}

install_runtime() {
    log "Installing runtime for $STUDIO_PLATFORM into runtime/$STUDIO_PLATFORM"
    can_exec_here || die "This drive is mounted 'noexec', so programs on it cannot run. Run ./studio.sh doctor for the fix."
    install_uv
    install_python "$PY_DEFAULT"
    install_ffmpeg
    install_zig
    toolchain_env
    log "Runtime ready."
}
