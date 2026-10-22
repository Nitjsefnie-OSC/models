import { inspectGguf } from "/Users/briansam-bodden/Code/java-ai/projects/model-jars-central-gate/tools/gguf-inspector.mjs";
const T = [
  ["maple",   "deepgrove/maple-preview-GGUF",                      "maple-preview-TQ1_0-head-Q4_K.gguf"],
  ["mellum",  "JetBrains/Mellum2-12B-A2.5B-Instruct-GGUF-Q8_0",    "Mellum2-12B-A2.5B-Instruct-Q8_0.gguf"],
  ["laguna",  "poolside/Laguna-XS-2.1-GGUF",                       "Laguna-XS-2.1-Q4_K_M.gguf"],
  ["lfm2moe", "LiquidAI/LFM2.5-8B-A1B-GGUF",                       "LFM2.5-8B-A1B-Q8_0.gguf"],
  ["parakeet","mudler/parakeet-cpp-gguf",                          "tdt-0.6b-v3-q8_0.gguf"],
  ["kokoro",  "simonfxr/kokoro.cpp-GGUF",                          "kokoro-82m-f16.gguf"],
  ["inflect", "remixerdec/Inflect-Nano-v2-GGUF",                   "inflect_nano_v2_q8_0.gguf"],
  ["nemo",    "bartowski/nvidia_Nemotron-Cascade-2-30B-A3B-GGUF",  "nvidia_Nemotron-Cascade-2-30B-A3B-Q8_0.gguf"],
];
for (const [label, repo, file] of T) {
  const uri = `https://huggingface.co/${repo}/resolve/main/${encodeURI(file)}`;
  const { metadata, tensorInfos } = await inspectGguf(uri, { additionalFetchHeaders: { "User-Agent": "ModelJars-Catalog-Inspector/0.1" } });
  const sample = tensorInfos[0];
  const counts = {};
  for (const t of tensorInfos) {
    const k = JSON.stringify(Object.fromEntries(Object.entries(t).filter(([kk]) => /type|kind|dtype/i.test(kk))));
    counts[k] = (counts[k] ?? 0) + 1;
  }
  console.log(`\n${label}: fields=${Object.keys(sample).join(",")}`);
  console.log(`  dtype histogram: ${JSON.stringify(counts)}`);
  const arch = metadata["general.architecture"];
  const keys = Object.entries(metadata).filter(([k]) => k.startsWith(arch + ".")).map(([k,v]) => `${k.slice(arch.length+1)}=${v}`);
  console.log(`  ${arch}.* : ${keys.join("  ")}`);
}
