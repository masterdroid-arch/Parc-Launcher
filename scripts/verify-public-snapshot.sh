#!/bin/sh
# Verify files and the sole commit, including after a local build.
set -eu
exec python3 "$(dirname "$0")/public_snapshot.py" verify "$@"
