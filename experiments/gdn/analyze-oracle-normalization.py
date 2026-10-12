"""Correct the ARM diagnostic's historical-reference label without rewriting any raw evidence."""
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]);result={}
for arch in ['x86','arm']:
    historical=json.loads((root/f'remote-production-correctness-v3/{arch}/qwen-0.8b-oracle/results.json').read_text())
    tokens={row['id']:row['arms']['baseline']['tokens'] for row in historical}
    original=json.loads((root/f'remote-oracle-normalization-v1/{arch}/comparison.json').read_text())
    assert len(original)==36 and len(tokens)==9
    rows=[{**row,'referenceArchitecture':arch,'modelsTokensSameArchitecture':tokens[row['id']],
           'matchesModelsSameArchitecture':row['oracleTokens']==tokens[row['id']]} for row in original]
    counts={f'{arm}-{batch}':sum(row['matchesModelsSameArchitecture'] for row in rows if row['arm']==arm and row['microbatch']==batch)
            for arm in ['baseline','additive-epsilon'] for batch in [512,1]}
    result[arch]={'exactCountsOfNine':counts,'comparisons':rows}
print(json.dumps({'scope':'Historical same-architecture Models comparison, not same-host paired qualification. The raw ARM diagnostic used x86 reference tokens and is retained unchanged.',
                  'architectures':result},indent=2))
