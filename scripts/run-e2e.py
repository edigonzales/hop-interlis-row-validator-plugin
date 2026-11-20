#!/usr/bin/env python3
"""Run installed ZIPs in an isolated Hop 2.19 distribution, never on Maven's classpath."""
import argparse
import csv
import json
import os
import signal
from pathlib import Path
import subprocess
import tempfile
from xml.sax.saxutils import escape
import zipfile

ROOT = Path(__file__).resolve().parents[1]


FIELDS = ('key', 'amount', 'start', 'finish', 'extra')
VALID_ROWS = [(str(i), '3', '1', '2', f'extra-{i}') for i in range(4)]


def transform(name, kind, body):
    return (f'<transform><name>{name}</name><type>{kind}</type><copies>1</copies>'
            f'<distribute>Y</distribute>{body}</transform>')


def pipeline(work, fixtures, case):
    rows = VALID_ROWS.copy()
    if case == 'late-unique':
        rows[-1] = ('0', *rows[-1][1:])
    fields = ''.join(f'<field><name>{name}</name><type>String</type><length>-1</length>'
                     '<precision>-1</precision></field>' for name in FIELDS)
    data = ''.join('<line>' + ''.join(f'<item>{escape(value)}</item>' for value in row)
                   + '</line>' for row in rows)
    source = transform('Input', 'DataGrid', f'<fields>{fields}</fields><data>{data}</data>')
    mappings = ''.join(f'<mapping><attribute>{name}</attribute><field>{name}</field></mapping>'
                       for name in FIELDS[:-1])
    validator = transform(
        'Validate', 'INTERLIS_ROW_VALIDATOR_TRANSFORM',
        f'<modelNames>Rows</modelNames><modelSources>{escape(str(fixtures))}</modelSources>'
        '<className>Rows.Data.Row</className><cacheRows>1</cacheRows><compress>Y</compress>'
        f'<tempDirectory>{escape(str(work / "spool"))}</tempDirectory>'
        f'<mappings>{mappings}</mappings>')
    output = transform(
        'Output', 'TextFileOutput',
        '<separator>;</separator><enclosure>"</enclosure><header>Y</header><footer>N</footer>'
        '<format>UNIX</format><encoding>UTF-8</encoding><compression>None</compression>'
        f'<file><name>{escape(str(work / case))}</name><extension>csv</extension>'
        '<split>N</split><haspartno>N</haspartno><append>N</append>'
        '<add_date>N</add_date><add_time>N</add_time><splitevery>0</splitevery></file><fields/>')
    return (f'<pipeline><info><name>{case}</name><pipeline_type>Normal</pipeline_type></info>'
            '<order><hop><from>Input</from><to>Validate</to><enabled>Y</enabled></hop>'
            '<hop><from>Validate</from><to>Output</to><enabled>Y</enabled></hop></order>'
            f'{source}{validator}{output}</pipeline>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hop-home', type=Path, required=True, help='Disposable Hop 2.19.0')
    parser.add_argument('--zip', type=Path, default=ROOT/'assemblies/plugin/target/hop-interlis-row-validator-plugin-0.1.0-SNAPSHOT.zip')
    args = parser.parse_args()
    hop = args.hop_home.resolve()
    launcher = hop / 'hop-run.sh'
    if not launcher.is_file():
        parser.error(f'Hop launcher missing: {launcher}')
    if not args.zip.is_file():
        parser.error(f'Build the plugin ZIP first: {args.zip}')
    with zipfile.ZipFile(args.zip) as archive:
        archive.extractall(hop)
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='installed-e2e-', dir=target))
    (work / 'spool').mkdir()
    metadata = work / 'config' / 'metadata' / 'pipeline-run-configuration'
    metadata.mkdir(parents=True)
    (metadata / 'local.json').write_text(json.dumps({
        'name': 'local',
        'engineRunConfiguration': {'Local': {'safe_mode': True, 'rowset_size': 2}},
    }), encoding='utf-8')
    env = dict(os.environ, HOP_CONFIG_FOLDER=str(work / 'config'),
               HOP_AUDIT_FOLDER=str(work / 'audit'), HOP_METADATA_FOLDER=str(metadata.parent))
    fixtures = ROOT / 'core/src/test/resources/models'
    header = list(FIELDS)
    for case in ('valid', 'late-unique'):
        hpl = work / f'{case}.hpl'
        hpl.write_text(pipeline(work, fixtures, case), encoding='utf-8')
        logfile = work / f'{case}.log'
        print(f'Installed Row Validator: {case} ({logfile})', flush=True)
        with logfile.open('w', encoding='utf-8') as log:
            with subprocess.Popen(['bash', str(launcher), '-f', str(hpl), '-r', 'local'],
                                  cwd=hop, env=env, stdout=log, stderr=subprocess.STDOUT,
                                  start_new_session=True) as process:
                try:
                    exit_code = process.wait(timeout=300)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    raise
        failed = case == 'late-unique'
        assert (exit_code != 0) == failed, logfile
        output = work / f'{case}.csv'
        if failed:
            diagnostics = logfile.read_text(encoding='utf-8')
            assert 'Unique constraint Rows.Data.Row.Constraint2 is violated' in diagnostics, logfile
            assert 'validation_failed; input=4, output=0' in diagnostics, logfile
        if not failed or output.exists():
            with output.open(newline='', encoding='utf-8') as stream:
                rows = list(csv.reader(stream, delimiter=';'))
            if failed:
                assert rows in ([], [header]), (case, rows)
            else:
                assert rows == [header] + [list(row) for row in VALID_ROWS], rows
        assert not list((work / 'spool').iterdir()), work / 'spool'


if __name__ == '__main__':
    main()
