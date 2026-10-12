"""Create a hashed evidence-only snapshot without copying or changing runtime source."""
import datetime,hashlib,json,pathlib,shutil,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[2];results=root/'benchmark-results/2026-10-11-gdn-proof';destination=pathlib.Path(sys.argv[1]).resolve();destination.mkdir(exist_ok=False)
selected=set()
for name in subprocess.check_output(['git','ls-files','experiments/gdn'],cwd=root,text=True).splitlines():
 p=root/name
 if p.is_file() and '/generated/' not in name:selected.add(p)
for pattern in ['ORACLE-DIAGNOSIS.md','QUALITY-FACTORIAL.md','oracle-contract-classification-source.json','*production-final-v9*.json','*production-final-v11*.json','*-v9-replacement-stop.json','*quality-factorial-v1*.json','*ffm-array-adapter-v1*.json']:
 selected.update(p for p in results.glob(pattern)if p.is_file())
for name in ['remote-quality-factorial-v1','remote-ffm-array-adapter-v1','remote-production-final-v9','remote-production-final-v11','oracle-arm-q8-scalar-replay-v2','oracle-arm-no-repack-scalar-replay','oracle-arm-q8k-fma-replay-v2','oracle-x86-ffn-input-delta-replay']:
 for p in (results/name).rglob('*'):
  if p.is_file() and p.suffix not in ['.xml','.jfr'] and p.stat().st_size<20_000_000:selected.add(p)
manifest={'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceRoot':str(root),'sourceHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
 'scope':'Evidence only. No production runtime sources or model weights. Active v11 captures are a timestamped snapshot, not a completion claim. Prior failed assertions and incomplete timing strata remain preserved.',
 'retainedOutsideExport':'Bulk tensor captures and complete remote S3 prefixes remain in proof branch/worktree and the immutable host receipts; XML compilation diagnostics and JFR are not duplicated here.',
 'files':{}}
for p in sorted(selected):
 relative=p.relative_to(root);target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
 manifest['files'][str(relative)]={'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'bytes':target.stat().st_size}
(destination/'EVIDENCE-EXPORT-MANIFEST.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'destination':str(destination),'sourceHead':manifest['sourceHead'],'files':len(manifest['files']),'bytes':sum(f['bytes']for f in manifest['files'].values())},indent=2))
