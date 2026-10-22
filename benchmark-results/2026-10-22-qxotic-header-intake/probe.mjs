import { inspectGguf } from "/Users/briansam-bodden/Code/java-ai/projects/model-jars-central-gate/tools/gguf-inspector.mjs";

// Every entry comes from qxotic's own test manifest, jinfer/scripts/models.txt.
const TARGETS = [
  ["laguna",       "poolside/Laguna-XS-2.1-GGUF",                        "Laguna-XS-2.1-Q4_K_M.gguf"],
  ["mellum",       "JetBrains/Mellum2-12B-A2.5B-Instruct-GGUF-Q8_0",     "Mellum2-12B-A2.5B-Instruct-Q8_0.gguf"],
  ["maple",        "deepgrove/maple-preview-GGUF",                       "maple-preview-TQ1_0-head-Q4_K.gguf"],
  ["nemotron",     "bartowski/nvidia_Nemotron-Cascade-2-30B-A3B-GGUF",   "nvidia_Nemotron-Cascade-2-30B-A3B-Q8_0.gguf"],
  ["lfm2moe",      "LiquidAI/LFM2.5-8B-A1B-GGUF",                        "LFM2.5-8B-A1B-Q8_0.gguf"],
  ["inflect-v2",   "remixerdec/Inflect-Nano-v2-GGUF",                    "inflect_nano_v2_q8_0.gguf"],
  ["kokoro",       "simonfxr/kokoro.cpp-GGUF",                           "kokoro-82m-f16.gguf"],
  ["kokoro-voice", "simonfxr/kokoro.cpp-GGUF",                           "voices/kokoro-voice-af_heart.gguf"],
  ["parakeet",     "mudler/parakeet-cpp-gguf",                           "tdt-0.6b-v3-q8_0.gguf"],
  ["minicpm?",     "openbmb/MiniCPM5-1B-GGUF",                           "MiniCPM5-1B-Q8_0.gguf"],
];

const out = [];
for (const [label, repo, file] of TARGETS) {
  const uri = `https://huggingface.co/${repo}/resolve/main/${encodeURI(file)}`;
  const row = { label, repo, file, uri };
  try {
    const head = await fetch(uri, { method: "HEAD", redirect: "follow" });
    row.httpStatus = head.status;
    const len = head.headers.get("x-linked-size") ?? head.headers.get("content-length");
    row.bytes = len ? Number(len) : null;
    const { metadata, tensorInfos } = await inspectGguf(uri, {
      additionalFetchHeaders: { "User-Agent": "ModelJars-Catalog-Inspector/0.1" },
    });
    row.architecture = metadata["general.architecture"] ?? null;
    row.name = metadata["general.name"] ?? null;
    row.tensorCount = tensorInfos.length;
    const roots = new Set();
    for (const t of tensorInfos) {
      const n = t.name;
      roots.add(n.startsWith("blk.") ? "blk." + n.split(".").slice(2).join(".") : n.split(".")[0]);
    }
    row.blockTensors = [...roots].filter((r) => r.startsWith("blk.")).sort();
    row.topTensors = [...roots].filter((r) => !r.startsWith("blk.")).sort();
    row.quants = [...new Set(tensorInfos.map((t) => t.type ?? t.ggmlType))].sort();
    row.keys = Object.fromEntries(
      Object.entries(metadata).filter(
        ([k, v]) => !k.startsWith("tokenizer.") && !Array.isArray(v) && !k.startsWith("general.") ,
      ),
    );
  } catch (error) {
    row.error = String(error).slice(0, 300);
  }
  out.push(row);
  console.error(`probed ${label}: ${row.architecture ?? row.error ?? "?"}`);
}
await (await import("node:fs/promises")).writeFile("headers.json", JSON.stringify(out, null, 2));
