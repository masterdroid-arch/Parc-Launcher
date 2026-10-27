#!/bin/sh
# Requires Python 3, git, JDK 21 and Android SDK 36 (ANDROID_HOME).
# Builds a disposable copy; the source repository and private recovery data stay intact.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/parc-snapshot-check.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM
denylist=${PARC_SNAPSHOT_DENYLIST:-$script_dir/../private/public-snapshot-denylist.txt}
python3 "$script_dir/test_public_snapshot.py"
"$script_dir/make-public-snapshot.sh" "$work_dir/public" --denylist "$denylist"
(cd "$work_dir/public" && ./gradlew :app:assembleDebug testDebugUnitTest "$@")
"$work_dir/public/scripts/check-no-tracked-keystores.sh"
"$script_dir/verify-public-snapshot.sh" "$work_dir/public" --denylist "$denylist"
