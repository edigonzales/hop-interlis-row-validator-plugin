#!/usr/bin/env python3
"""Verify the actual installable ZIP, including its private ANTLR runtime."""
import io, zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
zips=list((root/'assemblies/plugin/target').glob('hop-interlis-row-validator-plugin-*.zip'))
assert len(zips)==1, zips
with zipfile.ZipFile(zips[0]) as archive:
    names=[n for n in archive.namelist() if not n.endswith('/')]
    assert len(names)==1 and names[0].startswith('plugins/transforms/interlis-row-validator/'), names
    with zipfile.ZipFile(io.BytesIO(archive.read(names[0]))) as jar:
        entries=set(jar.namelist())
        assert 'META-INF/jandex.idx' in entries
        assert 'ch/so/agi/rowvalidator/transform/RowValidatorMeta.class' in entries
        assert 'ch/so/agi/rowvalidator/shaded/antlr/Token.class' in entries
        assert 'ch/interlis/iox_j/validator/Validator.class' in entries
        assert not any(n.startswith(('antlr/', 'org/apache/hop/', 'org/eclipse/swt/', 'org/junit/')) for n in entries)
print('Distribution verified:',zips[0])
