# Independent continuation check

Use the already installed llama.cpp server, record its version and executable hash, and load the
exact same local Qwen3.5-4B Q4_K_M artifact used by the experiment. CPU only, four decode and batch
threads, context 2048, one slot, F32 key/value cache, flash attention disabled. This is a numerical
cross-check, not a performance comparison or complete independent qualification.

Export Models' exact prompt token arrays for the two already selected continuation cases:
`original-routing-06-0` (JevBench) and `snips-AddToPlaylist-0` (SNIPS). Check independent tokenization
too, but pass the explicit token arrays to completion so no chat-template rewrite can enter the
inference comparison. Request eight greedy tokens, no prompt caching, no penalties, and return
raw token IDs and top-token probabilities. Preserve complete requests and responses, including
any mismatch. Do not tune the prompt or sampler after seeing an answer.

Compare token IDs with the retained current-Java continuation and corrected candidates. These
comparisons cannot prove full-logit equivalence to an external runtime with a different arithmetic
contract. A mismatch is an unresolved oracle difference to diagnose; it is not automatically a
regression caused by the candidate when current Java produced the same result.

Launch only a loopback server owned by this script; enforce a ten-minute overall deadline and
terminate that process group in a finally block. No unrelated local service is touched.
