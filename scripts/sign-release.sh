#!/bin/sh
# HUMAN ONLY: run directly in your own terminal. Agents must never execute this.
set +x
set +v
set -eu
umask 077
[ "$#" -eq 2 ] || { echo 'Usage: scripts/sign-release.sh <input.apk> <output.apk>' >&2; exit 2; }
[ -t 0 ] && [ -t 1 ] || { echo 'Run directly in your own terminal.' >&2; exit 1; }
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
case $1 in /*) input=$1 ;; *) input=$PWD/$1 ;; esac
case $2 in /*) output=$2 ;; *) output=$PWD/$2 ;; esac
[ -f "$input" ] || { echo 'Input APK is missing.' >&2; exit 1; }
[ ! -e "$output" ] && [ ! -L "$output" ] || { echo 'Output already exists; nothing overwritten.' >&2; exit 1; }
# Set ANDROID_HOME and optionally ANDROID_BUILD_TOOLS (default 36.1.0).
tools=${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}/build-tools/${ANDROID_BUILD_TOOLS:-36.1.0}
[ -x "$tools/zipalign" ] && [ -x "$tools/apksigner" ] || { echo 'Install the selected Android SDK Build-Tools.' >&2; exit 1; }
work=$(mktemp -d "$(dirname "$output")/.parc-sign.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
"$tools/zipalign" -P 16 -f 4 "$input" "$work/aligned.apk"
# No password options: apksigner prompts on the terminal for store/key passwords.
"$tools/apksigner" sign --ks "$root/keys/parc/parc-release.jks" --ks-key-alias parc \
    --debuggable-apk-permitted false --v4-signing-enabled false \
    --out "$work/signed.apk" "$work/aligned.apk" </dev/tty >/dev/tty 2>&1
"$tools/zipalign" -c -P 16 4 "$work/signed.apk"
"$tools/apksigner" verify --verbose --print-certs "$work/signed.apk" >/dev/tty 2>&1
# Publish only a verified APK; hard-link creation refuses a concurrently created output.
ln "$work/signed.apk" "$output"
printf '%s\n' 'Signed APK saved. Compare the SHA-256 certificate fingerprint above with your saved fingerprint.' >/dev/tty
