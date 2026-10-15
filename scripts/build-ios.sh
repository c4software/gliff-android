#!/usr/bin/env bash
# Builds the iOS app and installs it on a device plugged in over USB, from
# Linux, asking what it needs along the way. Each step is skipped when it is
# already done, so running it again only rebuilds and reinstalls.
#
#   scripts/build-ios.sh
#
# The steps are the ones of the README's "Build: iPhone and iPad": the tools
# (Swift, usbmuxd, gh, xtool and its iOS SDK), a bundle ID of your own Apple
# account, Shared.xcframework for this commit (linked on GitHub, Kotlin cannot
# link it under Linux), then xtool dev.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"

bold=$'\e[1m' dim=$'\e[2m' red=$'\e[31m' green=$'\e[32m' off=$'\e[0m'
[ -t 1 ] || bold='' dim='' red='' green='' off=''

step() { printf '\n%s== %s%s\n' "$bold" "$1" "$off"; }
ok() { printf '%s✓%s %s\n' "$green" "$off" "$1"; }
fail() { printf '%s✗ %s%s\n' "$red" "$1" "$off" >&2; exit 1; }
note() { printf '%s%s%s\n' "$dim" "$1" "$off"; }

# ask "question" default(y|n): true for yes.
ask() {
  local hint answer
  [ "$2" = y ] && hint='[Y/n]' || hint='[y/N]'
  read -r -p "$1 $hint " answer
  answer="${answer:-$2}"
  [[ "$answer" =~ ^[YyOo] ]]
}

