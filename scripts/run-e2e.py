#!/usr/bin/env python3
"""Run installed ZIPs in an isolated Hop 2.19 distribution, never on Maven's classpath."""
import argparse, csv, hashlib, json, os, shutil, subprocess, zipfile, time, platform, re, signal
from pathlib import Path
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
HOP_VERSION = '2.19.0'
DOWNLOAD_BASES = ('https://dlcdn.apache.org/hop', 'https://archive.apache.org/dist/hop')

def log(message):
    print(f'[e2e] {message}', flush=True)

def run_logged(command, logfile, *, cwd=None, env=None, timeout=300,
               progress=None, progress_interval=15):
    """Keep output on disk and bound the entire process, including its children on POSIX."""
    start = time.monotonic()
    with logfile.open('w', encoding='utf-8') as output:
        proc = subprocess.Popen(command, cwd=cwd, env=env, stdout=output,
                                stderr=subprocess.STDOUT, start_new_session=os.name == 'posix')
        try:
            while True:
                remaining = timeout - (time.monotonic() - start)
                if remaining <= 0:
                    raise TimeoutError(f'Time limit of {timeout}s exceeded; log: {logfile}')
                try:
                    proc.wait(timeout=min(progress_interval, remaining))
                    break
                except subprocess.TimeoutExpired:
                    if progress:
                        progress(time.monotonic() - start)
        finally:
            if proc.poll() is None:
                if os.name == 'posix':
                    os.killpg(proc.pid, signal.SIGKILL)
                else:
                    proc.kill()
                proc.wait()
    return subprocess.CompletedProcess(command, proc.returncode,
                                       logfile.read_text(encoding='utf-8', errors='replace'))

def download(url, dest, *, timeout=300):
    """Use a total deadline and a low-speed limit, not just a per-socket-read timeout."""
    if not shutil.which('curl'):
        raise RuntimeError('curl is required to download the Hop distribution')
    partial = dest.with_name(dest.name + '.part')
    logfile = dest.with_name(dest.name + '.download.log')
    log(f'Downloading {url} (limit {timeout}s)')
    try:
        proc = run_logged(
            ['curl', '--fail', '--location', '--silent', '--show-error',
             '--connect-timeout', '20', '--max-time', str(timeout),
             '--speed-limit', '16384', '--speed-time', '30',
             '--output', str(partial), url], logfile, timeout=timeout + 5,
            progress=lambda elapsed: log(
                f'Download: {(partial.stat().st_size if partial.exists() else 0) / 1048576:.1f} MiB'
                f' received in {elapsed:.0f}s'))
        if proc.returncode:
            raise RuntimeError(f'Download failed: {url}\n{proc.stdout.strip()}')
        partial.replace(dest)
        log(f'Download complete: {dest.name} ({dest.stat().st_size / 1048576:.1f} MiB)')
    finally:
        partial.unlink(missing_ok=True)

