"""Compare actual runtime class bytes, ignoring packaging versions and JAR timestamps."""
import argparse,hashlib,json,pathlib,zipfile
p=argparse.ArgumentParser();p.add_argument('--reference',required=True,type=pathlib.Path);p.add_argument('--candidate-root',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);a=p.parse_args()
modules=['models-api','models-runtime','backend-java','backend-native']
def inventory(jars):
    classes={};artifacts=[]
    for jar in sorted(jars):
        artifacts.append({'path':str(jar),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()})
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.startswith('com/integrallis/models/') and name.endswith('.class'):
                    assert name not in classes,(name,jar)
                    classes[name]=hashlib.sha256(z.read(name)).hexdigest()
    return {'artifacts':artifacts,'classes':classes}
reference=inventory([j for j in a.reference.glob('*.jar') if any(j.name.startswith(m+'-') for m in modules)])
jars=[]
for module in modules:
    found=[j for j in (a.candidate_root/module/'build/libs').glob('*.jar') if j.name.startswith(module+'-') and not any(s in j.name for s in ['-sources','-javadoc','-linux-','-macos-','-windows-'])]
    assert len(found)==1,(module,found);jars.extend(found)
candidate=inventory(jars)
left=reference['classes'];right=candidate['classes']
report={'reference':reference,'candidate':candidate,'added':sorted(right.keys()-left.keys()),'removed':sorted(left.keys()-right.keys()),'changed':sorted(k for k in left.keys()&right.keys() if left[k]!=right[k])}
a.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:report[k] for k in ['added','removed','changed']},indent=2))
