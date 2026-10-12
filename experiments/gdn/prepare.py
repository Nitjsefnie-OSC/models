"""Generate exact, auditable experimental copies; never edit production dispatch."""
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[2]
dest = root / 'experiments/gdn/generated'
dest.mkdir(exist_ok=True)
source = (root / 'backend-java/src/main/java/com/integrallis/models/backend/purejava/qwen35/GatedDeltaNetRecurrence.java').read_text()
old = '''    for (int token = 0; token < tokenCount; token++) {
      for (int head = 0; head < valueHeadCount; head++) {'''
assert source.count(old) == 1
candidate = source.replace('GatedDeltaNetRecurrence', 'HeadMajorRecurrence').replace(old, '''    for (int head = 0; head < valueHeadCount; head++) {
      for (int token = 0; token < tokenCount; token++) {''')
(dest / 'HeadMajorRecurrence.java').write_text(candidate)
fused = candidate.replace('HeadMajorRecurrence', 'FusedRecurrence')
scale = '''        for (int index = 0; index < keyDimension * valueDimension; index++) {
          state[stateOffset + index] *= decay;
        }
'''
assert fused.count(scale) == 1
fused = fused.replace(scale, '')
memory = '''        for (int row = 0; row < keyDimension; row++) {
          VectorUtil.addScaledInPlace(
              memory,'''
assert fused.count(memory) == 1
fused = fused.replace(memory, '''        for (int row = 0; row < keyDimension; row++) {
          int start = stateOffset + row * valueDimension;
          for (int col = 0; col < valueDimension; col++) state[start + col] *= decay;
          VectorUtil.addScaledInPlace(
              memory,''')
update = '''        for (int row = 0; row < keyDimension; row++) {
          VectorUtil.addScaledInPlace(
              state,'''
assert fused.count(update) == 1
fused = fused.replace(update, '''        Arrays.fill(output, valueOffset, valueOffset + valueDimension, 0.0f);
        for (int row = 0; row < keyDimension; row++) {
          VectorUtil.addScaledInPlace(
              state,''')
separator = '''        }

        Arrays.fill(output, valueOffset, valueOffset + valueDimension, 0.0f);
        for (int row = 0; row < keyDimension; row++) {
          VectorUtil.addScaledInPlace(
              output,'''
assert fused.count(separator) == 1
fused = fused.replace(separator, '''          VectorUtil.addScaledInPlace(
              output,''')
(dest / 'FusedRecurrence.java').write_text(fused)
parallel = fused.replace('FusedRecurrence', 'ParallelRecurrence')
parallel = parallel.replace('final class ParallelRecurrence {', '''final class ParallelRecurrence {
  static final ThreadLocal<int[]> HEAD_RANGE = ThreadLocal.withInitial(() -> new int[]{0, Integer.MAX_VALUE});''')
parallel = parallel.replace('for (int head = 0; head < valueHeadCount; head++) {',
    'for (int head = HEAD_RANGE.get()[0]; head < Math.min(valueHeadCount, HEAD_RANGE.get()[1]); head++) {')
(dest / 'ParallelRecurrence.java').write_text(parallel)

# Four independent SIMD accumulators preserve each column's row order while exposing
# instruction-level parallelism and visiting adjacent state columns together.
tiled=(root/'experiments/gdn/RegisterRecurrence.java').read_text().replace('RegisterRecurrence','RegisterTiledRecurrence')
marker='        for (int col = 0; col < vectorEnd; col += SPECIES.length()) {'
assert tiled.count(marker)==1
lines=['        int col = 0;',
       '        for (; col + 4 * SPECIES.length() <= vectorEnd; col += 4 * SPECIES.length()) {']
for n in range(4):lines.append(f'          var memory{n} = FloatVector.zero(SPECIES);')
lines += ['          for (int row = 0; row < kd; row++) {',
          '            int cell = stateAt + row * vd + col;',
          '            var key = FloatVector.broadcast(SPECIES, k[row]);']
for n in range(4):
    lines += [f'            var decayed{n} = FloatVector.fromArray(SPECIES, state, cell + {n} * SPECIES.length()).mul(decay);',
              f'            memory{n} = CAPS.fastVectorFma() ? decayed{n}.fma(key, memory{n}) : decayed{n}.mul(key).add(memory{n});']
