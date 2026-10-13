# Native-width kernel diagnosis

This fixed diagnostic compares two public kernels independently: `PinnedReduction.dot` and `Q5_1Dequantizer.dequantize`. Each comparison changes only its named production class (and its own nested classes), compiled from immutable commits with the same compiler and flags. The sibling kernel stays at the baseline revision. Full class and source inventories, host/JVM flags, and all process output are retained.

Each supported host runs twelve counterbalanced fresh-process pairs per kernel. Four synthetic, nonuniform shapes cover actual Qwen projection widths; each process records cold allocation/time, then performs 20,000 warm calls and five blocks of 1,000 measured calls per shape. Inputs and outputs remain observable; every measured block's checksum and each full output-array hash must match its paired control. The separate cold/hot scalar-oracle unit tests cover offsets, tails, varying magnitudes, zero, subnormal, and nonfinite inputs.

Analysis bootstraps the twelve matched process ratios, keeping kernels and shapes separate. The original latency, allocation, and precision limits remain explicit; uncertainty is not a pass. Zero allocation in both arms is recorded as equality. Every sample is retained. Measurements on synthetic arrays diagnose the confirmed unsupported-width mechanism; they do not qualify the complete model or substitute for the two-dataset real-weight protocol. No instrumentation is enabled during these measurements.

All outputs live in the runner's temporary directory and external audit storage. This workflow never publishes a library or modifies qualification/catalog evidence.
