# Installed-plugin checks

After `mvn clean verify`, run `python3 scripts/run-e2e.py --hop-home "$HOP_HOME"`
against a disposable Hop 2.19.0 installation. The runner installs the exact built
ZIP, verifies that Full validation preserves all original values in order, and
checks that a late UNIQUE failure releases no rows. Both cases use compressed
spooling and check cleanup. Pipeline definitions and logs remain under
`target/installed-e2e-*`.

The [distribution suite](https://github.com/edigonzales/hop-distributions#end-to-end-verification)
also exercises the second-pass class constraint, Single Pass behavior, empty input,
parallel configurations and typed temporal conversion.
