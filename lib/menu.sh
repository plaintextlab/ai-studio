# Control panel shown by ./studio.sh with no arguments: pick one or more apps, start
# each in its own terminal window, stop any or all of them. Pure bash, no dialog tools.

# The first terminal emulator found, preferring the user's $TERMINAL.
find_terminal() {
    local t
    for t in "${TERMINAL%% *}" x-terminal-emulator kitty alacritty foot wezterm konsole \
             gnome-terminal ptyxis xfce4-terminal mate-terminal tilix terminator \
             lxterminal qterminal xterm; do
        [ -n "$t" ] && command -v "$t" >/dev/null 2>&1 && { echo "$t"; return 0; }
    done
    return 1
}

# open_terminal TITLE COMMAND... -> opens a new terminal window running COMMAND.
# Detached with setsid, so closing the control panel leaves the windows open.
open_terminal() {
    local title="$1"; shift
    local t; t="$(find_terminal)" || return 1
    local -a cmd
    case "$(basename "$t")" in
        gnome-terminal|ptyxis)            cmd=("$t" --title "$title" -- "$@") ;;
        kitty|foot)                       cmd=("$t" --title "$title" "$@") ;;
        alacritty)                        cmd=("$t" --title "$title" -e "$@") ;;
        wezterm)                          cmd=("$t" start -- "$@") ;;
        konsole)                          cmd=("$t" -e "$@") ;;
        xfce4-terminal|mate-terminal|terminator) cmd=("$t" --title "$title" -x "$@") ;;
        tilix|lxterminal|qterminal)       cmd=("$t" -e "$(printf '%q ' "$@")") ;;
        *)                                cmd=("$t" -T "$title" -e "$@") ;;
    esac
    host_cmd setsid "${cmd[@]}" >/dev/null 2>&1 < /dev/null &
}

# Prints "<state> <url>" for the loaded recipe: installing, not-installed, stopped,
# starting, running.
app_state() {
    if install_pid >/dev/null; then echo "installing"
    elif [ ! -f "$ENV_DIR/.studio-lock" ]; then echo "not-installed"
    elif ! app_pid >/dev/null; then echo "stopped"
    elif [ -f "$APP_DATA/.url" ]; then echo "running $(cat "$APP_DATA/.url")"
    else echo "starting"
    fi
}

gpu_usage() {
    command -v nvidia-smi >/dev/null 2>&1 || return 0
    nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv,noheader,nounits 2>/dev/null \
        | head -1 | awk -F', ' '{ printf "%s: %.1f of %.1f GB VRAM in use", $1, $2/1024, $3/1024 }'
}

