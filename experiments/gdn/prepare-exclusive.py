"""Generate the exact sequential comparator with bounded mutable borrows."""
from pathlib import Path
import shutil, subprocess

root = Path(__file__).resolve().parents[2]
source = root / 'experiments/gdn/generated/sequential-aligned-parallel-group-fixed'
target = root / 'experiments/gdn/generated/sequential-aligned-parallel-exclusive'
target.mkdir(exist_ok=True)
shutil.copytree(source / 'src', target / 'src', dirs_exist_ok=True)
for name in ('Cargo.toml', 'Cargo.lock'):
    shutil.copyfile(source / name, target / name)
code = (source / 'src/lib.rs').read_text()
begin = code.index('unsafe fn execute_gated_delta_net_partition(')
end = code.index('/// Tokens folded into one associative scan step.', begin)
part = code[begin:end]
first = part.index('    let state_slots =')
last = part.index('    // SAFETY:', first)
part = part[:first] + part[last:]
for line in (
    '    let state = unsafe { slice::from_raw_parts_mut(job.state as *mut f32, state_elements) };\n',
    '    let output = unsafe { slice::from_raw_parts_mut(job.output as *mut f32, value_elements) };\n',
):
    assert part.count(line) == 1
    part = part.replace(line, '')
# This comparator deliberately uses the exact sequential arithmetic for every prefix length.
chunk = part.index('    if job.token_count >= GATED_DELTA_NET_CHUNK_MINIMUM {')
following = part.index('    for head in start_head..end_head {', chunk)
part = part[:chunk] + part[following:]
state_slice = '&mut state[state_offset..state_offset + job.key_dimension * job.value_dimension]'
assert part.count(state_slice) == 2
part = part.replace(state_slice, '''unsafe { slice::from_raw_parts_mut(
    (job.state as *mut f32).add(state_offset), job.key_dimension * job.value_dimension) }''')
output_slice = '&mut output[value_offset..value_offset + job.value_dimension]'
assert part.count(output_slice) == 2
part = part.replace(output_slice, '''unsafe { slice::from_raw_parts_mut(
    (job.output as *mut f32).add(value_offset), job.value_dimension) }''')
fill = '            output[value_offset..value_offset + job.value_dimension].fill(0.0);'
assert part.count(fill) == 1
part = part.replace(fill, '''            unsafe { slice::from_raw_parts_mut(
    (job.output as *mut f32).add(value_offset), job.value_dimension) }.fill(0.0);''')
code = code[:begin] + part + code[end:]
# Distinct rows must own distinct state slots before the job reaches parallel workers.
invalid = '            .any(|&slot| slot < 0 || slot as usize >= state_slot_count)'
assert code.count(invalid) == 1
code = code.replace(invalid, invalid + '\n            || slots.iter().enumerate().any(|(row, slot)| slots[..row].contains(slot))')
(target / 'src/lib.rs').write_text(code)
subprocess.run(['patch', str(target / 'src/lib.rs'),
    str(root / 'experiments/gdn/native-attention-exclusive-slices.patch')], check=True)
subprocess.run(['cargo', 'fmt', '--manifest-path', str(target / 'Cargo.toml')], check=True)
