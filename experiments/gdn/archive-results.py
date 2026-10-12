"""Compress immutable large diagnostics without changing their raw bytes or S3 originals."""
import gzip,hashlib,json,pathlib
root=pathlib.Path(__file__).resolve().parents[2]
out=root/'benchmark-results/2026-10-11-gdn-proof'
archives=[]
for receipt in sorted(out.glob('hosts*.json')):
    for host in json.loads(receipt.read_text())['hosts']:
        if not (out/'termination-receipts'/f"{host['instanceId']}.json").exists():continue
        phase=host['resultUri'].split('/results/')[1].split('/')[0]
        folder={'gdn-proof-v1':'remote','gdn-proof-jmh-v1':'remote-jmh','gdn-proof-trace-v1':'remote-trace'}.get(phase,'remote-'+phase.removeprefix('gdn-proof-'))
        hostDir=out/folder/host['arch']
        for path in sorted(hostDir.rglob('*')):
            if path.suffix not in ('.xml','.bin'):continue
            raw=path.read_bytes();target=path.with_suffix(path.suffix+'.gz')
            compressed=gzip.compress(raw,mtime=0)
            if target.exists():assert gzip.decompress(target.read_bytes())==raw,path
            else:target.write_bytes(compressed)
            archives.append({'original':str(path.relative_to(out)),'archive':str(target.relative_to(out)),
                'originalS3Uri':host['resultUri']+str(path.relative_to(hostDir)),
                'rawSha256':hashlib.sha256(raw).hexdigest(),
                'archiveSha256':hashlib.sha256(target.read_bytes()).hexdigest(),
                'rawBytes':len(raw),'archiveBytes':target.stat().st_size})
(out/'large-artifact-manifest.json').write_text(json.dumps(archives,indent=2)+'\n')
print(len(archives),'archived files; raw bytes verified, S3 originals retained')
