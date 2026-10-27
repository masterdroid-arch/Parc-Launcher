#!/bin/sh
# HUMAN ONLY: run directly in your own terminal. Agents must never execute this.
set +x
set +v
set -eu
umask 077
[ "$#" -eq 0 ] || { echo 'Usage: scripts/create-release-key.sh' >&2; exit 2; }
[ -t 0 ] && [ -t 1 ] || { echo 'Run directly in your own terminal.' >&2; exit 1; }
command -v keytool >/dev/null 2>&1 || { echo 'Install JDK 21 and put keytool on PATH.' >&2; exit 1; }
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
# Exclusive directory creation prevents overwriting a key, including partial runs.
[ ! -L "$root/keys" ] || { echo 'Refusing linked key directory.' >&2; exit 1; }
if [ ! -d "$root/keys" ]; then mkdir -m 700 "$root/keys"; fi
mkdir -m 700 "$root/keys/parc" || { echo 'Key directory already exists; nothing overwritten.' >&2; exit 1; }
# Passwords are typed into keytool itself; never read by this script.
keytool -genkeypair -keystore "$root/keys/parc/parc-release.jks" \
    -storetype JKS -alias parc -keyalg RSA -keysize 4096 -validity 10000 \
    </dev/tty >/dev/tty 2>&1
keytool -list -v -keystore "$root/keys/parc/parc-release.jks" -alias parc \
    </dev/tty >/dev/tty 2>&1
printf '%s\n' 'Record the SHA-256 certificate fingerprint above.' \
    'Back up keys/parc/ twice, to two separate safe locations. Keep the passwords safe too.' \
    'If creation failed, inspect the directory yourself before trying again.' >/dev/tty
