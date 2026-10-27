#!/bin/sh
# Inspect tracked path names only; never open or traverse key directories.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
python3 - "$root" <<'PY'
import subprocess
import sys
paths = subprocess.check_output(['git', '-C', sys.argv[1], 'ls-files', '-z']).split(b'\0')
if any(path.lower().endswith((b'.jks', b'.keystore')) for path in paths):
    sys.exit('FAIL: a keystore path is still tracked. Commit its removal before releasing.')
print('PASS: no tracked .jks or .keystore paths.')
PY
