# Repository instructions

Implement the row validator contract documented in docs/transforms/row-validator.adoc.
Keep the file validator and other plugins independent. Never normalize input text.

## CI and tests

Read the [shared CI contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md)
and [plugin repository contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/plugin-repository-contract.md).
Workflow/helper revision: cf686f2cb631dd32722377f301692f84fe9cd6a5, profile standard-plugin.
Prerequisites: JDK 21 (compatibility 25), Maven, Python 3; Linux SWT requires xvfb-run.
From the repository root, with that helper checkout in .ci/hop-plugin-ci:

`.mvn/maven.config` selects project global settings that mirror only Central to
`https://repo.maven.apache.org/maven2/` (repo1 returned HTTP 429 in CI). The generated
user settings below still provide the INTERLIS repositories and optional credentials.

```
python3 .ci/hop-plugin-ci/scripts/write_maven_settings.py --output .ci/settings.xml
mvn -s .ci/settings.xml -U -B -ntp clean verify
python3 .ci/hop-plugin-ci/scripts/check-plugin-repository.py --profile standard-plugin
python3 scripts/check-distribution.py
python3 scripts/run-e2e.py
python3 scripts/build-docs.py
```

Compatibility: same Maven command with clean test. E2E installs the exact ZIP from
assemblies/plugin/target in a disposable Hop 2.19.0 installation; HOP_E2E_HOME may
point at a disposable preinstalled Hop. Never rebuild a CI candidate for E2E.
No tests are to be skipped. Report checks that cannot run. No publication from PRs.
Publication resolves the published ZIP into a fresh Maven cache and compares its bytes.
