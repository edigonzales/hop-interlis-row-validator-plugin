# Installed-plugin checks

Run `python3 scripts/run-e2e.py` after `mvn clean verify`. It installs the exact ZIP into
an isolated, checksum-verified Apache Hop 2.19.0. An existing archive cache can be selected
with `--cache`. `HOP_E2E_HOME` is an optional disposable pre-extracted Hop installation.

Scenarios: full success, late UNIQUE, full class constraint, native Single Pass constraint
behavior, first invalid row, empty input, parallel copies, typed temporal day-boundary conversion and a 20,000-row full-validation
benchmark. JSONL terminal records, output counts/order/extra fields and spool cleanup are
asserted. Logs, generated pipelines and measured timings/OS peak process RSS remain under `target/e2e`.

Unit tests additionally inject spool truncation, precision errors, cancellation and metadata
errors. The benchmark timing includes JVM startup and pipeline preparation; it is not a
claim of constant memory use or a universal performance bound.
