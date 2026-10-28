"""Check tracked publication contents; this is not a complete security audit."""
import hashlib
import ipaddress
import json
from pathlib import Path
import re
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
TEXT_SUFFIXES = {'.md', '.kt', '.js', '.css', '.html', '.xml', '.json', '.gradle', '.properties', '.py', '.yml', '.bat'}

def forbidden_path(name):
    p = Path(name)
    parts = {s.lower() for s in p.parts}
    return bool(parts & {'.private', '.env', 'uploads', 'media', 'build', '.gradle', '.idea', 'node_modules', '__pycache__'}) or p.suffix.lower() in {'.apk', '.aab', '.jks', '.keystore', '.pem', '.key', '.zip', '.tar', '.mp4', '.webm'} or p.name.lower() in {'local.properties', 'tokens.json', 'fx-calibration.json', 'canvas-draft.json', 'scenes.json'} or p.name.startswith('.env.')

def text_issues(value):
    problems = []
    if re.search(r'[A-Za-z]:[\\/](?:Users|All my app)[\\/]', value, re.I):
        problems.append('personal absolute path')
    if re.search(r'https://(?:docs|drive)\.google\.com/', value):
        problems.append('private document link')
    for match in re.finditer(r'(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])', value):
        try:
            address = ipaddress.ip_address(match.group())
        except ValueError:
            continue
        if any(address in ipaddress.ip_network(net) for net in ((167772160, 8), (2886729728, 12), (3232235520, 16))):
            problems.append('private LAN address')
    if re.search(r'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)', value):
        problems.append('credential marker')
    if re.search(r'''\b(?:token|password|passphrase|secret|api_key)\s*(?::\s*(?:String|string))?\s*[:=]\s*["'][A-Za-z0-9+/=_-]{12,}["']''', value, re.I):
        problems.append('literal credential assignment')
    return problems

def png_has_metadata(data):
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
        return True
    pos = 8
    while pos + 12 <= len(data):
        size = struct.unpack('>I', data[pos:pos+4])[0]
        kind = data[pos+4:pos+8]
        if kind in {b'eXIf', b'tEXt', b'zTXt', b'iTXt'}:
            return True
        pos += size + 12
        if kind == b'IEND':
            return False
    return True

def main():
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    paths = [p for p in paths if p]
    if not paths:
        raise SystemExit('No tracked files to inspect; stage the export first.')
    failures = []
    for name in paths:
        data = (ROOT / name).read_bytes()
        if forbidden_path(name):
            failures.append((name, 'forbidden file path'))
        suffix = Path(name).suffix.lower()
        if suffix == '.png':
            if png_has_metadata(data):
                failures.append((name, 'PNG metadata or malformed PNG'))
        elif suffix == '.jar':
            if name != 'android/gradle/wrapper/gradle-wrapper.jar' or hashlib.sha256(data).hexdigest() != WRAPPER_SHA256:
                failures.append((name, 'unrecognized binary'))
        else:
            try:
                value = data.decode('utf-8')
            except UnicodeDecodeError:
                failures.append((name, 'unrecognized binary encoding'))
                continue
            # Policy fixtures intentionally contain rejected examples, not real credentials.
            if name != 'tests/test_public_export.py':
                failures.extend((name, problem) for problem in text_issues(value))
    snapshot = json.loads((ROOT / 'docs/RUNTIME_SNAPSHOT.json').read_text(encoding='utf-8'))
    expected = snapshot['sha256']
    for name, digest in expected.items():
        if name not in paths or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest:
            failures.append((name, 'runtime snapshot mismatch'))
    actual = {p for p in paths if p.startswith('android/app/src/') or p in {'android/app/build.gradle', 'android/build.gradle', 'android/settings.gradle', 'android/gradle.properties'}}
    if actual != set(expected):
        failures.append(('docs/RUNTIME_SNAPSHOT.json', 'runtime manifest coverage mismatch'))
    if failures:
        for name, issue in failures:
            print(f'{name}: {issue}', file=sys.stderr)
        raise SystemExit(1)
    print(f'PASS: {len(paths)} tracked files, {len(expected)} runtime hashes; no rejected publication data.')

if __name__ == '__main__':
    main()
