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
# shows, small, over the phone's own screen while it is on.
#
# The settings it changes are read first and saved, once, per device, in
# $XDG_STATE_HOME/gliff: `off` puts them back as they were, deleting those
# that were not set rather than writing a value the phone never had.
# Community workaround, not a documented one: Genymobile/scrcpy#6143.
#
# The device is adb's own, or ANDROID_SERIAL's when there are several.
set -eu

KEYS="force_desktop_mode_on_external_displays force_allow_on_external overlay_display_devices"

serial=$(adb get-serialno | tr -d '\r')
state="${XDG_STATE_HOME:-$HOME/.local/state}/gliff/desktop-mode-$(printf %s "$serial" | tr -c 'A-Za-z0-9.-' _).env"

setting() { adb shell settings get global "$1" | tr -d '\r'; }
overlays() { adb shell cmd display get-displays -i -t OVERLAY | tr -d '\r'; }

case "${1:-}" in
on)
    size="${2:-1920x1080/160}"
    # Saved once: a second `on` must not take the forced values for the phone's own.
    if [ ! -f "$state" ]; then
        mkdir -p "$(dirname "$state")"
        for key in $KEYS; do printf '%s=%s\n' "$key" "$(setting "$key")"; done > "$state.tmp"
        mv "$state.tmp" "$state"
        echo "Saved the phone's settings in $state:"
        sed 's/^/  /' "$state"
    fi
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
        echo "No overlay display showed up; scripts/desktop-mode.sh off puts the settings back." >&2
        exit 1
    fi
    echo "Desktop display: $id ($size)."
    if ! command -v scrcpy > /dev/null; then
        echo "scrcpy is missing (sudo pacman -S scrcpy); then: scrcpy -s $serial --display-id=$id --keyboard=uhid --mouse=uhid" >&2
        exit 1
    fi
    scrcpy -s "$serial" --display-id="$id" --keyboard=uhid --mouse=uhid || true
    echo "scrcpy closed; the desktop display is still there. scripts/desktop-mode.sh off puts the phone back."
    ;;
off)
    if [ ! -f "$state" ]; then
        echo "Nothing saved for $serial: the settings were not changed by this script." >&2
        exit 1
    fi
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
    ;;
*)
    echo "usage: $0 on [WIDTHxHEIGHT/DPI] | off" >&2
    exit 2
    ;;
esac
