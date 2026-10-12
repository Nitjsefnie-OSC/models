#!/usr/bin/env python3
"""Check native inventory and proof provenance; this cannot establish causal proof itself."""
import argparse
import hashlib
import json
from pathlib import Path
import re

CAPABILITIES = 'backend-native/src/main/java/com/integrallis/models/backend/nativekernel/NativeKernelCapability.java'
RUST = 'backend-native/src/main/rust/model-kernels/src'
CUDA = 'backend-cuda/src/main/rust/models-cuda-kernels/src'
STATES = {'UNPROVEN', 'INACTIVE_EXPERIMENT', 'PROVEN_REQUIRED', 'JAVA_REPLACEMENT_READY', 'RETIRED'}
GATES = {'correctness', 'javaInvestigation', 'jvmCause', 'necessity', 'retirement'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def local_file(root, name):
    path = (root / name).resolve()
    require(path.is_relative_to(root.resolve()) and path.is_file(), 'Missing or external file: ' + name)
    return path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root):
    text = local_file(root, CAPABILITIES).read_text()
    capabilities = {name: 1 << int(bit or 0) for name, bit in re.findall(
        r'\b([A-Z][A-Z0-9_]+)\(1L(?:\s*<<\s*(\d+))?\)', text)}
    rust = '\n'.join(p.read_text() for p in sorted((root / RUST).rglob('*.rs')))
    rust_capabilities = {name: 1 << int(bit or 0) for name, bit in re.findall(
        r'const CAPABILITY_([A-Z0-9_]+): u64 = 1(?:\s*<<\s*(\d+))?;', rust)}
    require(capabilities == rust_capabilities, 'Java/Rust capability masks differ')
    exports = {}
    for target, folder, abi in [('cpu', RUST, 'C'), ('cuda', CUDA, 'ptx-kernel')]:
        names = []
        for path in sorted((root / folder).rglob('*.rs')):
            names.extend(re.findall(r'pub\s+(?:unsafe\s+)?extern\s+"' + abi + r'"\s+fn\s+(\w+)', path.read_text()))
        require(len(names) == len(set(names)), 'Duplicate ' + target + ' exported symbol')
        exports[target] = sorted(names)
    require(capabilities and all(exports.values()), 'Empty capability/export inventory')
    return capabilities, exports


def verify_reference(root, reference):
    path = local_file(root, reference['path'])
    require(sha(path) == reference['sha256'], 'Stale evidence hash: ' + reference['path'])
    return path


def verify(root, registry):
    require(registry['schemaVersion'] == 1, 'Unsupported registry schema')
    capabilities, exports = inventory(root)
    require(registry['capabilities'] == capabilities, 'Unregistered or stale capability')
    require(registry['exports'] == exports, 'Unregistered or stale exported symbol')
    operations = registry['operations']
    ids = [operation['id'] for operation in operations]
    require(len(ids) == len(set(ids)) and ids, 'Duplicate or empty operation IDs')
    migration = set(registry['migrationChecklist'])
    require(migration <= set(ids), 'Unknown operation in migration checklist')
    covered_caps = []
    covered_exports = {'cpu': set(), 'cuda': set()}
    unresolved = []
    for operation in operations:
        name = operation['id']
        status = operation['status']
        require(status in STATES, 'Invalid status: ' + name)
        require(operation['summary'].strip(), 'Missing explanation: ' + name)
        for cap in operation['capabilities']:
            require(cap in capabilities, 'Unknown capability: ' + cap)
            covered_caps.append(cap)
        for target, symbols in operation['exports'].items():
            require(target in exports and set(symbols) <= set(exports[target]), 'Unknown export: ' + name)
            covered_exports[target].update(symbols)
        require(status == 'RETIRED' or operation['dispatch'], 'Missing dispatch coverage: ' + name)
        source_inputs = registry['sourceScopes'][operation['sourceScope']]
        require(source_inputs, 'Missing source/dependency scope: ' + name)
        for source in source_inputs:
            local_file(root, source)
        for dispatch in operation['dispatch']:
            source = local_file(root, dispatch['path']).read_text()
            require(dispatch['anchor'] in source, 'Stale dispatch anchor: ' + name)
        for reference in operation['evidence']:
            verify_reference(root, reference)
        if status in {'UNPROVEN', 'INACTIVE_EXPERIMENT'}:
            require(name in migration, 'New unproven operation is not permitted: ' + name)
            require(operation['remaining'], 'Missing finite migration work: ' + name)
            unresolved.append(name)
        elif status == 'PROVEN_REQUIRED':
            require('proof' in operation, 'Missing proof: ' + name)
            proof = json.loads(verify_reference(root, operation['proof']).read_text())
            require(set(proof['gates']) == GATES and all(value == 'PASS' for value in proof['gates'].values()), 'Incomplete proof gates: ' + name)
            require(proof['sources'], 'Missing dependency fingerprints: ' + name)
            require({row['path'] for row in proof['sources']} == set(source_inputs), 'Incomplete dependency fingerprints: ' + name)
            for reference in proof['sources']:
                verify_reference(root, reference)
            require(proof['profiles'], 'Missing supported profiles: ' + name)
            require({profile['host'] for profile in proof['profiles']} == set(operation['requiredProfiles']), 'Missing or unsupported host profile: ' + name)
            for profile in proof['profiles']:
                require(profile['host'] and profile['jvm'], 'Missing runtime identity: ' + name)
                require(profile['javaCalls'] > 0 and profile['nativeCalls'] > 0, 'Unexecuted comparison arm: ' + name)
                require(len(set(profile['datasets'])) >= 2, 'Missing independent datasets: ' + name)
                require(profile['decision'] == 'PASS', 'Failed profile: ' + name)
                require(profile['rawEvidence'], 'Missing raw evidence: ' + name)
                for reference in profile['rawEvidence']:
                    verify_reference(root, reference)
            for field in ['jvmLimitation', 'controlledExperiment', 'workloadRequirement', 'retirementCommand', 'review']:
                require(proof[field].strip(), 'Missing proof explanation ' + field + ': ' + name)
        elif status == 'RETIRED':
            require(not operation['capabilities'] and not any(operation['exports'].values()), 'Retired operation still exported: ' + name)
        else:
            require(operation.get('replacementEvidence'), 'Missing replacement evidence: ' + name)
            verify_reference(root, operation['replacementEvidence'])
            unresolved.append(name)
    require(len(covered_caps) == len(set(covered_caps)), 'Capability belongs to multiple operations')
    require(set(covered_caps) == set(capabilities), 'Capability lacks an operation')
    require(covered_exports == {target: set(names) for target, names in exports.items()}, 'Export lacks an operation')
    return {'operations': len(operations), 'capabilities': len(capabilities), 'exports': {k: len(v) for k, v in exports.items()}, 'unresolved': unresolved}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--require-complete', action='store_true')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    registry = json.loads((args.root / 'docs/native-shims/registry.json').read_text())
    result = verify(args.root, registry)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    require(not args.require_complete or not result['unresolved'], 'Shim proof campaign remains incomplete')


if __name__ == '__main__':
    main()