def verify_checksum(archive, checksum):
    hashes = re.findall(r'\b[0-9a-fA-F]{128}\b', checksum.read_text(encoding='utf-8'))
    if len(hashes) != 1:
        raise ValueError(f'Expected one SHA-512 checksum in {checksum}')
    digest = hashlib.sha512()
    with archive.open('rb') as data:
        for chunk in iter(lambda: data.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != hashes[0].lower():
        raise ValueError(f'Hop ZIP checksum mismatch: {archive}')

def distribution(cache, bases=DOWNLOAD_BASES):
    cache.mkdir(parents=True, exist_ok=True)
    name = f'apache-hop-client-{HOP_VERSION}.zip'
    archive = cache / name
    checksum = cache / (name+'.sha512')
    if archive.exists() and checksum.exists():
        log(f'Checking cached Hop distribution: {archive}')
        try:
            verify_checksum(archive, checksum)
            return archive
        except ValueError as error:
            log(f'{error}; downloading a fresh copy')
    failures = []
    for base in bases:
        try:
            download(f'{base}/{HOP_VERSION}/{name}.sha512', checksum, timeout=30)
            download(f'{base}/{HOP_VERSION}/{name}', archive)
            log('Verifying Hop SHA-512 checksum')
            verify_checksum(archive, checksum)
            return archive
        except (OSError, ValueError, RuntimeError) as error:
            failures.append(str(error))
            archive.unlink(missing_ok=True)
            checksum.unlink(missing_ok=True)
            log(f'{error}; trying the next download source if available')
    raise RuntimeError('Cannot obtain a verified Hop distribution:\n' + '\n'.join(failures))

def transform(name, kind, body, x, copies=1):
    return f'<transform><name>{name}</name><type>{kind}</type><copies>{copies}</copies><distribute>Y</distribute>{body}<GUI><xloc>{x}</xloc><yloc>160</yloc></GUI></transform>'

def pipeline(work, fixtures, case, count=4, example=False):
    single=case.startswith('single')
    copies=2 if case=='single-parallel' else 1
    rows=[] if case=='empty' else [(str(i), '9999' if case=='single-invalid' and i==1 else '3', '9' if 'constraint' in case else '1', '2',f'extra-{i}') for i in range(count)]
    if case=='late-unique': rows[-1]=('0',*rows[-1][1:])
    fields=['key','amount','start','finish','extra']
    source=transform('Input','DataGrid','<fields>'+''.join(f'<field><name>{name}</name><type>String</type><length>-1</length><precision>-1</precision></field>' for name in fields)+'</fields><data>'+''.join('<line>'+''.join('<item>'+escape(v)+'</item>' for v in row)+'</line>' for row in rows)+'</data>',100)
    mapping='<mappings>'+''.join(f'<mapping><attribute>{name}</attribute><field>{name}</field></mapping>' for name in fields[:-1])+'</mappings>'
    sources='${MODEL_DIR}' if example else str(fixtures)
    reports='' if example else str(work/'reports'/case)
    temp='' if example else str(work/'spool')
    validator=transform('Validate','INTERLIS_ROW_VALIDATOR_TRANSFORM',f'<modelNames>Rows</modelNames><modelSources>{escape(sources)}</modelSources><className>Rows.Data.Row</className><singlePassOnly>{"Y" if single else "N"}</singlePassOnly><cacheRows>1</cacheRows><compress>Y</compress><tempDirectory>{escape(temp)}</tempDirectory><reportDirectory>{escape(reports)}</reportDirectory><sourceRowField>extra</sourceRowField>{mapping}',340,copies)
    if example:
        output=transform('Output','WriteToLog','<loglevel>log_level_basic</loglevel><displayHeader>Y</displayHeader><limitRows>N</limitRows><logmessage>Validated row</logmessage><fields>'+''.join(f'<field><name>{name}</name></field>' for name in fields)+'</fields>',580)
    else:
        output=transform('Output','TextFileOutput',f'<separator>;</separator><enclosure>"</enclosure><header>Y</header><footer>N</footer><format>UNIX</format><encoding>UTF-8</encoding><compression>None</compression><file><name>{escape(str(work/case))}</name><extension>csv</extension><split>N</split><haspartno>N</haspartno><append>N</append><add_date>N</add_date><add_time>N</add_time><splitevery>0</splitevery></file><fields>'+''.join(f'<field><name>{name}</name><type>String</type></field>' for name in fields)+'</fields>',580)
    params='<parameters><parameter><name>MODEL_DIR</name><default_value>${PROJECT_HOME}/examples</default_value></parameter></parameters>' if example else ''
    return f'<?xml version="1.0" encoding="UTF-8"?><pipeline><info><name>{case}</name><pipeline_type>Normal</pipeline_type>{params}</info><order><hop><from>Input</from><to>Validate</to><enabled>Y</enabled></hop><hop><from>Validate</from><to>Output</to><enabled>Y</enabled></hop></order>{source}{validator}{output}</pipeline>'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache',type=Path,default=Path.home()/'.cache/apache-hop')
    parser.add_argument('--zip',type=Path,default=ROOT/'assemblies/plugin/target/hop-interlis-row-validator-plugin-0.1.0-SNAPSHOT.zip')
    parser.add_argument('--benchmark-rows',type=int,default=20000)
    args=parser.parse_args()
    log(f'Starting installed-plugin checks with {args.zip}')
    if not args.zip.is_file():
        parser.error(f'Plugin ZIP not found: {args.zip}; build it before running E2E')
    work=ROOT/'target/e2e'
    if work.exists(): shutil.rmtree(work)
    work.mkdir(parents=True)
    if os.environ.get('HOP_E2E_HOME'):
        hop=Path(os.environ['HOP_E2E_HOME']).resolve()
        assert hop!=ROOT, 'Use a disposable Hop installation'
        log(f'Using disposable Hop installation: {hop}')
    else:
        archive=distribution(args.cache)
        log(f'Extracting {archive} into {work}')
        with zipfile.ZipFile(archive) as z:z.extractall(work)
        hop=work/'hop'
    log('Installing the exact plugin ZIP')
    with zipfile.ZipFile(args.zip) as z:z.extractall(hop)
    for script in hop.glob('*.sh'):script.chmod(0o755)
    (work/'spool').mkdir()
    fixtures=ROOT/'core/src/test/resources/models'
    metadata=work/'config/metadata/pipeline-run-configuration';metadata.mkdir(parents=True)
    (metadata/'local.json').write_text(json.dumps({'name':'local','engineRunConfiguration':{'Local':{'safe_mode':True,'rowset_size':'1000'}}}))
    env=os.environ.copy();env.update({'HOP_CONFIG_FOLDER':str(work/'config'),'HOP_AUDIT_FOLDER':str(work/'audit'),'HOP_METADATA_FOLDER':str(metadata.parent),'HOP_JAVA_HOME':env.get('JAVA_HOME','')})
    (work/'audit').mkdir()
    results=[]
    for case in ('valid','late-unique','full-constraint','single-constraint','single-invalid','empty','single-parallel','temporal','benchmark'):
        count=args.benchmark_rows if case=='benchmark' else 4
        xml=pipeline(work,fixtures,case,count)
        if case=='temporal':
            tree=ET.fromstring(xml)
            source=next(t for t in tree.findall('transform') if t.findtext('name')=='Input')
            fields=source.find('fields')
            field=ET.SubElement(fields,'field')
            for key,value in {'name':'instant','type':'Date','format':"yyyy-MM-dd'T'HH:mm:ss.SSSX",'length':'-1','precision':'-1'}.items():ET.SubElement(field,key).text=value
            for line in source.findall('data/line'):ET.SubElement(line,'item').text='2024-02-29T23:30:00.123Z'
            validator=next(t for t in tree.findall('transform') if t.findtext('name')=='Validate')
            validator.find('modelNames').text='TimeGate';validator.find('className').text='TimeGate.Data.Row'
            ET.SubElement(validator,'timeZone').text='Europe/Zurich'
            mappings=validator.find('mappings');mappings.clear()
            for attr,field in [('key','key'),('day','instant'),('clock','instant'),('moment','instant')]:
                m=ET.SubElement(mappings,'mapping');ET.SubElement(m,'attribute').text=attr;ET.SubElement(m,'field').text=field
            xml=ET.tostring(tree,encoding='unicode')
        # The benchmark uses larger chunks, avoiding thousands of tiny temporary files.
        if case=='benchmark':xml=xml.replace('<cacheRows>1</cacheRows>','<cacheRows>1000</cacheRows>')
        hpl=work/(case+'.hpl');hpl.write_text(xml)
        start=time.monotonic()
        command=['bash',str(hop/'hop-run.sh'),'-f',str(hpl),'-r','local']
        # OS peak resident memory of the whole Hop process, including JVM and plugins.
        measured=Path('/usr/bin/time').exists() and platform.system() in ('Darwin','Linux')
        if measured:command=['/usr/bin/time','-l' if platform.system()=='Darwin' else '-v',*command]
        logfile=work/(case+'.log')
        log(f'Running {case}; log: {logfile} (limit 300s)')
        try:
            proc=run_logged(command,logfile,cwd=hop,env=env,timeout=300,
                            progress=lambda elapsed: log(f'{case}: running for {elapsed:.0f}s; log: {logfile}'))
        except TimeoutError:
            log(logfile.read_text(encoding='utf-8',errors='replace')[-9000:])
            raise
        elapsed=time.monotonic()-start
        failed=case in ('late-unique','full-constraint','single-invalid')
        assert (proc.returncode!=0)==failed,proc.stdout[-9000:]
        reports=list((work/'reports'/case).glob('*.jsonl'))
        assert len(reports)==(2 if case=='single-parallel' else 1),(case,reports,proc.stdout[-8000:])
        ends=[]
        for report in reports:
            records=[json.loads(line) for line in report.read_text().splitlines()]
            assert records[0]['record_type']=='run_start'
            assert records[-1]['record_type']=='run_end',records[-3:]
            validation=[r for r in records if r['record_type']=='validation_end'];assert len(validation)==1
            assert validation[0]['release']==(not failed),validation
            ends.append(records[-1])
            for issue in (r for r in records if r['record_type']=='issue'):
                if 'copy' in issue: assert issue['copy']==records[0]['copy']
        emitted=sum(r['output_rows'] for r in ends)
        expected=0 if failed or case=='empty' else count
        if case=='single-invalid': assert emitted==1,ends
        else: assert emitted==expected,ends
        if not failed:
            with (work/(case+'.csv')).open() as f: rows=list(csv.DictReader(f,delimiter=';'))
            assert len(rows)==expected,(case,len(rows),expected)
            if case!='single-parallel':assert [r['extra'] for r in rows]==[f'extra-{i}' for i in range(expected)]
        elif case!='single-invalid' and (work/(case+'.csv')).exists():
            with (work/(case+'.csv')).open() as f:assert not list(csv.DictReader(f,delimiter=';'))
        assert not list((work/'spool').iterdir()),'Temporary files leaked'
        result={'case':case,'input_rows':count if case!='empty' else 0,'emitted_rows':emitted,'seconds':round(elapsed,3),'exit_code':proc.returncode}
        if measured:
            match=re.search(r'(\d+)\s+maximum resident set size',proc.stdout) if platform.system()=='Darwin' else re.search(r'Maximum resident set size \(kbytes\): (\d+)',proc.stdout)
            assert match,'Missing OS memory measurement'
            result['process_peak_rss_bytes']=int(match.group(1))*(1 if platform.system()=='Darwin' else 1024)
        results.append(result);print(json.dumps(result),flush=True)
    (work/'results.json').write_text(json.dumps(results,indent=2)+'\n')
    log(f'All {len(results)} installed-plugin scenarios passed')

if __name__=='__main__':main()
