"""Compare final independently cloned vectors with both final Java and prior native records."""
import json,pathlib,sys
final=pathlib.Path(sys.argv[1]);prior=pathlib.Path(sys.argv[2]);output=pathlib.Path(sys.argv[3]);reports=[];pending=[]
def rows(path):
    data=[json.loads(line)for line in path.read_text().splitlines()if line.startswith('{')]
    records={r['id']:r for r in data if r['kind']=='request'};assert len(records)==148,path
    assert all(len(r['fullLogitSha256'])==9 for r in records.values()),path
    return records
for shard,arch,model in [('arm-small','arm','qwen-0.8b'),('arm-large-a','arm','qwen-4b'),('x86-large-a','x86','qwen-4b')]:
    paths={'finalJava':final/shard/f'{model}-baseline-cohort.stdout','finalNative':final/shard/f'{model}-native-cohort.stdout','priorNative':prior/(arch+'-correctness')/f'{model}-native-cohort.stdout'}
    if not all(p.exists()for p in paths.values()):pending.append({'shard':shard,'reason':'missing cohort output'});continue
    executions=[final/shard/f'{model}-{a}-cohort.execution.json' for a in ['baseline','native']]
    executions.append(prior/(arch+'-correctness')/f'{model}-native-cohort.execution.json')
    if not all(p.exists()for p in executions):pending.append({'shard':shard,'reason':'cohort execution not complete'});continue
    assert all(json.loads(p.read_text())['exitCode']==0 for p in executions)
    data={k:rows(p)for k,p in paths.items()};reference=data['finalNative'];differences=[]
    for arm,records in data.items():
        assert records.keys()==reference.keys()
        for id,row in records.items():
            fields=[k for k in ['fullLogitSha256','inputTokens','greedyTokens','prediction','expected']if row[k]!=reference[id][k]]
            if fields:differences.append({'arm':arm,'id':id,'fields':fields})
    reports.append({'architecture':arch,'model':model,'requests':148,'vectorsPerComparison':1332,'differences':differences,'correctLabels':sum(r['prediction']==r['expected']for r in reference.values())})
output.write_text(json.dumps({'complete':not pending,'pending':pending,'results':reports},indent=2)+'\n')
assert not any(r['differences']for r in reports),'final allocation-only change altered model outputs'
print(output)
