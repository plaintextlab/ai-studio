# App management: install / lock / start / stop. Each app is described by
# recipes/<app>/recipe.sh; its pinned dependencies are in recipes/<app>/<platform>.lock.
#
#   apps/<app>/                 app source at the pinned commit (replaced on update)
#   envs/<platform>/<app>/      the app's own Python with its packages (no venv)
#   data/<app>/                 app settings/state, kept across updates
#   data/logs/<app>.log, data/run/<app>.pid

APP_VARS="APP_TITLE APP_VERSION APP_REPO APP_COMMIT APP_SRC_SHA256 APP_PYTHON APP_PORT
APP_TORCH_BACKEND APP_EXTRA_INDEX APP_UPSTREAM_REQUIREMENTS APP_REQUIREMENTS_EXCLUDE
APP_BUILD_WHEELS APP_SMOKE_TEST APP_CUSTOM_NODES"

list_apps() {
    local d; for d in "$STUDIO_ROOT"/recipes/*/recipe.sh; do basename "$(dirname "$d")"; done
}

load_recipe() {
    APP="$1"
    local recipe="$STUDIO_ROOT/recipes/$APP/recipe.sh"
    [ -n "$APP" ] && [ -f "$recipe" ] || die "Unknown app '$APP'. Available: $(list_apps | tr '\n' ' ')"
    local v; for v in $APP_VARS; do unset "$v"; done
    unset -f app_launch_args
    . "$recipe"
    RECIPE_DIR="$STUDIO_ROOT/recipes/$APP"
    APP_DIR="$STUDIO_ROOT/apps/$APP"
    ENV_DIR="$STUDIO_ROOT/envs/$STUDIO_PLATFORM/$APP"
    APP_DATA="$STUDIO_ROOT/data/$APP"
    LOCK_FILE="$RECIPE_DIR/$STUDIO_PLATFORM.lock"
    BUILD_FILE="$RECIPE_DIR/$STUDIO_PLATFORM.build.txt"
    WHEEL_DIR="$STUDIO_ROOT/cache/wheels/$STUDIO_PLATFORM/py$APP_PYTHON"
    APP_PY="$ENV_DIR/bin/python$APP_PYTHON"
    PID_FILE="$STUDIO_ROOT/data/run/$APP.pid"
    LOG_FILE="$STUDIO_ROOT/data/logs/$APP.log"
}

# Common uv options for resolving/installing this app's packages.
uv_index_opts() {
    UV_OPTS=(--index-strategy unsafe-best-match)
    [ -n "${APP_TORCH_BACKEND:-}" ] && UV_OPTS+=(--torch-backend "$APP_TORCH_BACKEND")
    [ -n "${APP_EXTRA_INDEX:-}" ] && UV_OPTS+=(--extra-index-url "$APP_EXTRA_INDEX")
    return 0
}

# Host-side uv cache (drives without symlinks) is only scratch space; free it.
cleanup_host_cache() {
    case "$UV_CACHE_DIR" in "$STUDIO_ROOT"/*) ;; *) rm -rf "$UV_CACHE_DIR" ;; esac
}

fetch_app_source() {
    if [ "$(cat "$APP_DIR/.studio-commit" 2>/dev/null)" = "$APP_COMMIT" ]; then
        ok "$APP_TITLE source $APP_VERSION (already present)"; return
    fi
    local url="https://codeload.github.com/$APP_REPO/tar.gz/$APP_COMMIT"
    local archive; archive="$(fetch "$url" "$APP_SRC_SHA256" "$APP-$APP_COMMIT.tar.gz")"
    extract_portable "$archive" "$APP_DIR.new"
    rm -rf "$APP_DIR"; mv "$APP_DIR.new"/* "$APP_DIR"; rm -rf "$APP_DIR.new"
    echo "$APP_COMMIT" > "$APP_DIR/.studio-commit"
    ok "$APP_TITLE source $APP_VERSION (${APP_COMMIT:0:10})"
}

# Plugins pinned in the recipe (APP_CUSTOM_NODES: "name owner/repo commit sha256" per
# line), extracted to apps/<app>/custom_nodes/<name>. Their Python packages go in
# the recipe's extra.in, so they are locked with the app's own.
fetch_custom_nodes() {
    local name repo commit sha dir archive
    while read -r name repo commit sha; do
        case "$name" in ''|\#*) continue ;; esac
        dir="$APP_DIR/custom_nodes/$name"
        if [ "$(cat "$dir/.studio-commit" 2>/dev/null)" = "$commit" ]; then
            ok "$name (already present)"; continue
        fi
        archive="$(fetch "https://codeload.github.com/$repo/tar.gz/$commit" "$sha" "$name-$commit.tar.gz")"
        extract_portable "$archive" "$dir.new"
        rm -rf "$dir"; mv "$dir.new"/* "$dir"; rm -rf "$dir.new"
        echo "$commit" > "$dir/.studio-commit"
        ok "$name (${commit:0:10})"
    done <<< "${APP_CUSTOM_NODES:-}"
}

# ./studio.sh lock <app>: re-resolve all dependencies to exact, hashed versions.
# Only needed when updating an app; normal installs use the committed lock file.
lock_app() {
    load_recipe "$1"
    install_runtime >/dev/null
    install_python "$APP_PYTHON" >/dev/null
    fetch_app_source
    fetch_custom_nodes
    local req="$STUDIO_ROOT/cache/tmp/$APP-requirements.in" full="$STUDIO_ROOT/cache/tmp/$APP-full.lock"
    {
        cat "$RECIPE_DIR/extra.in"
        echo "# --- upstream $APP_UPSTREAM_REQUIREMENTS @ $APP_COMMIT"
        grep -vE '^\s*--' "$APP_DIR/$APP_UPSTREAM_REQUIREMENTS" \
            | grep -viE "^(${APP_REQUIREMENTS_EXCLUDE:-^$})([=<>~! ;\[]|$)"
    } > "$req"
    uv_index_opts
    log "Resolving $APP_TITLE dependencies for $STUDIO_PLATFORM"
    uv pip compile "$req" --python "$(studio_python "$APP_PYTHON")" \
        --python-platform x86_64-manylinux_2_28 "${UV_OPTS[@]}" \
        --generate-hashes --no-header --quiet -o "$full" || die "Dependency resolution failed."

    # Source-only packages go to a separate file: they are built locally, so their
    # wheel hashes cannot match the published source hashes.
    local builds="${APP_BUILD_WHEELS:-}" pkg pattern="^\$never"
    [ -n "$builds" ] && pattern="^(${builds// /|})=="
    {
        echo "# Built from source with the bundled compiler (see APP_BUILD_WHEELS)."
        if [ -n "$builds" ]; then grep -oE "$pattern[^ ]+" "$full"; fi
    } > "$BUILD_FILE"
    awk -v pat="$pattern" '/^[A-Za-z0-9]/ { skip = ($0 ~ pat) } !skip' "$full" > "$LOCK_FILE"
    sed -i "1i # $APP_TITLE $APP_VERSION ($APP_COMMIT), python $APP_PYTHON. Generated by ./studio.sh lock $APP" "$LOCK_FILE"
    for pkg in $builds; do
        grep -qiE "^$pkg==" "$BUILD_FILE" || warn "$pkg is listed in APP_BUILD_WHEELS but not in the lock"
    done
    ok "$(grep -cE '^[A-Za-z0-9]' "$LOCK_FILE") packages locked in recipes/$APP/$(basename "$LOCK_FILE")"
    cleanup_host_cache
}

# Builds wheels for source-only packages once; later installs reuse them.
build_wheels() {
    local py="$1" spec name
    [ -n "${CC:-}" ] || die "Bundled compiler not set up (runtime/$STUDIO_PLATFORM/zig missing). Run ./studio.sh install-runtime"
    mkdir -p "$WHEEL_DIR"
    while read -r spec; do
        case "$spec" in ''|\#*) continue ;; esac
        name="${spec%%==*}"
        if ls "$WHEEL_DIR/${name//-/_}-${spec#*==}-"*.whl >/dev/null 2>&1; then
            ok "$spec (prebuilt wheel cached)"; continue
        fi
        printf '  %sbuilding %s with bundled compiler...%s\n' "$C_DIM" "$spec" "$C_RST"
        "$py" -m pip wheel --quiet --no-deps --disable-pip-version-check -w "$WHEEL_DIR" "$spec" \
            >"$STUDIO_ROOT/cache/tmp/build-$name.log" 2>&1 \
            || die "Building $spec failed; details in cache/tmp/build-$name.log"
        ok "$spec (built)"
    done < "$BUILD_FILE"
}

install_app() {
    load_recipe "$1"
    [ -f "$LOCK_FILE" ] || die "No lock file for $APP on $STUDIO_PLATFORM (recipes/$APP/$(basename "$LOCK_FILE"))."
    install_runtime
    log "Installing $APP_TITLE $APP_VERSION"
    install_python "$APP_PYTHON"
    fetch_app_source
    fetch_custom_nodes

    local want; want="$(cat "$LOCK_FILE" "$BUILD_FILE" | sha256_of /dev/stdin)"
    if [ "$(cat "$ENV_DIR/.studio-lock" 2>/dev/null)" = "$want" ] && [ -x "$APP_PY" ]; then
        ok "Python packages (already installed)"
    else
        # Build the environment next to the old one; a failed install keeps the old one working.
        local new="$ENV_DIR.new"
        rm -rf "$new"; mkdir -p "$(dirname "$new")"
        cp -r "$STUDIO_ROOT/runtime/$STUDIO_PLATFORM/python/$APP_PYTHON" "$new"
        rm -f "$new/.version"
        local py="$new/bin/python$APP_PYTHON"
        uv_index_opts
        printf '  %sinstalling %s packages (several GB on first install)...%s\n' "$C_DIM" \
            "$(grep -cE '^[A-Za-z0-9]' "$LOCK_FILE")" "$C_RST"
        uv pip install --python "$py" --break-system-packages --no-deps --require-hashes \
            "${UV_OPTS[@]}" -r "$LOCK_FILE" >"$STUDIO_ROOT/cache/tmp/install-$APP.log" 2>&1 \
            || { tail -15 "$STUDIO_ROOT/cache/tmp/install-$APP.log" >&2; die "Package install failed; full log in cache/tmp/install-$APP.log"; }
        ok "Locked packages"
        if grep -qE '^[A-Za-z0-9]' "$BUILD_FILE"; then
            build_wheels "$py"
            uv pip install --python "$py" --break-system-packages --no-deps --no-index \
                --find-links "$WHEEL_DIR" -r "$BUILD_FILE" --quiet || die "Installing locally built wheels failed."
        fi
        smoke_test "$py" || die "Install finished but the self-test failed (old environment kept)."
        echo "$want" > "$new/.studio-lock"
        rm -rf "$ENV_DIR"; mv "$new" "$ENV_DIR"
    fi
    mkdir -p "$APP_DATA" "$STUDIO_ROOT/outputs/$APP"
    cleanup_host_cache
    log "$APP_TITLE is ready. Start it with: ./studio.sh start $APP"
}

smoke_test() {
    local py="$1"
    [ -n "${APP_SMOKE_TEST:-}" ] || return 0
    local out
    if out="$("$py" -c "$APP_SMOKE_TEST" 2>&1)"; then
        ok "Self-test: $(echo "$out" | tail -1)"
    else
        err "Self-test failed:"; echo "$out" | tail -8 >&2; return 1
    fi
}

app_pid() {
    local pid; pid="$(cat "$PID_FILE" 2>/dev/null)" || return 1
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && echo "$pid"
}

port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# LAN address of this machine (the interface used for the default route).
lan_ip() {
    ip -4 route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1
}

start_app() {
    load_recipe "$1"; shift
    # --lan: listen on all interfaces so phones/tablets on the same network can connect.
    local host="127.0.0.1"
    if [ "${1:-}" = "--lan" ]; then host="0.0.0.0"; shift; fi
    [ -f "$ENV_DIR/.studio-lock" ] || die "$APP_TITLE is not installed in $STUDIO_ROOT. Run: ./studio.sh install $APP (or use the studio folder where it is installed)"
    if app_pid >/dev/null; then ok "$APP_TITLE is already running: $(cat "$APP_DATA/.url" 2>/dev/null)"; return; fi

    # Several apps may run at once; they share the GPU, so warn about memory.
    local self="$APP" other
    for other in $(list_apps); do
        [ "$other" = "$self" ] && continue
        if ( load_recipe "$other"; app_pid >/dev/null ); then
            warn "$other is also running and shares the GPU. If you run out of GPU memory, stop one: ./studio.sh stop $other"
        fi
    done
    load_recipe "$self"

    local port="$APP_PORT"
    while ! port_free "$port"; do port=$((port + 1)); [ "$port" -gt $((APP_PORT + 20)) ] && die "No free port near $APP_PORT"; done

    mkdir -p "$APP_DATA" "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
    local -a args; mapfile -t args < <(app_launch_args "$APP_DATA" "$port" "$host")
    local entry="$RECIPE_DIR/launch.py"
    log "Starting $APP_TITLE $APP_VERSION on port $port (log: data/logs/$APP.log)"
    rm -f "$APP_DATA/.url"  # written once the app answers; until then it is "starting"
    ( cd "$APP_DIR" && exec setsid "$APP_PY" "$entry" "${args[@]}" "$@" ) >"$LOG_FILE" 2>&1 < /dev/null &
    echo $! > "$PID_FILE"

    local url="http://127.0.0.1:$port/" i
    for i in $(seq 1 300); do
        if ! app_pid >/dev/null; then
            err "$APP_TITLE exited during startup. Last log lines:"; tail -20 "$LOG_FILE" >&2; rm -f "$PID_FILE"; exit 1
        fi
        curl -sf -o /dev/null "$url" 2>/dev/null && break
        sleep 1
    done
    if [ "$host" = "0.0.0.0" ]; then
        local ip; ip="$(lan_ip)"
        url="http://${ip:-<this-pc-ip>}:$port/"
        warn "Reachable by every device on your network, without a password. Use only on a network you trust."
    fi
    echo "$url" > "$APP_DATA/.url"
    ok "$APP_TITLE is running: $url"
    command -v xdg-open >/dev/null 2>&1 && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && host_cmd xdg-open "$url" >/dev/null 2>&1 &
    return 0
}

# ./studio.sh run <app>: start an app and show its log in this terminal. Ctrl-C or
# closing the window stops the app; stopping it elsewhere closes the window.
run_app() {
    local app="$1"; shift
    load_recipe "$app"
    printf '\e]0;AI Studio - %s\a' "$APP_TITLE"
    local fresh=1; app_pid >/dev/null && fresh=0

    local tail_pid
    if [ "$fresh" = 1 ]; then
        rm -f "$LOG_FILE"; tail -n +1 -F "$LOG_FILE" 2>/dev/null &
    else
        tail -n 40 -F "$LOG_FILE" 2>/dev/null &
    fi
    tail_pid=$!
    # On a closed window the terminal is gone, so stop quietly.
    trap "set +e; kill $tail_pid 2>/dev/null; stop_app $app >/dev/null 2>&1; exit 0" HUP
    trap "set +e; kill $tail_pid 2>/dev/null; echo; stop_app $app; exit 0" INT TERM

    if [ "$fresh" = 1 ] && ! ( start_app "$app" "$@" ); then
        kill "$tail_pid" 2>/dev/null
        read -rp "Press Enter to close this window " _ || true
        exit 1
    fi
    load_recipe "$app"
    log "$APP_TITLE is running at $(cat "$APP_DATA/.url" 2>/dev/null). Press Ctrl-C or close this window to stop it."

    while app_pid >/dev/null; do sleep 1; done
    trap - HUP INT TERM
    kill "$tail_pid" 2>/dev/null
    echo; log "$APP_TITLE stopped. This window closes in 10 seconds."
    read -rt 10 _ || true
}

stop_app() {
    load_recipe "$1"
    local pid; pid="$(app_pid)" || { ok "$APP_TITLE is not running"; rm -f "$PID_FILE"; return 0; }
    # setsid made the app its own process group: stop it with all its workers.
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
    local i; for i in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$pid" 2>/dev/null && kill -KILL -- "-$pid" 2>/dev/null
    rm -f "$PID_FILE"
    ok "$APP_TITLE stopped"
}

status_apps() {
    local a
    for a in $(list_apps); do
        load_recipe "$a"
        local state="not installed"
        [ -f "$ENV_DIR/.studio-lock" ] && state="installed"
        app_pid >/dev/null && state="running at $(cat "$APP_DATA/.url" 2>/dev/null)"
        printf '  %-12s %-8s %s\n' "$APP" "$APP_VERSION" "$state"
    done
}
