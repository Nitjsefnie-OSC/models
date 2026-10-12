"""Bound two real framework fixtures and retain failures as structured results."""
from pathlib import Path
import subprocess,json,time,os
root=Path('/work');out=root/'out';out.mkdir(exist_ok=True);cp=json.loads((root/'classpaths.json').read_text());results=[]
for module,cls in [('models-langchain4j','com.integrallis.models.langchain4j.Qwen3ActivatedLangChain4jToolCallingIntegrationTest'),('models-spring-ai','com.integrallis.models.spring.ai.Qwen3ActivatedSpringAiToolCallingIntegrationTest')]:
 cmd=['/opt/jdk/bin/java','--add-modules=jdk.incubator.vector','--enable-native-access=ALL-UNNAMED','-Xmx4g','-Dmodels.fixtures.qwen317b=/work/model.gguf','-Dmodels.fixtures.activatedLoraDirectory=/work/adapter','-Dorg.slf4j.simpleLogger.defaultLogLevel=info','-Dorg.slf4j.simpleLogger.log.dev.langchain4j.service.tool=debug','-cp',':'.join(cp[module]),'RunFixture',cls]
 (out/(module+'-command.json')).write_text(json.dumps(cmd,indent=2)+'\n');start=time.time()
 with (out/(module+'.log')).open('w') as log:
  try:code=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=900).returncode
  except subprocess.TimeoutExpired:code=124
 results.append({'module':module,'exitCode':code,'elapsedSeconds':time.time()-start});(out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
 subprocess.run(['aws','s3','cp',str(out),os.environ['RESULT_URI'],'--recursive','--only-show-errors'],check=True)
(out/'STATUS').write_text('COMPLETE_PASS\n' if all(x['exitCode']==0 for x in results) else 'COMPLETE_WITH_FAILED_FIXTURES\n')