# choose "question" option...: prints the number picked, from 1.
choose() {
  local question="$1" i answer
  shift
  printf '%s\n' "$question" >&2
  for ((i = 1; i <= $#; i++)); do printf '  %d. %s\n' "$i" "${!i}" >&2; done
  while :; do
    read -r -p "Choice (1-$#): " answer
    if [[ "$answer" =~ ^[0-9]+$ ]] && ((answer >= 1 && answer <= $#)); then
      echo "$answer"
      return
    fi
  done
}

# --- Tools -----------------------------------------------------------------

step "Tools"

if command -v swift >/dev/null; then
  ok "$(swift --version 2>&1 | head -1)"
else
  fail "Swift is missing. Install the toolchain xtool's guide names, from
  https://swift.org/install/linux, then run this again."
fi

if command -v usbmuxd >/dev/null; then
  ok "usbmuxd"
else
  note "usbmuxd reaches the device over USB."
  if command -v pacman >/dev/null && ask "Install it with pacman?" y; then
    sudo pacman -S --needed usbmuxd
  elif command -v apt-get >/dev/null && ask "Install it with apt-get?" y; then
    sudo apt-get install -y usbmuxd
  else
    fail "Install usbmuxd, then run this again."
  fi
fi

if ! command -v gh >/dev/null; then
  fail "gh is missing: it fetches the Kotlin framework from GitHub.
  See https://cli.github.com, then run this again."
fi
if ! gh auth status >/dev/null 2>&1; then
  note "gh is not logged in."
  gh auth login
fi
ok "gh"

if ! command -v xtool >/dev/null; then
  note "xtool builds, signs and installs the app (https://xtool.sh)."
  if ask "Download it into ~/.local/bin?" y; then
    mkdir -p ~/.local/bin
    curl -fL --progress-bar -o ~/.local/bin/xtool \
      "https://github.com/xtool-org/xtool/releases/latest/download/xtool-$(uname -m).AppImage"
    chmod +x ~/.local/bin/xtool
    case ":$PATH:" in
      *":$HOME/.local/bin:"*) ;;
      *) export PATH="$HOME/.local/bin:$PATH"
         note "$HOME/.local/bin is not in your PATH; add it to run xtool yourself." ;;
    esac
  else
    fail "Install xtool, then run this again."
  fi
fi
ok "xtool"

if swift sdk list 2>/dev/null | grep -q darwin; then
  ok "iOS SDK"
else
  step "xtool setup"
  cat <<'EOF'
xtool needs to log in to Apple once, and to make the iOS SDK from Xcode.

1. Download Xcode.xip in a browser, with your Apple account (several GB):
   https://developer.apple.com/download/all/?q=Xcode
2. At the login, "API Key" needs a paid Apple Developer Program membership;
   "Password" works with any Apple account, through private Apple APIs.
   With a free account, the app stops opening after 7 days: run this again
   to sign it anew.
3. Then give the path to Xcode.xip.
EOF
  ask "Ready?" y || exit 1
  xtool setup
  swift sdk list 2>/dev/null | grep -q darwin || fail "No darwin SDK after xtool setup."
  ok "iOS SDK"
fi

# --- Bundle ID -------------------------------------------------------------

# An app ID belongs to one Apple account. Yours is kept in iosApp/.bundle-id
# (not versioned) and put in xtool.yml only for the build.
step "Bundle ID"
yml=iosApp/xtool.yml
saved=iosApp/.bundle-id
committed="$(sed -n 's/^bundleID: *//p' "$yml")"
if [ -s "$saved" ]; then
  bundle="$(cat "$saved")"
else
  note "An app ID belongs to one Apple account: $committed is the maintainer's."
  if ask "Is $committed registered on your Apple account?" n; then
    bundle="$committed"
  else
    default="io.github.$(id -un | tr -cd '[:alnum:]-' | tr '[:upper:]' '[:lower:]').gliff"
    while :; do
      read -r -p "Your bundle ID [$default]: " bundle
      bundle="${bundle:-$default}"
      [[ "$bundle" =~ ^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$ ]] && break
      note "Letters, digits, - and dots, like com.example.gliff."
    done
  fi
  echo "$bundle" >"$saved"
  note "Kept in $saved; delete it to choose again."
fi
ok "$bundle"

# --- Kotlin framework ------------------------------------------------------

step "Shared.xcframework"
framework=iosApp/Frameworks/Shared.xcframework
stamp=iosApp/Frameworks/commit
head="$(git rev-parse HEAD)"
short="$(git rev-parse --short HEAD)"

if [ -n "$(git status --porcelain -- proto domain transport shared)" ]; then
  note "The Kotlin sources have uncommitted changes: the framework is built from
commits, so it will not have them."
fi

if [ "$(uname)" = Darwin ]; then
  if ask "On a Mac: link the framework here, with Gradle?" y; then
    ./gradlew :shared:assembleSharedReleaseXCFramework
    mkdir -p iosApp/Frameworks
    rm -rf "$framework"
    cp -R shared/build/XCFrameworks/release/Shared.xcframework "$framework"
    echo "$head" >"$stamp"
  fi
fi

if [ -d "$framework" ] && [ "$(cat "$stamp" 2>/dev/null)" = "$head" ]; then
  ok "already the one of $short"
else
  run="$(gh run list --workflow ios-framework.yml --commit "$head" --limit 1 \
    --json databaseId,status,conclusion \
    --jq '.[] | select(.conclusion == "success" or .status != "completed") | .databaseId')"
  if [ -z "$run" ]; then
    origin="$(git remote get-url origin)"
    case "$(choose "No framework was built from $short yet. It is linked on GitHub, on a macOS runner (about 25 minutes)." \
      "Build it: push the tag ios-build-$short to origin ($origin)" \
      "Take the last one built, from another commit (fine if the Kotlin side has not changed)" \
      "Stop")" in
      1)
        tag="ios-build-$short"
        if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null; then
          # Pushed before, and its run failed: run the workflow on it again.
          gh workflow run ios-framework.yml --ref "$tag"
        else
          git tag -f "$tag" >/dev/null
          git push origin "refs/tags/$tag"
        fi
        note "Waiting for GitHub to start the run..."
        for _ in $(seq 30); do
          sleep 4
          run="$(gh run list --workflow ios-framework.yml --commit "$head" --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
          [ -n "$run" ] && break
        done
        [ -n "$run" ] || fail "No run started. Are GitHub Actions enabled on $origin?"
        ;;
      2) run="" ;;
      *) exit 1 ;;
    esac
  fi
  if [ -n "$run" ] && [ "$(gh run view "$run" --json status --jq .status)" != completed ]; then
    note "Run $run is building it; this waits for it (Ctrl-C leaves it running)."
    gh run watch "$run" --exit-status >/dev/null || fail "Run $run failed: gh run view $run --log-failed"
  fi
  scripts/fetch-ios-framework.sh ${run:+"$run"}
fi

# --- Build and install ------------------------------------------------------

step "Build and install"
cat <<'EOF'
Plug the iPhone or iPad in over USB, unlocked. The first time:
- tap Trust on the device and enter its passcode;
- if asked for Developer Mode: Settings > Privacy & Security > Developer Mode
  (the device restarts);
- with a free account, the first launch says "Untrusted Developer": allow it
  in Settings > General > VPN & Device Management, under your Apple account.
The first build takes a few minutes.
EOF

if [ "$bundle" != "$committed" ]; then
  cp "$yml" "$yml.orig"
  trap 'mv -f "$here/$yml.orig" "$here/$yml"' EXIT
  sed -i "s/^bundleID:.*/bundleID: $bundle/" "$yml"
fi

until (cd iosApp && xtool dev); do
  printf '\n'
  note "After Trust or Developer Mode, xtool often has to start again."
  ask "Try again?" y || exit 1
done
ok "Installed"
