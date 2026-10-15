"""Freeze externally authored QA windows before any model outputs are observed."""
import hashlib
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parent
cache = Path.home() / '.cache/models-onboarding/public-qa'
cache.mkdir(parents=True, exist_ok=True)
sources = {
    'squad2': ('https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v2.0.json', 'CC-BY-SA-4.0'),
    'boolq': ('https://huggingface.co/datasets/google/boolq/resolve/35b264d03638db9f4ce671b711558bf7ff0f80d5/data/validation-00000-of-00001.parquet', 'CC-BY-SA-3.0'),
}
expected_hashes = {
    'squad2': '80a5225e94905956a6446d296ca1093975c4d3b3260f1d6c8f68bc2ab77182d8',
    'boolq': '52355d11524b4b874a9b9dcc278feb10f672d52c4f4eff9872e695ede59820f8',
}
seed = 'bonsai-production-public-qa-v1'
manifest = {'protocol': seed, 'perDataset': 100, 'selection': 'first 50 per label after SHA-256(seed + dataset + source ID) sort', 'sources': {}, 'cases': []}
for dataset, (url, license_id) in sources.items():
    path = cache / (dataset + ('.parquet' if dataset == 'boolq' else '.json'))
    if not path.exists():
        with urllib.request.urlopen(url, timeout=120) as response:
            path.write_bytes(response.read())
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_hashes[dataset]:
        raise ValueError('source SHA-256 differs: ' + dataset)
    if dataset == 'squad2':
        rows = [dict(id=q['id'], question=q['question'], passage=p['context'], title=a['title'],
                     label='unanswerable' if q['is_impossible'] else 'answerable',
                     answers=[answer['text'] for answer in q['answers']] or [''])
                for a in json.loads(raw)['data'] for p in a['paragraphs'] for q in p['qas']]
    else:
        import pyarrow as pa
        import pyarrow.parquet as pq
        data = pq.read_table(pa.BufferReader(raw)).to_pylist()
        rows = [dict(id=str(i), question=q['question'], passage=q['passage'], title=q.get('title', ''),
                     label='yes' if q['answer'] else 'no', answers=['yes' if q['answer'] else 'no'])
                for i, q in enumerate(data)]
    manifest['sources'][dataset] = {'url': url, 'sha256': hashlib.sha256(raw).hexdigest(),
        'bytes': len(raw), 'license': license_id, 'sourceRows': len(rows),
        'attribution': 'Rajpurkar, Jia and Liang (2018), SQuAD 2.0' if dataset == 'squad2' else 'Clark et al. (2019), BoolQ, Google Research'}
    for label in sorted({row['label'] for row in rows}):
        selected = sorted((row for row in rows if row['label'] == label),
            key=lambda row: hashlib.sha256((seed + dataset + row['id']).encode()).hexdigest())[:50]
        for row in selected:
            row['dataset'] = dataset
            if dataset == 'squad2':
                instruction = 'Answer the question using only the passage. Output the shortest exact answer span from the passage. If the passage does not answer the question, output UNANSWERABLE. Do not explain.'
                row['maxTokens'] = 64
            else:
                instruction = 'Answer the question using the passage. Output only yes or no. Do not explain.'
                row['maxTokens'] = 8
            row['prompt'] = '<|im_start|>system\n' + instruction + '<|im_end|>\n<|im_start|>user\nPassage:\n' + row['passage'] + '\n\nQuestion: ' + row['question'] + '<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n'
            row['promptSha256'] = hashlib.sha256(row['prompt'].encode()).hexdigest()
            manifest['cases'].append(row)
manifest['cases'].sort(key=lambda row: hashlib.sha256((seed + row['dataset'] + row['id']).encode()).hexdigest())
output = root / 'public-qa-cases.json'
output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'file': str(output), 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'cases': len(manifest['cases']), 'sources': manifest['sources']}, indent=2))
