# Scripts

- `build-and-install.sh [hop-directory]`: builds with JDK 21, runs tests, checks the ZIP and installs it; defaults to `/Users/stefan/Downloads/hop`.
- `check-distribution.py`: validates installation root, plugin index, embedded engine and private ANTLR; rejects bundled host libraries.
- `run-e2e.py`: installs and runs the canonical ZIP without rebuilding it.
- `test_run_e2e.py`: offline HTTP/download, fallback, checksum and process-timeout regression tests.
- `build-docs.py`: Biblios from the exact checkout; `--revision` selects a commit and `--serve` opens a local HTTP preview.
- `check-docs-site.py`: validates generated links, GUI styles and search content.

The installer uses `HOP_CI_DIR` when set, otherwise `.ci/hop-plugin-ci` or the sibling
`hop-plugin-ci` checkout for Maven settings. It prefers JDK 21 in `JAVA_HOME`, then
searches macOS Java homes and SDKMAN. It stages the verified ZIP on the target filesystem,
replaces only `plugins/transforms/interlis-row-validator`, and restores the previous plugin
if the final rename fails. Close Hop before installing and restart it afterwards.
On headless Linux, invoke it with `xvfb-run -a` for the SWT tests.

Optional repository credentials use `INTERLIS_MAVEN_USERNAME` and
`INTERLIS_MAVEN_PASSWORD` (set both). The installer maps them to the shared helper's
`MAVEN_USERNAME`/`MAVEN_PASSWORD` environment variables for `sogeo-snapshots`;
the generated settings contain only environment references. GitHub publication uses
repository secrets with the same `INTERLIS_MAVEN_*` names.
