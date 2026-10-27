#!/bin/sh
# Create a local source snapshot; never publish or configure a remote.
set -eu
exec python3 "$(dirname "$0")/public_snapshot.py" create "$@"
