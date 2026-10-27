#!/usr/bin/env python3
"""Fail-closed public export. Only git's index in a NEW target is ever changed.

Usage: make-public-snapshot.sh <new-target-dir>
       verify-public-snapshot.sh <target-dir> [--denylist <local-file>]
The private denylist is required when exporting from the development checkout.
It is deliberately never copied. Verification of a public copy requires --denylist.
Diagnostics identify paths and categories, never matching values.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
MODULES = ('app', 'core/model', 'core/data', 'feature/ring', 'feature/search', 'feature/settings')
ROOT_FILES = ('gradlew', 'gradlew.bat', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties')
DOCUMENTS = ('README', 'LICENSE', 'PRIVACY', 'TRADEMARK', 'CONTRIBUTING', 'CLA',
             'CLA-SIGNATURES', 'NOTICE')
BRANDING = ('parc-icon.svg', 'parc-icon-round.svg', 'parc-icon-legacy.svg',
            'parc-feature-graphic.svg', 'store-icon-512.png', 'feature-graphic-1024x500.png')
SCRIPTS = ('make-public-snapshot.sh', 'verify-public-snapshot.sh', 'check-public-snapshot.sh',
           'public_snapshot.py', 'test_public_snapshot.py', 'rel.init.gradle',
           'create-release-key.sh', 'sign-release.sh', 'check-no-tracked-keystores.sh')
IGNORED_DIRS = {'.git', 'private', 'reports', 'prompts', 'keys', '.agent', '.agents', '.codex',
                '.claude', '.kotlin', '.gradle', '.idea', 'build', '__pycache__'}
IGNORED_FILES = {'local.properties', 'keystore.properties', '.DS_Store',
                 'PROJECT.md', 'DESIGN.md', 'TASKS.md'}
IGNORED_SUFFIXES = {'.apk', '.aab', '.keystore', '.jks', '.p12', '.pfx', '.pem', '.key', '.pyc', '.zip'}
# Historical recovery relies on private inputs and old history, neither of which is exported.
PRIVATE_PATHS = {'core/data/tools',
                 'core/data/src/test/kotlin/com/brightblu/parc/core/data/HistoricalLayoutBackupTest.kt'}
IDENTITY = 'Parc <' + 'noreply' + '@' + 'example.invalid' + '>'
GITIGNORE = '''build/
.gradle/
.kotlin/
__pycache__/
*.pyc
*.apk
*.aab
local.properties
*.keystore
*.jks
private/
keys/
.idea/
.DS_Store
'''
PATTERNS = {
    'personal name': r'\bLi' + r'c(?:i|ínia)\b|\bJ(?:ohn|hon)\w*\b',
    'personal path': r'/Users/' + r'bright' + r'blu',
    'owner identifier': r'bright' + r'blu',
    'email': r'[\w.+-]+@[\w-]+(?:\.[\w-]+)+',
    'phone': r'(?<![\w.])(?:\+\d{1,3}[ ()-]+(?:\d[ ()-]*){8,14}\d|\(?\d{3}\)?[ -]\d{3}[ -]\d{4}|\d{3}-\d{4})(?!\w)|[\"\']\d{9,15}[\"\']',
    'token': r'(?:AIza[\w-]{30,}|gh[pousr]_[\w]{30,}|github_pat_[\w]{30,}|xox[baprs]-[\w-]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:\w+ )?PRIVATE KEY-----)',
    'credential assignment': r'''(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret)\s*[=:]\s*["'][A-Za-z0-9_/-]{20,}''',
}


def git(target, *args):
    # Ignore global author, hooks, signing, templates and user repository configuration.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
               GIT_AUTHOR_NAME='Parc', GIT_AUTHOR_EMAIL=IDENTITY.split('<')[1][:-1],
               GIT_COMMITTER_NAME='Parc', GIT_COMMITTER_EMAIL=IDENTITY.split('<')[1][:-1])
    return subprocess.check_output(['git', '-C', str(target), '-c', 'core.hooksPath=/dev/null',
                                    '-c', 'commit.gpgSign=false', *args], env=env, text=True).strip()


def excluded(path):
    return (any(p in IGNORED_DIRS for p in path.parts) or path.name in IGNORED_FILES
            or path.suffix.lower() in IGNORED_SUFFIXES
            or any(path.as_posix() == p or path.as_posix().startswith(p + '/') for p in PRIVATE_PATHS))


def export_files(source):
    for module in (*MODULES, 'gradle'):
        base = source / module
        if not base.is_dir() or base.is_symlink():
            raise ValueError('Missing or linked source directory: ' + module)
        for directory, dirs, files in os.walk(base, followlinks=False):
            for name in list(dirs):
                entry = Path(directory) / name
                if entry.is_symlink():
                    raise ValueError('Symlink refused: ' + entry.relative_to(source).as_posix())
                if excluded(entry.relative_to(source)):
                    dirs.remove(name)
            for name in files:
                entry = Path(directory) / name
                if not excluded(entry.relative_to(source)):
                    if entry.is_symlink():
                        raise ValueError("Symlink refused: " + entry.relative_to(source).as_posix())
                    yield entry.relative_to(source)
    for name in ROOT_FILES:
        yield Path(name)
    for stem in DOCUMENTS:
        for suffix in ('', '.md', '.txt'):
            if (source / (stem + suffix)).exists():
                yield Path(stem + suffix)
    for name in SCRIPTS:
        yield Path('scripts') / name
    for name in BRANDING:
        yield Path('branding') / name


def source_file(source, relative):
    if relative.parts[0] == 'branding' and not (source / 'branding').exists():
        return source / 'reports' / relative
    return source / relative


def scan(relative, data, denylist):
    # Strip only the exact Android namespace (including slash-separated source paths).
    content = relative.as_posix() + '\n' + data.decode('utf-8', errors='replace')
    content = re.sub(r'com(?:\.bright' + r'blu\.parc|/bright' + r'blu/parc)(?![\w])', 'parc_namespace', content)
    # Kotlin receiver labels are syntax, never an address.
    if relative.suffix == '.kt':
        pieces = re.split(r'("""[\s\S]*?"""|"(?:\\.|[^"\\])*")', content)
        content = ''.join(piece if index % 2 else
                          re.sub(r'\bthis@[A-Za-z_]\w*', 'receiver', piece)
                          for index, piece in enumerate(pieces))
    findings = [kind for kind, pattern in PATTERNS.items() if re.search(pattern, content, re.I)]
    if any(word.casefold() in content.casefold() for word in denylist):
        findings.append('private app')
    if relative.suffix.lower() == '.jar':
        official_wrapper = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
        if relative != Path('gradle/wrapper/gradle-wrapper.jar') or hashlib.sha256(data).hexdigest() != official_wrapper:
            findings.append('unexpected binary archive')
    if data[:4] in (bytes.fromhex('feedfeed'), bytes.fromhex('cececece')) or data[:2] == b'\x30\x82':
        findings.append('key container')
    return findings


def load_denylist(path):
    if not path.is_file() or path.is_symlink():
        raise ValueError('Missing local private app denylist; supply --denylist')
    values = [line.strip() for line in path.read_text().splitlines() if line.strip()]
    if len(values) != 7 or len(set(values)) != 7:
        raise ValueError('Private app denylist must contain seven distinct entries')
    return values


def verify(target, denylist):
    if not (target / '.git').is_dir() or (target / '.git').is_symlink():
        raise ValueError('Expected a standalone snapshot repository')
    tracked = [p for p in git(target, 'ls-files', '-z').split('\0') if p]
    committed_paths = set(git(target, 'ls-tree', '-r', '--name-only', 'HEAD').splitlines())
    allowed = {p.as_posix() for p in export_files(target)} | {'.gitignore'}
    failures = []
    if set(tracked) != committed_paths:
        failures.append('index: differs from the committed file list')
    for name in tracked:
        path = Path(name)
        entry = target / path
        if name not in allowed or excluded(path) or entry.is_symlink():
            failures.append(name + ': excluded or unexpected tracked path')
            continue
        for kind in scan(path, entry.read_bytes(), denylist):
            failures.append(name + ': ' + kind)
    if git(target, 'diff', '--name-only', 'HEAD'):
        failures.append('files: differ from committed content')
    # Include ignored/untracked files too; permit only recognised build outputs after a build.
    for directory, dirs, files in os.walk(target, followlinks=False):
        for name in list(dirs):
            entry = Path(directory) / name
            rel = entry.relative_to(target)
            if entry.is_symlink():
                failures.append(rel.as_posix() + ': symlink')
            if name in {'.git', '.gradle', '.kotlin', 'build', '__pycache__'}:
                dirs.remove(name)
        for name in files:
            entry = Path(directory) / name
            rel = entry.relative_to(target)
            if rel.as_posix() in tracked:
                continue
            if entry.is_symlink() or not (rel == Path('local.properties') or
                    (len(rel.parts) == 1 and rel.suffix == '.apk')):
                failures.append(rel.as_posix() + ': unexpected untracked file')
    if git(target, 'rev-list', '--all', '--count') != '1':
        failures.append('history: expected exactly one commit')
    if git(target, 'log', '-1', '--format=%an <%ae>%n%cn <%ce>') != IDENTITY + '\n' + IDENTITY:
        failures.append('history: unexpected author or committer')
    if git(target, 'remote'):
        failures.append('repository: remote configured')
    if failures:
        raise ValueError('Snapshot verification FAILED:\n' + '\n'.join(failures))
    print('Snapshot verification passed: files, one neutral commit, no remotes.')


def create(target, denylist):
    if target.exists() or target.is_symlink():
        raise ValueError('Target must not exist (nothing is overwritten)')
    if target == ROOT or ROOT in target.parents or target in ROOT.parents:
        raise ValueError('Target must be outside the source repository')
    paths = list(export_files(ROOT))
    # Preflight before creating anything. No automatic redaction conceals source problems.
    for relative in paths:
        source = source_file(ROOT, relative)
        if source.is_symlink() or not source.is_file():
            raise ValueError('Missing or linked source file: ' + relative.as_posix())
        findings = scan(relative, source.read_bytes(), denylist)
        if findings:
            raise ValueError(relative.as_posix() + ': ' + ', '.join(findings))
    target.mkdir(parents=True)
    for relative in paths:
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_file(ROOT, relative), destination)
        destination.chmod(0o755 if relative.name.endswith('.sh') or relative.name == 'gradlew' else 0o644)
    (target / '.gitignore').write_text(GITIGNORE)
    git(target, 'init', '--quiet', '--template=', '--initial-branch=main')
    git(target, 'config', '--local', 'user.name', 'Parc')
    git(target, 'config', '--local', 'user.email', IDENTITY.split('<')[1][:-1])
    git(target, 'add', '--all')
    git(target, 'commit', '--quiet', '-m', 'Initial public source snapshot')
    verify(target, denylist)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('create', 'verify'))
    parser.add_argument('target', type=Path)
    parser.add_argument('--denylist', type=Path)
    args = parser.parse_args()
    local = ROOT / 'private/public-snapshot-denylist.txt'
    denylist = load_denylist(args.denylist or local)
    target = args.target.expanduser().resolve()
    (create if args.action == 'create' else verify)(target, denylist)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
