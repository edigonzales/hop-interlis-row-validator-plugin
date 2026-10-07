# hop-interlis-row-validator-plugin

An independent Apache Hop transform that validates a finite row stream against an INTERLIS class with ili2c/iox-ili.

## Features

- Full validation retains original rows until successful completion, including the second pass.
- Native Single Pass emits checked rows immediately with its explicitly limited scope.
- Scalar mapping, inherited attributes, Date/Time/DateTime, explicit conversion time zone.
- Bounded original-row cache with optional compressed disk spooling; streaming JSONL diagnostics.
- Native Hop dialog in English and German. No dependency on the file validator plugin.

## Requirements

Apache Hop 2.19.0, Java 21; Java 25 compatibility is checked by CI. Native local/server engine only.
Full validation requires one copy without partitioning. The complete input must be finite.

## Install

Unzip `assemblies/plugin/target/hop-interlis-row-validator-plugin-0.1.0-SNAPSHOT.zip` into a disposable Hop home for testing, or into your Hop installation, then restart Hop.
The ZIP installs under `plugins/transforms/interlis-row-validator`.

## Documentation

[Rendered handbook](https://edigonzales.github.io/hop-interlis-row-validator-plugin/row-validator/main/index.html),
[canonical source](docs/index.adoc), [examples](examples/README.md), [development and tests](docs/development.adoc).
Build the exact working documentation with `python3 scripts/build-docs.py`; output is `target/docs-site`.

## Build and development

JDK 21, Maven and Python 3 are required. Run `mvn -B -ntp clean verify`, then
`python3 scripts/check-distribution.py` and `python3 scripts/run-e2e.py`.
See [AGENTS.md](AGENTS.md) for the canonical repository settings and compatibility commands.
Linux SWT tests run under `xvfb-run -a`. Tests use local synthetic models.

For local development, build, test and copy the plugin into Hop with:

```bash
./scripts/build-and-install.sh                    # /Users/stefan/Downloads/hop
./scripts/build-and-install.sh /path/to/hop        # another Hop 2.19.0 installation
```

Close Hop before running the script and restart it afterwards. The script selects JDK 21,
verifies the distribution and replaces only this plugin. See [scripts/README.md](scripts/README.md)
for CI helper checkout and headless Linux requirements.

## Modules and artifacts

| Module | Responsibility |
|---|---|
| `core` | Hop-independent model analysis, scalar conversion, validator session, JSONL |
| `transform` | Hop metadata, runtime, spooling and SWT dialog |
| `assemblies/plugin` | Installable `ch.so.agi:hop-interlis-row-validator-plugin:0.1.0-SNAPSHOT` ZIP |

The distribution shades its library dependencies and relocates ANTLR; Hop/SWT are provided by the host.

## CI and publication

Shared CI is pinned to `cf686f2cb631dd32722377f301692f84fe9cd6a5` for both workflows and helpers.
Java 21/25 on Linux/macOS/Windows; Ubuntu/21 builds the sole canonical ZIP. Package checks and
installed-Hop E2E test that ZIP. Main snapshot publication reuses those exact bytes, with a fresh
Maven-resolution comparison afterwards. PRs never publish. Biblios deploys only from main.

## License

See [LICENSE](LICENSE).