# Starts an app in a new terminal window, or in the background when there is no desktop.
menu_start() {
    local app="$1" lan="$2"
    load_recipe "$app"
    local -a args=(run "$app"); [ "$lan" = 1 ] && args+=(--lan)
    if [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && open_terminal "AI Studio - $APP_TITLE" "$STUDIO_ROOT/studio.sh" "${args[@]}"; then
        MENU_MSG+=("$(ok "$APP_TITLE: starting in a new terminal window")")
    else
        args[0]=start
        setsid "$STUDIO_ROOT/studio.sh" "${args[@]}" >/dev/null 2>&1 < /dev/null &
        MENU_MSG+=("$(ok "$APP_TITLE: starting in the background (no terminal window available; log: ./studio.sh logs $app)")")
    fi
}

studio_menu() {
    if [ ! -t 0 ] || [ ! -t 1 ]; then usage; return; fi
    local -a apps; mapfile -t apps < <(list_apps)
    local n=${#apps[@]} cur=0 lan=0 i key rest
    local -a sel; for i in "${!apps[@]}"; do sel[i]=0; done
    MENU_MSG=()

    # Alternate screen and hidden cursor; restored however the panel exits.
    printf '\e[?1049h\e[?25l'
    trap 'printf "\e[?25h\e[?1049l"' EXIT
    trap 'exit 0' INT TERM

    while true; do
        local -a states=() titles=()
        local active=0
        for i in "${!apps[@]}"; do
            load_recipe "${apps[i]}"
            titles[i]="$APP_TITLE"; states[i]="$(app_state)"
            case "${states[i]}" in running*|starting) active=$((active + 1)) ;; esac
        done

        printf '\e[H\e[J'
        printf '%sPortable AI Studio%s   %s\n\n' "$C_BLU" "$C_RST" "$(gpu_usage)"
        for i in "${!apps[@]}"; do
            local mark=" " box="[ ]" st="${states[i]%% *}" url="" color="$C_DIM"
            [ "$i" = "$cur" ] && mark=">"
            [ "${sel[i]}" = 1 ] && box="[x]"
            [ "$st" != "${states[i]}" ] && url="${states[i]#* }"
            case "$st" in running) color="$C_GRN" ;; starting|installing) color="$C_YLW" ;; esac
            printf ' %s %s %d  %-12s %s%-13s%s %s\n' "$mark" "$box" $((i + 1)) "${titles[i]}" \
                "$color" "$st" "$C_RST" "$url"
        done
        printf '\n %s↑/↓%s move   %sSpace%s or %s1-%d%s select   %sEnter%s start   %sx%s stop   %sa%s stop all\n' \
            "$C_BLU" "$C_RST" "$C_BLU" "$C_RST" "$C_BLU" "$n" "$C_RST" "$C_BLU" "$C_RST" \
            "$C_BLU" "$C_RST" "$C_BLU" "$C_RST"
        printf ' %sl%s LAN access: %s   %sq%s quit (running apps keep running)\n' \
            "$C_BLU" "$C_RST" "$([ "$lan" = 1 ] && echo "${C_YLW}on${C_RST}" || echo off)" "$C_BLU" "$C_RST"
        printf ' %sEnter/x act on the selected apps, or on the one under the cursor when none is selected.%s\n' \
            "$C_DIM" "$C_RST"
        if [ "$active" -gt 1 ]; then
            printf '\n'; warn "$active apps share the GPU. If one runs out of memory, stop the others."
        fi
        if [ ${#MENU_MSG[@]} -gt 0 ]; then
            local first=$(( ${#MENU_MSG[@]} > 6 ? ${#MENU_MSG[@]} - 6 : 0 ))
            printf '\n'; printf '%s\n' "${MENU_MSG[@]:first}"
        fi

        # Refresh every 2 seconds so status changes show up without a key press.
        key=""; IFS= read -rsn1 -t 2 key || continue
        if [ "$key" = $'\e' ]; then rest=""; IFS= read -rsn2 -t 0.05 rest || true; key+="$rest"; fi

        # Apps to act on: the selected ones, or the one under the cursor.
        local -a targets=()
        for i in "${!apps[@]}"; do [ "${sel[i]}" = 1 ] && targets+=("$i"); done
        [ ${#targets[@]} -eq 0 ] && targets=("$cur")

        case "$key" in
            $'\e[A'|k) cur=$(( (cur - 1 + n) % n )) ;;
            $'\e[B'|j) cur=$(( (cur + 1) % n )) ;;
            ' ')       sel[cur]=$(( 1 - sel[cur] )) ;;
            [1-9])     [ "$key" -le "$n" ] && { cur=$((key - 1)); sel[cur]=$(( 1 - sel[cur] )); } ;;
            l|L)       lan=$(( 1 - lan )) ;;
            ''|$'\r'|$'\n')  # Enter
                MENU_MSG=()
                for i in "${targets[@]}"; do
                    case "${states[i]}" in
                        running*|starting) MENU_MSG+=("$(ok "${titles[i]} is already running")") ;;
                        installing) MENU_MSG+=("$(ok "${titles[i]} is being installed; wait for that to finish")") ;;
                        *) menu_start "${apps[i]}" "$lan" ;;
                    esac
                done
                for i in "${!apps[@]}"; do sel[i]=0; done ;;
            x|X)
                MENU_MSG=()
                for i in "${targets[@]}"; do MENU_MSG+=("$(stop_app "${apps[i]}" 2>&1)"); done
                for i in "${!apps[@]}"; do sel[i]=0; done ;;
            a|A)
                MENU_MSG=()
                for i in "${!apps[@]}"; do
                    case "${states[i]}" in running*|starting) MENU_MSG+=("$(stop_app "${apps[i]}" 2>&1)") ;; esac
                done
                [ ${#MENU_MSG[@]} -eq 0 ] && MENU_MSG+=("$(ok "No apps are running")") ;;
            q|Q)       exit 0 ;;
        esac
    done
}
