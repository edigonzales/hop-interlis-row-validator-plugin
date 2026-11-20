# Repository instructions

Implement the row validator contract documented in docs/transforms/row-validator.adoc.
Keep the file validator and other plugins independent. Never normalize input text.

## CI and tests

Read the [shared CI contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md).
Use the helper revision pinned in `.github/workflows/verify.yml`.
Prerequisites: JDK 21 (compatibility 25), Maven, Python 3; Linux SWT requires xvfb-run.
From the repository root, with that helper checkout in .ci/hop-plugin-ci:

`.mvn/maven.config` selects project global settings that mirror only Central to
`https://repo.maven.apache.org/maven2/` (repo1 returned HTTP 429 in CI). The generated
user settings below still provide the INTERLIS repositories and optional credentials.

```
python3 .ci/hop-plugin-ci/scripts/write_maven_settings.py --output .ci/settings.xml
mvn -s .ci/settings.xml -U -B -ntp clean verify
python3 scripts/check-distribution.py
python3 scripts/run-e2e.py --hop-home "$HOP_HOME"
python3 scripts/build-docs.py
```

Compatibility: same Maven command with clean test. E2E installs the exact ZIP from
assemblies/plugin/target in the disposable Hop 2.19.0 installation passed as HOP_HOME. Never rebuild a CI candidate for E2E.
No tests are to be skipped. Report checks that cannot run. Publication uses the canonical ZIP and runs only on main.