lines.append('          }')
for n in range(4):
    lines += [f'          var delta{n} = FloatVector.fromArray(SPECIES, i.v(), at + col + {n} * SPECIES.length()).sub(memory{n}).mul(i.b()[th]);',
              f'          var result{n} = FloatVector.zero(SPECIES);']
lines += ['          for (int row = 0; row < kd; row++) {',
          '            int cell = stateAt + row * vd + col;',
          '            var key = FloatVector.broadcast(SPECIES, k[row]);',
          '            var query = FloatVector.broadcast(SPECIES, q[row]);']
for n in range(4):
    lines += [f'            var decayed{n} = FloatVector.fromArray(SPECIES, state, cell + {n} * SPECIES.length()).mul(decay);',
              f'            var updated{n} = CAPS.fastVectorFma() ? delta{n}.fma(key, decayed{n}) : delta{n}.mul(key).add(decayed{n});',
              f'            updated{n}.intoArray(state, cell + {n} * SPECIES.length());',
              f'            result{n} = CAPS.fastVectorFma() ? updated{n}.fma(query, result{n}) : updated{n}.mul(query).add(result{n});']
lines.append('          }')
for n in range(4):lines.append(f'          result{n}.intoArray(out, at + col + {n} * SPECIES.length());')
lines += ['        }','        for (; col < vectorEnd; col += SPECIES.length()) {']
tiled=tiled.replace(marker,'\n'.join(lines)).replace('for (int col = vectorEnd; col < vd; col++) {','for (col = vectorEnd; col < vd; col++) {')
(dest/'RegisterTiledRecurrence.java').write_text(tiled)
slot=tiled.replace('RegisterTiledRecurrence','SlotTiledRecurrence')
signature='int firstHead, int endHead) {'
assert slot.count(signature)==1
slot=slot.replace(signature,'int firstHead, int endHead, int firstToken, int endToken, int stateBase) {')
slot=slot.replace('for (int t = 0; t < i.tokens(); t++) {','for (int t = firstToken; t < endToken; t++) {')
slot=slot.replace('stateAt = h * kd * vd;','stateAt = stateBase + h * kd * vd;')
(dest/'SlotTiledRecurrence.java').write_text(slot)

# Same Rust implementation, but force the sequential arm independently of token count.
rust = root / 'backend-native/src/main/rust/model-kernels'
for name, aligned in [('sequential', False), ('sequential-aligned', True), ('sequential-aligned-parallel', True), ('sequential-aligned-parallel-group-fixed', True)]:
    target = dest / name
    (target / 'src').mkdir(parents=True, exist_ok=True)
    shutil.copytree(rust / 'src', target / 'src', dirs_exist_ok=True)
    (target / 'Cargo.toml').write_text((rust / 'Cargo.toml').read_text())
    (target / 'Cargo.lock').write_text((rust / 'Cargo.lock').read_text())
    code = (rust / 'src/lib.rs').read_text()
    code = code.replace('const GATED_DELTA_NET_CHUNK_MINIMUM: usize = 8;',
                        'const GATED_DELTA_NET_CHUNK_MINIMUM: usize = usize::MAX;')
    if aligned:
        code = code.replace('1.0_f32 / (job.key_dimension as f32).sqrt()',
                            '(1.0_f64 / (job.key_dimension as f64).sqrt()) as f32')
        code = code.replace('1.0_f32 / (squared_norm + 1.0e-6_f32).sqrt()',
                            '(1.0_f64 / ((squared_norm + 1.0e-6_f32) as f64).sqrt()) as f32')
        code = code.replace('log_decay[token_head].exp()',
                            '(log_decay[token_head] as f64).exp() as f32')
        code = code.replace('let decay = log_decay.exp();',
                            'let decay = (log_decay as f64).exp() as f32;')
    if '-parallel' in name:
        guard=' || (job.token_count == 1 && job.branch_count == 1)'
        assert code.count(guard)==1
        code=code.replace(guard,'')
    if name.endswith('-group-fixed'):
        guard='    if branches > 1 {'
        assert code.count(guard)==1
        code=code.replace(guard,'    if branches > 1 || job.state_index != 0 {')
        # The grouped step has its own inline float exp; prefix-only alignment did not change it.
        inline='gated_delta_net_scale(head_state, log_decay.exp(), vectorized);'
        assert code.count(inline)==1
        code=code.replace(inline,'gated_delta_net_scale(head_state, (log_decay as f64).exp() as f32, vectorized);')
    (target / 'src/lib.rs').write_text(code)
