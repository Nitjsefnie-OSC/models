"""Compare captured production recurrence calls with pinned, unmodified Transformers functions.

The adapter expands Q/K in GGUF tiled head order. Pinned llama.cpp a935fbffe's
conversion/qwen.py, _LinearAttentionVReorderBase, explicitly converts grouped HF V
heads to this order. This is a component comparison, not a whole-model quality gate.
"""
import ast
import hashlib
import json
import pathlib
import struct
import sys

import numpy as np
import torch

torch.set_num_threads(1)
source = pathlib.Path(__file__).parent / 'oracle-source/modeling_qwen3_5.py'
tree = ast.parse(source.read_text())
functions = []
for node in tree.body:
    if isinstance(node, ast.FunctionDef) and node.name in ('l2norm', 'torch_recurrent_gated_delta_rule'):
        node.decorator_list = []  # Use the source implementation, never a hub kernel substitution.
        functions.append(node)
assert len(functions) == 2
namespace = {'torch': torch}
exec(compile(ast.Module(body=functions, type_ignores=[]), str(source), 'exec'), namespace)


def read(path):
    data = path.read_bytes()
    dimensions = struct.unpack_from('>5i', data)
    offset = 20
    fields = []
    for _ in range(8):
        size, = struct.unpack_from('>i', data, offset)
        offset += 4
        fields.append(torch.from_numpy(np.frombuffer(data, dtype='>f4', count=size,
                                                     offset=offset).astype(np.float32)))
        offset += 4 * size
    assert offset == len(data)
    return dimensions, fields, hashlib.sha256(data).hexdigest()


def compare(actual, expected):
    actual = actual.reshape(expected.shape)
    difference = (actual - expected).abs()
    assert torch.isfinite(actual).all() and torch.isfinite(expected).all()
    return {'maxAbsoluteError': difference.max().item(),
            'rmsError': difference.square().mean().sqrt().item(),
            'referenceMaxAbsolute': expected.abs().max().item(),
            'elements': actual.numel(),
            'elementsExceedingExistingFixtureAbsoluteTolerance2eMinus6':
                (difference > 2e-6).sum().item()}


results = []
for folder in sys.argv[1:]:
    for path in sorted(pathlib.Path(folder).glob('*.bin')):
        (tokens, kh, vh, kd, vd), fields, sha = read(path)
        assert vh % kh == 0
        query, key, value, decay, beta, state, output, final_state = fields
        # repeat tiles the key-head dimension, matching the GGUF converter above.
        query = query.reshape(1, tokens, kh, kd).repeat(1, 1, vh // kh, 1)
        key = key.reshape(1, tokens, kh, kd).repeat(1, 1, vh // kh, 1)
        expected_output, expected_state = namespace['torch_recurrent_gated_delta_rule'](
            query, key, value.reshape(1, tokens, vh, vd), decay.reshape(1, tokens, vh),
            beta.reshape(1, tokens, vh), initial_state=state.reshape(1, vh, kd, vd),
            output_final_state=True, use_qk_l2norm_in_kernel=True)
        results.append({'file': str(path), 'sha256': sha,
                        'dimensions': [tokens, kh, vh, kd, vd],
                        'output': compare(output, expected_output),
                        'state': compare(final_state, expected_state)})
assert results
print(json.dumps({'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                  'torchVersion': torch.__version__, 'numpyVersion': np.__version__,
                  'scope': 'Measured component differences; not a whole-model qualification verdict',
                  'samples': results}, indent=2))
