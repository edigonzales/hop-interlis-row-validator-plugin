# Examples

Open `scalar-rows.hpl` in Hop 2.19.0. Set pipeline parameter `MODEL_DIR` to the absolute
path of this directory. Choose the native local run configuration. Four original rows
are written to the Hop log only after full validation. The cache size of 1 forces spooling.

Make the last `key` equal to the first to reproduce a late UNIQUE failure with zero output.
Choose Single Pass to observe its limited scope (UNIQUE and class constraints are omitted).
The model also contains Date/Time/DateTime attributes; map typed Hop values with an explicit
zone, or strings to test native iox-ili lexical validation.
