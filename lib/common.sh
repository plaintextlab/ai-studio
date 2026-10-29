# Shared helpers. Sourced by studio.sh; expects STUDIO_ROOT to be set.
# Only depends on bash, coreutils, tar/xz and curl or wget — nothing else on the host.

if [ -t 1 ]; then
    C_RED=$'\e[31m'; C_GRN=$'\e[32m'; C_YLW=$'\e[33m'; C_BLU=$'\e[34m'; C_DIM=$'\e[2m'; C_RST=$'\e[0m'
else
    C_RED=""; C_GRN=""; C_YLW=""; C_BLU=""; C_DIM=""; C_RST=""
fi

log()  { printf '%s==>%s %s\n' "$C_BLU" "$C_RST" "$*"; }
ok()   { printf '  %s✔%s %s\n' "$C_GRN" "$C_RST" "$*"; }
warn() { printf '  %s!%s %s\n' "$C_YLW" "$C_RST" "$*"; }
err()  { printf '  %s✘%s %s\n' "$C_RED" "$C_RST" "$*" >&2; }
die()  { err "$*"; exit 1; }

# Prints the platform key used in folder names and versions.sh (e.g. linux-x64).
detect_platform() {
    local os arch
    case "$(uname -s)" in
        Linux) os=linux ;;
        Darwin) os=macos ;;
        MINGW*|MSYS*|CYGWIN*) os=windows ;;
        *) os=unknown ;;
    esac
    case "$(uname -m)" in
        x86_64|amd64) arch=x64 ;;
        aarch64|arm64) arch=arm64 ;;
        *) arch=unknown ;;
    esac
    echo "${os}-${arch}"
}

# versions.sh variables use underscores: linux-x64 -> linux_x64
platform_var() { echo "${STUDIO_PLATFORM//-/_}"; }

# Reads a pinned value such as UV_URL_linux_x64 for the current platform.
pin() {
    local name="${1}_$(platform_var)"
    [ -n "${!name:-}" ] || die "No pinned value '$name' for platform $STUDIO_PLATFORM (not supported yet)."
    echo "${!name}"
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# fetch URL SHA256 FILENAME -> prints path of the verified file in cache/downloads.
# Downloads are kept, so reinstalling later works offline.
fetch() {
    local url="$1" sha="$2" name="$3"
    local dir="$STUDIO_ROOT/cache/downloads" dest
    dest="$dir/$name"
    mkdir -p "$dir"
    if [ -f "$dest" ] && [ "$(sha256_of "$dest")" = "$sha" ]; then
        echo "$dest"; return 0
    fi
    rm -f "$dest"
    printf '  %sdownloading %s%s\n' "$C_DIM" "$name" "$C_RST" >&2
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --retry-delay 2 -C - --progress-bar -o "$dest.part" "$url" >&2 \
            || die "Download failed: $url"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress -c -O "$dest.part" "$url" >&2 || die "Download failed: $url"
    else
        die "Need curl or wget to download files."
    fi
    local got; got="$(sha256_of "$dest.part")"
    if [ "$got" != "$sha" ]; then
        rm -f "$dest.part"
        die "Checksum mismatch for $name (expected $sha, got $got)."
    fi
    mv "$dest.part" "$dest"
    echo "$dest"
}

# extract_portable ARCHIVE DEST [EXCLUDE_GLOB...]
# Extracts without symlinks so the result works on exFAT/NTFS/APFS too.
# Symlinks in the archive are skipped; callers refer to real file names instead.
# DEST is replaced atomically: extracted next to it, then moved into place.
extract_portable() {
    local archive="$1" dest="$2"; shift 2
    local work="$STUDIO_ROOT/cache/tmp/extract.$$"
    rm -rf "$work"; mkdir -p "$work/out"

    local list_flags="-tvf" x_flags="-xf"
    case "$archive" in
        *.tar.xz) list_flags="-tvJf"; x_flags="-xJf" ;;
        *.tar.gz|*.tgz) list_flags="-tvzf"; x_flags="-xzf" ;;
    esac

    # Collect symlink entries ("l" in the mode column; name is before " -> ").
    tar $list_flags "$archive" | awk '/^l/ { sub(/ -> .*/, ""); print $NF }' > "$work/exclude"
    local pat; for pat in "$@"; do echo "$pat" >> "$work/exclude"; done

    # --no-same-owner/permissions: exFAT/NTFS cannot store them anyway.
    tar $x_flags "$archive" -C "$work/out" --no-same-owner --no-same-permissions \
        --exclude-from="$work/exclude" || die "Failed to extract $(basename "$archive")"

    rm -rf "$dest.old"
    [ -e "$dest" ] && mv "$dest" "$dest.old"
    mkdir -p "$(dirname "$dest")"
    mv "$work/out" "$dest"
    rm -rf "$dest.old" "$work"
}

# True if the studio drive can store symlinks (ext4/btrfs/APFS yes; exFAT/NTFS no).
supports_symlinks() {
    local probe="$STUDIO_ROOT/cache/tmp/link-probe.$$"
    mkdir -p "$(dirname "$probe")"
    local rc=0; ln -s target "$probe" 2>/dev/null || rc=1
    rm -f "$probe"
    return $rc
}

# Host temp area with the most free space that supports symlinks. Used only when the
# studio drive cannot hold symlinks. PyTorch + CUDA wheels need ~10 GB here while
# installing; /run/user is often a small RAM disk, so it is just one candidate.
host_temp_dir() {
    local best="/tmp" best_free=-1 d free
    for d in /tmp /var/tmp "${XDG_RUNTIME_DIR:-}"; do
        [ -n "$d" ] && [ -d "$d" ] && [ -w "$d" ] || continue
        local probe="$d/.ai-studio-link-probe.$$"
        ln -s target "$probe" 2>/dev/null || continue
        rm -f "$probe"
        free="$(df -Pk "$d" | awk 'NR==2 {print $4}')"
        if [ "$free" -gt "$best_free" ]; then best="$d"; best_free="$free"; fi
    done
    echo "$best"
}

# True if the studio folder sits on a mount where binaries can run.
can_exec_here() {
    local probe="$STUDIO_ROOT/cache/tmp/exec-probe.$$"
    mkdir -p "$(dirname "$probe")"
    printf '#!/bin/sh\nexit 0\n' > "$probe"
    chmod +x "$probe" 2>/dev/null
    local rc=0; "$probe" 2>/dev/null || rc=1
    rm -f "$probe"
    return $rc
}
