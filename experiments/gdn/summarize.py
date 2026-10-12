"""Summarize process-level evidence without converting incomplete runs into passes."""
import collections,json,math,pathlib,statistics
root=pathlib.Path(__file__).resolve().parents[2]
out=root/'benchmark-results/2026-10-11-gdn-proof'
summary={'jmh':{},'exclusiveKernels':{},'cohorts':{},'stability':{}}
expectedIds={r['id'] for r in json.loads((root/'experiments/gdn/data/tasks.json').read_text())}
def paired_interval(ratios):
    center=statistics.mean(ratios);error=4.302652729*statistics.stdev(ratios)/math.sqrt(3)
    return {'latencyRatio':math.exp(center),'paired95CI':[math.exp(center-error),math.exp(center+error)]}
for folder in sorted(out.glob('remote*')):
    for host in sorted(folder.iterdir()):
        if not host.is_dir():continue
        key=folder.name+'/'+host.name
        status=(host/'STATUS').read_text().strip() if (host/'STATUS').exists() else 'NO_STATUS'
        files=list(host.glob('jmh-*.json'))
        if files:
            means={}; incomplete=[]
            for file in files:
                repeat=int(file.name.split('-')[1])
                try: rows=json.loads(file.read_text())
                except json.JSONDecodeError:
                    incomplete.append(file.name)
                    continue
                if len(rows)!=5: incomplete.append(file.name)
                for row in rows:
                    means[(row['params']['arm'],int(row['params']['tokens']),repeat)]=row['primaryMetric']['score']
            report=[]
            for arm,tokens in sorted({(a,t) for a,t,_ in means}):
                samples=[means[(arm,tokens,r)] for r in range(3) if (arm,tokens,r) in means]
                ratios=[math.log(means[(arm,tokens,r)]/means[('java',tokens,r)]) for r in range(3)
                    if (arm,tokens,r) in means and ('java',tokens,r) in means]
                result={'arm':arm,'tokens':tokens,'forks':len(samples),'meanMicros':statistics.mean(samples)}
                if len(ratios)==3:
                    result.update(paired_interval(ratios))
                nativeRatios=[math.log(means[(arm,tokens,r)]/means[('native-aligned-parallel',tokens,r)]) for r in range(3)
                    if (arm,tokens,r) in means and ('native-aligned-parallel',tokens,r) in means]
                if len(nativeRatios)==3:result['vsMatchedNative']=paired_interval(nativeRatios)
                report.append(result)
            summary['jmh'][key]={'status':status,'measurements':report,'incompleteFiles':incomplete,
                'completeKernelMatrix':status=='COMPLETE' and not incomplete and len(files)==18 and len(means)==90,
                'scope':'kernel only; three process means required; no model qualification implied'}
        exclusiveFiles=list(host.glob('exclusive-*.json'))
        if exclusiveFiles:
            means={}; incomplete=[]
            for file in exclusiveFiles:
                _,repeat,mode,_=file.name.split('-',3)
                try:records=json.loads(file.read_text())
                except json.JSONDecodeError:
                    incomplete.append(file.name);continue
                if len(records)!=(5 if mode=='prefix' else 4):incomplete.append(file.name)
                for row in records:
                    shape=int(row['params']['tokens' if mode=='prefix' else 'branches'])
                    means[(mode,row['params']['arm'],shape,int(repeat))]=row['primaryMetric']['score']
            report=[]
            for mode,arm,shape in sorted({(m,a,s) for m,a,s,_ in means}):
                samples=[means[(mode,arm,shape,r)] for r in range(3) if (mode,arm,shape,r) in means]
                result={'mode':mode,'arm':arm,'shape':shape,'forks':len(samples),'meanMicros':statistics.mean(samples)}
                for baseline in ('java','native-aligned-parallel'):
                    ratios=[math.log(means[(mode,arm,shape,r)]/means[(mode,baseline,shape,r)])
                        for r in range(3) if (mode,arm,shape,r) in means and (mode,baseline,shape,r) in means]
                    if len(ratios)==3:result['vs-'+baseline]=paired_interval(ratios)
                report.append(result)
            summary['exclusiveKernels'][key]={'status':status,'measurements':report,'incompleteFiles':incomplete,
                'completeKernelMatrix':status=='COMPLETE' and not incomplete and len(exclusiveFiles)==18 and len(means)==81,
                'scope':'corrected native ranges; isolated kernels with existing Java executor; no model qualification implied'}
        for file in [host/'cohort.stdout',host/'trace.stdout']:
            if not file.exists():continue
            rows=[];other=[]
            for line in file.read_text().splitlines():
                try:r=json.loads(line)
                except json.JSONDecodeError:continue
                if r.get('kind')=='decision':rows.append(r)
                else:other.append(r)
            arms={}
            for arm in sorted({r['arm'] for r in rows}):
                measured=[r for r in rows if r['arm']==arm and r['repeat']==0]
                arms[arm]={'cases':len(measured),'nonidenticalLogits':[r['id'] for r in measured if not r['difference'].startswith('0:')],
                    'correctLabels':sum(r['prediction']==r['expected'] for r in measured),
                    'datasets':dict(collections.Counter(r['dataset'] for r in measured)),
                    'completeExactPublicCohort':len(measured)==148 and {r['id'] for r in measured}==expectedIds
                        and all(r['difference'].startswith('0:') and r['calls']>0 for r in measured)}
                timed=[r for r in rows if r['arm']==arm and r['repeat']>0]
                arms[arm]['timingObservations']=len(timed)
                arms[arm]['completeTimingRounds']=all(sum(r['repeat']==repeat for r in timed)==26 for repeat in range(1,6))
                arms[arm]['timedNonidenticalLogits']=[r['id'] for r in timed if not r['difference'].startswith('0:')]
                if timed:
                    arms[arm]['diagnosticMedianDecisionSeconds']=statistics.median(r['nanos'] for r in timed)/1e9
                    arms[arm]['diagnosticMedianRecurrenceMillis']=statistics.median(r['recurrenceNanos'] for r in timed)/1e6
            sharedControl=folder.name in ('remote-cohort-correctness-v6','remote-cohort-timing-v6')
            summary['cohorts'][key]={'status':status,'observations':len(rows),'arms':arms,'diagnostics':other,
                'performanceScope':('Diagnostic shared-context control only; instrumented activation baseline; native ownership correction and production qualification pending'
                    if sharedControl else 'INVALID for native whole-model performance decisions: separate projection and recurrence native contexts compete for cores'),
                'productionPerformanceQualified':False}
        stable={}
        for file in sorted(host.glob('reference*.stdout')):
            observations=[];changes=[]
            for line in file.read_text().splitlines():
                try:row=json.loads(line)
                except json.JSONDecodeError:continue
                if row.get('kind')=='reference-stability':observations.append(row)
                elif row.get('kind')=='first-input-change':changes.append(row)
            stable[file.name]={'observations':len(observations),
                'changedAgainstFirst':sum(not row['againstFirst'].startswith('0:') for row in observations),
                'changedAgainstPrevious':sum(not row['againstPrevious'].startswith('0:') for row in observations),
                'firstInputChanges':changes}
        if stable:summary['stability'][key]={'status':status,'runs':stable}
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
for key,value in summary['jmh'].items():
    print(key,value['status'])
    for r in value['measurements']:
        if r['tokens'] in (1,32):print(r)
for key,value in summary['cohorts'].items():print(key,value['status'],value['observations'],value['diagnostics'])
