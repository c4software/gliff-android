#!/bin/sh
# The phone's desktop mode in a window of this computer, over adb (Wi-Fi or
# USB), to try the app as on a Googlebook:
#
#     scripts/desktop-mode.sh on [WIDTHxHEIGHT/DPI]   # default 1920x1080/160
#     scripts/desktop-mode.sh off
#
# `on` makes an overlay display on the phone, forces desktop windowing on
# external displays, then shows that display with scrcpy, the phone seeing
# this computer's keyboard and mouse as real ones (uhid). The overlay also
# shows, small, over the phone's own screen while it is on. Closing scrcpy's
# window, or Ctrl-C, puts the phone back as it was.
#
# The settings it changes are read first and saved, once, per device, in
# $XDG_STATE_HOME/gliff: putting them back deletes those that were not set
# rather than writing a value the phone never had. `off` does it by hand,
# should `on` have been killed before it could.
# Community workaround, not a documented one: Genymobile/scrcpy#6143.
#
# The device is adb's own, or ANDROID_SERIAL's when there are several.
set -eu

KEYS="force_desktop_mode_on_external_displays force_allow_on_external overlay_display_devices"

# adb reads its standard input, and would swallow what `restore` reads the saved settings from.
adb() { command adb "$@" < /dev/null; }

serial=$(adb get-serialno | tr -d '\r')
state="${XDG_STATE_HOME:-$HOME/.local/state}/gliff/desktop-mode-$(printf %s "$serial" | tr -c 'A-Za-z0-9.-' _).env"

setting() { adb shell settings get global "$1" | tr -d '\r'; }
overlays() { adb shell cmd display get-displays -i -t OVERLAY | tr -d '\r'; }

# The saved settings back on the phone, then forgotten. Nothing saved, nothing to do.
restore() {
    [ -f "$state" ] || return 0
    while IFS='=' read -r key value; do
        case " $KEYS " in *" $key "*) ;; *) continue ;; esac
        if [ "$value" = null ]; then
            adb shell settings delete global "$key" > /dev/null
        else
            adb shell settings put global "$key" "$value"
        fi
        echo "  $key back to $value (now: $(setting "$key"))"
    done < "$state"
    rm "$state"
    echo "The phone's settings are as they were."
}

case "${1:-}" in
on)
    size="${2:-1920x1080/160}"
    if ! command -v scrcpy > /dev/null; then
        echo "scrcpy is missing: sudo pacman -S scrcpy" >&2
        exit 1
    fi
    # Saved once: a second `on` must not take the forced values for the phone's own.
    if [ ! -f "$state" ]; then
        mkdir -p "$(dirname "$state")"
        for key in $KEYS; do printf '%s=%s\n' "$key" "$(setting "$key")"; done > "$state.tmp"
        mv "$state.tmp" "$state"
        echo "Saved the phone's settings in $state:"
        sed 's/^/  /' "$state"
    fi
    # From here on, however this ends: scrcpy closed, Ctrl-C, an error.
    trap 'restore' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    adb shell settings put global force_desktop_mode_on_external_displays 1
    adb shell settings put global force_allow_on_external 1
    adb shell settings put global overlay_display_devices "$size"
    id=""
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        id=$(overlays | head -n 1)
        [ -n "$id" ] && break
        sleep 1
    done
    if [ -z "$id" ]; then
        echo "No overlay display showed up." >&2
        exit 1
    fi
    echo "Desktop display: $id ($size). Close the window or press Ctrl-C to put the phone back."
    scrcpy -s "$serial" --display-id="$id" --keyboard=uhid --mouse=uhid || true
    ;;
off)
    if [ ! -f "$state" ]; then
        echo "Nothing saved for $serial: the settings were not changed by this script." >&2
        exit 1
    fi
    restore
    ;;
*)
    echo "usage: $0 on [WIDTHxHEIGHT/DPI] | off" >&2
    exit 2
    ;;
esac
