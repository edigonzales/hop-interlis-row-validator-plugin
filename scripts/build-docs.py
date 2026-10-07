#!/usr/bin/env python3
"""Build the documentation site precisely from this checkout or a specified Git revision."""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
BASE = 'https://jars.interlis.guru/snapshots/guru/interlis/thoth-biblios/0.0.1-SNAPSHOT'
SOURCE = 'https://github.com/edigonzales/hop-interlis-row-validator-plugin.git'


def run(*args, cwd=None, capture=False):
    return subprocess.run(args, cwd=cwd or ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def snapshot(destination, revision=None):
    """Never change branches, stage files or commit in the user's repository."""
    paths = ['docs', 'examples']
    if revision:
        commit = run('git', 'rev-parse', '--verify', revision + '^{commit}', capture=True).strip()
        names = run('git', 'ls-tree', '-r', '--name-only', commit, '--', *paths, capture=True).splitlines()
        for name in names:
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(subprocess.check_output(['git', 'show', f'{commit}:{name}'], cwd=ROOT))
        origin = commit
    else:
        for name in paths:
            src, target = ROOT / name, destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if src.is_dir():
                shutil.copytree(src, target, ignore=shutil.ignore_patterns(
                    'build', '.downloads', '.cache', '.biblios-cache', 'biblios.local.yml',
                    'downloads', '__pycache__'))
            else:
                shutil.copy2(src, target)
        origin = run('git', 'rev-parse', 'HEAD', capture=True).strip() + ' + working tree'
    run('git', 'init', '-q', '-b', 'main', str(destination))
    run('git', 'add', '.', cwd=destination)
    run('git', '-c', 'user.name=Biblios local snapshot', '-c', 'user.email=biblios@localhost',
        '-c', 'commit.gpgsign=false', 'commit', '-qm', origin, cwd=destination)
    print('Documentation source:', origin, flush=True)
    return origin


def resolve_jar(explicit=None):
    if explicit:
        jar = Path(explicit).expanduser().resolve(strict=True)
        version = 'explicit: ' + jar.name
    else:
        with urllib.request.urlopen(BASE + '/maven-metadata.xml', timeout=60) as response:
            metadata = ET.fromstring(response.read())
        versions = [v.findtext('value') for v in metadata.findall('versioning/snapshotVersions/snapshotVersion')
                    if v.findtext('classifier') == 'all' and v.findtext('extension') == 'jar']
        if not versions or not versions[-1]:
            raise RuntimeError('No all.jar Biblios snapshot in Maven metadata')
        version = versions[-1]
        jar = DOCS / '.downloads' / f'thoth-biblios-{version}-all.jar'
        jar.parent.mkdir(parents=True, exist_ok=True)
        if not jar.exists():
            partial = jar.with_suffix('.partial')
            try:
                with urllib.request.urlopen(BASE + '/' + jar.name, timeout=60) as response, partial.open('wb') as output:
                    shutil.copyfileobj(response, output)
                partial.replace(jar)
            finally:
                partial.unlink(missing_ok=True)
    hasher = hashlib.sha256()
    with jar.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(block)
    digest = hasher.hexdigest()
    print(f'Biblios version: {version}\nBiblios SHA-256: {digest}', flush=True)
    return jar, version, digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', help='Exact Git revision; CI passes GITHUB_SHA. Default: working files.')
    parser.add_argument('--jar', default=os.environ.get('BIBLIOS_JAR'), help='Optional already downloaded fat JAR')
    parser.add_argument('--serve', action='store_true', help='Serve the completed build locally; rebuild to update')
    parser.add_argument('--port', type=int, default=8080)
    args = parser.parse_args()
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java'
    jar, version, digest = resolve_jar(args.jar)
    output = ROOT / 'target/docs-site'
    with tempfile.TemporaryDirectory(prefix='biblios-source-') as temp:
        source = Path(temp) / 'source'
        source.mkdir()
        origin = snapshot(source, args.revision)
        config = (source / 'docs/biblios.yml').read_text()
        expected = 'url: ' + SOURCE
        if config.count(expected) != 1:
            raise RuntimeError('Expected exactly one public Git source in biblios.yml')
        config = config.replace(expected, 'url: ' + json.dumps(source.as_uri()))
        generated = source / 'docs/biblios.local.yml'
        generated.write_text(config)
        run(java, '-jar', str(jar), 'build', '--config', str(generated), '--output', str(output), '--clean', cwd=source)
        # GUI mockups use a smaller font; the stylesheet is appended to the generated theme.
        with (output / 'site-assets/styles.css').open('a') as styles:
            styles.write('\n' + (source / 'docs/site.css').read_text())
        # The manual links to examples/ with the same relative paths as on GitHub.
        shutil.copytree(source / 'examples', output / 'examples', dirs_exist_ok=True)
    (output / '.nojekyll').touch()
    (output / 'build-info.json').write_text(
        json.dumps(dict(source=origin, biblios=version, sha256=digest), indent=2) + '\n')
    run('python3', str(ROOT / 'scripts/check-docs-site.py'), str(output))
    if args.serve:
        handler = lambda *a, **kw: http.server.SimpleHTTPRequestHandler(*a, directory=str(output), **kw)
        with http.server.ThreadingHTTPServer(('127.0.0.1', args.port), handler) as server:
            print(f'Preview: http://127.0.0.1:{args.port}/ (Ctrl-C stops)', flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass


if __name__ == '__main__':
    main()
