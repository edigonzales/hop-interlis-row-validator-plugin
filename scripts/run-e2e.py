#!/usr/bin/env python3
"""Run installed ZIPs in an isolated Hop 2.19 distribution, never on Maven's classpath."""
import argparse, csv, hashlib, json, os, shutil, subprocess, urllib.request, zipfile, time, tempfile, platform, re
from pathlib import Path
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
HOP_VERSION = '2.19.0'

def download(url, dest):
    with urllib.request.urlopen(url, timeout=120) as response, dest.open('wb') as out:
        shutil.copyfileobj(response, out)

def distribution(cache):
    cache.mkdir(parents=True, exist_ok=True)
    name = f'apache-hop-client-{HOP_VERSION}.zip'
    archive = cache / name
    checksum = cache / (name+'.sha512')
    if not checksum.exists():
        download(f'https://archive.apache.org/dist/hop/{HOP_VERSION}/{name}.sha512', checksum)
    if not archive.exists():
        temporary = archive.with_suffix('.part')
        download(f'https://archive.apache.org/dist/hop/{HOP_VERSION}/{name}', temporary)
        temporary.replace(archive)
    expected = next(w.lower() for w in checksum.read_text().split() if len(w)==128)
    assert hashlib.sha512(archive.read_bytes()).hexdigest() == expected, 'Hop ZIP checksum mismatch'
    return archive

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
    work=ROOT/'target/e2e'
    if work.exists(): shutil.rmtree(work)
    work.mkdir(parents=True)
    if os.environ.get('HOP_E2E_HOME'):
        hop=Path(os.environ['HOP_E2E_HOME']).resolve()
        assert hop!=ROOT, 'Use a disposable Hop installation'
    else:
        archive=distribution(args.cache)
        with zipfile.ZipFile(archive) as z:z.extractall(work)
        hop=work/'hop'
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
        proc=subprocess.run(command,cwd=hop,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=300)
        elapsed=time.monotonic()-start;(work/(case+'.log')).write_text(proc.stdout)
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

if __name__=='__main__':main()
