#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 MODEL_GGUF MODEL_ID [OUTPUT_DIR]" >&2
  exit 2
fi

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MODEL_INPUT=$1
MODEL_PATH=$(cd "$(dirname "$MODEL_INPUT")" && pwd -P)/$(basename "$MODEL_INPUT")
MODEL_ID=$2
OUTPUT_DIR=${3:-"$ROOT_DIR/build/reports/determinism/$MODEL_ID"}
PROMPT=${AUDIT_PROMPT:-}
PROMPT_FILE=${AUDIT_PROMPT_FILE:-"$ROOT_DIR/models-bench/prompts/completion.txt"}
TOKENS=${AUDIT_TOKENS:-4}
ITERATIONS=${AUDIT_ITERATIONS:-3}
CONTEXT=${AUDIT_CONTEXT:-128}
PREFILL=${AUDIT_PREFILL:-sequential}
VARIANT=${AUDIT_VARIANT:-}
VECTORS_CORE_JAR=${AUDIT_VECTORS_CORE_JAR:-}
VECTORS_CORE_REVISION=${AUDIT_VECTORS_CORE_REVISION:-}

case "$VARIANT" in
  ""|panama-default|panama-serial|panama-no-fma|panama-128|scalar) ;;
  *) echo "unknown AUDIT_VARIANT: $VARIANT" >&2; exit 2 ;;
esac
if [[ -n "$VECTORS_CORE_JAR" ]]; then
  if [[ ! -f "$VECTORS_CORE_JAR" || ! "$VECTORS_CORE_REVISION" =~ ^[0-9a-fA-F]{40}$ ]]; then
    echo "diagnostic core requires an existing JAR and immutable AUDIT_VECTORS_CORE_REVISION" >&2
    exit 2
  fi
  VECTORS_CORE_JAR=$(cd "$(dirname "$VECTORS_CORE_JAR")" && pwd -P)/$(basename "$VECTORS_CORE_JAR")
  command -v python3 >/dev/null || { echo "diagnostic provenance requires python3" >&2; exit 1; }
fi

for command in awk java; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command not found: $command" >&2
    exit 1
  fi
done
JAVA_FEATURE=$(java -XshowSettings:properties -version 2>&1 \
  | awk -F= '/^[[:space:]]*java.specification.version[[:space:]]*=/{gsub(/[[:space:]]/, "", $2); print $2}')
if [[ "$JAVA_FEATURE" != 25 ]]; then
  echo "the determinism audit requires Java 25; default java is $JAVA_FEATURE" >&2
  exit 1
fi
if [[ ! -f "$MODEL_PATH" ]]; then
  echo "model not found: $MODEL_PATH" >&2
  exit 1
fi
if [[ -z "$PROMPT" && ! -f "$PROMPT_FILE" ]]; then
  echo "prompt not found: $PROMPT_FILE" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
"$ROOT_DIR/gradlew" --no-daemon -p "$ROOT_DIR" :models-bench:installDist
AUDIT_CLI="$ROOT_DIR/models-bench/build/install/models-bench/bin/models-bench"
AUDIT_LIB="$ROOT_DIR/models-bench/build/install/models-bench/lib"
if [[ -n "$VECTORS_CORE_JAR" ]]; then
  python3 - "$VECTORS_CORE_JAR" "$VECTORS_CORE_REVISION" "$OUTPUT_DIR" "$ROOT_DIR" "$AUDIT_LIB" "$VARIANT" <<'PY_PROVENANCE'
import hashlib, json, pathlib, subprocess, sys
jar, revision, output, root, libraries, variant = sys.argv[1:]
provenance = {
    "scope": "Diagnostic classpath overlay; released dependency pins are unchanged",
    "vectorsCoreRevision": revision,
    "vectorsCoreJar": jar,
    "vectorsCoreSha256": hashlib.sha256(pathlib.Path(jar).read_bytes()).hexdigest(),
    "modelsRevision": subprocess.check_output(["git", "-C", root, "rev-parse", "HEAD"], text=True).strip(),
    "variant": variant or "all",
    "classpath": jar + ":" + libraries + "/*",
    "distributionCoreJars": [
        {"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
        for p in sorted(pathlib.Path(libraries).glob("vectors-core-*.jar"))
    ],
}
(pathlib.Path(output) / "diagnostic-core.json").write_text(json.dumps(provenance, indent=2) + "\n")
PY_PROVENANCE
fi
if [[ -n "$PROMPT" ]]; then
  PROMPT_ARGS=(--prompt "$PROMPT")
else
  PROMPT_ARGS=(--prompt-file "$PROMPT_FILE")
fi

run_audit() {
  local name=$1
  local java_options=$2
  echo "auditing $name with JAVA_OPTS='$java_options'"
  local -a launcher
  if [[ -n "$VECTORS_CORE_JAR" ]]; then
    launcher=(java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
      -XX:NativeMemoryTracking=summary)
    if [[ -n "$java_options" ]]; then
      local -a configuration_options
      read -r -a configuration_options <<< "$java_options"
      launcher+=("${configuration_options[@]}")
    fi
    launcher+=(-cp "$VECTORS_CORE_JAR:$AUDIT_LIB/*"
      com.integrallis.models.bench.InferenceBenchmarkCli)
  else
    launcher=("$AUDIT_CLI")
  fi
  env JAVA_OPTS="$java_options" "${launcher[@]}" determinism \
    --model "$MODEL_PATH" \
    --model-id "$MODEL_ID" \
    "${PROMPT_ARGS[@]}" \
    --tokens "$TOKENS" \
    --iterations "$ITERATIONS" \
    --context "$CONTEXT" \
    --prefill "$PREFILL" \
    --output "$OUTPUT_DIR/$name.json"
}

failed_audits=0
check_audit() {
  if [[ -n "$VARIANT" && "$VARIANT" != "$1" ]]; then
    return
  fi
  if ! run_audit "$@"; then
    echo "determinism audit failed: $1" >&2
    failed_audits=$((failed_audits + 1))
  fi
}

check_audit panama-default ""
check_audit panama-serial "-Dvectors.gguf.parallel=false"
check_audit panama-no-fma \
  "-Dvectors.gguf.parallel=false -Dvectors.useVectorFMA=false -Dvectors.useScalarFMA=false"
check_audit panama-128 \
  "-Dvectors.gguf.parallel=false -Dvectors.maxBits=128"
check_audit scalar \
  "-Dvectors.forceScalar=true -Dvectors.gguf.parallel=false"

echo "determinism evidence: $OUTPUT_DIR"
if ((failed_audits > 0)); then
  echo "$failed_audits determinism audit(s) failed" >&2
  exit 1
fi
