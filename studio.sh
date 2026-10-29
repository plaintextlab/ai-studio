#!/usr/bin/env bash
# Portable AI Studio — single entry point.
# Everything lives in this folder; nothing is installed on the host system.
set -euo pipefail

STUDIO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
export STUDIO_ROOT

# shellcheck source=config/versions.sh
. "$STUDIO_ROOT/config/versions.sh"
. "$STUDIO_ROOT/lib/common.sh"
. "$STUDIO_ROOT/lib/env.sh"
. "$STUDIO_ROOT/lib/runtime.sh"
. "$STUDIO_ROOT/lib/doctor.sh"
. "$STUDIO_ROOT/lib/apps.sh"
. "$STUDIO_ROOT/lib/menu.sh"

STUDIO_PLATFORM="$(detect_platform)"
export STUDIO_PLATFORM

usage() {
    cat <<EOF
Portable AI Studio

Usage: ./studio.sh [command]

  (no command)      Control panel: pick apps to start (each in its own terminal), stop any or all

  install <app>     Install an app with its pinned dependencies (apps: $(list_apps | tr '\n' ' '))
  start <app>       Start an app in the background and print its URL
  run <app>         Start an app and show its log here; Ctrl-C stops it
  start <app> --lan Same, reachable from phones/other devices on your network
  stop [app]        Stop an app (all apps when none is given)
  status            Show which apps are installed / running
  logs <app>        Follow an app's log
  doctor            Check GPU driver, drive, free space and runtime; explains any fix
  install-runtime   Download only the pinned runtime (uv, Python, ffmpeg, zig)
  lock <app>        Re-resolve an app's dependencies (only when updating its version)
  shell             Open a shell with the isolated studio environment
  env               Print the isolated environment variables
  clean-tmp         Delete temporary files (cache/tmp)

Downloads are kept in cache/downloads, so reinstalling works offline.
EOF
}

clean_tmp() {
    rm -rf "$STUDIO_ROOT/cache/tmp"; mkdir -p "$STUDIO_ROOT/cache/tmp"
    # Host-side install cache, only used on drives without symlinks.
    case "$UV_CACHE_DIR" in "$STUDIO_ROOT"/*) ;; *) rm -rf "$UV_CACHE_DIR" ;; esac
    ok "Temporary files cleared"
}

main() {
    local cmd="${1:-menu}"; [ $# -gt 0 ] && shift
    studio_env
    case "$cmd" in
        install)         install_app "${1:-}" ;;
        start)           start_app "${1:-}" "${@:2}" ;;
        run)             run_app "${1:-}" "${@:2}" ;;
        menu)            studio_menu ;;
        stop)            if [ -n "${1:-}" ]; then stop_app "$1"; else for a in $(list_apps); do stop_app "$a"; done; fi ;;
        status)          status_apps ;;
        logs)            load_recipe "${1:-}"; tail -n 50 -f "$LOG_FILE" ;;
        lock)            lock_app "${1:-}" ;;
        install-runtime) install_runtime ;;
        doctor)          run_doctor ;;
        shell)
            log "Studio shell (exit to leave). HOME=$HOME"
            PS1="(ai-studio) \w \$ " exec bash --norc -i ;;
        env)             env | grep -E '^(HOME|STUDIO_|XDG_|HF_|TORCH_|UV_|PIP_|PYTHON|TMPDIR|PATH=|GRADIO|TRITON|CUDA_CACHE|CC|CXX|ZIG_)' | sort ;;
        clean-tmp)       clean_tmp ;;
        help|-h|--help)  usage ;;
        *)               usage; die "Unknown command: $cmd" ;;
    esac
}

main "$@"
