import com.integrallis.models.backend.purejava.gguf.GgufParser;
import com.integrallis.models.backend.purejava.qwen35.Qwen35ForwardPass;
import com.integrallis.models.backend.purejava.tokenizer.GgufTokenizer;
import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import java.lang.foreign.Arena;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
class BonsaiBatchStateProbe {
 static Object field(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static double diff(float[] a,float[] b){double d=0;for(int i=0;i<a.length;i++)d=Math.max(d,Math.abs((double)a[i]-b[i]));return d;}
 public static void main(String[] args)throws Exception{
  try(var arena=Arena.ofShared();var kernel=RustGgufBatchedMatrixKernel.open(Path.of(args[1]),4)){
   var file=GgufParser.parse(Path.of(args[0]),arena);var tokenizer=GgufTokenizer.fromMetadata(file.metadata());
   int[] tokens=tokenizer.encode("Explain why independent requests must not share recurrent state. A second request should start with its own history and produce the same answer when repeated.");
   tokens=Arrays.copyOf(tokens,Integer.parseInt(args[2]));System.out.println("tokens="+Arrays.toString(tokens));
   var base=Qwen35ForwardPass.fromGgufFile(file,kernel);Object baseline=null;float[] first=null;
   for(int size:new int[]{1,16,1}){var graph=base.withPrefillBatchSize(size);var session=graph.openSession(96);float[] logits=graph.prefill(session,tokens,0);Object state=field(session,"state");
    if(baseline==null){baseline=state;first=logits;continue;}
    System.out.println("batch="+size+" logitDelta="+diff(first,logits));
    for(String name:new String[]{"convolutionHistory","recurrentState","keys","values"}){
     var a=(float[][])field(baseline,name);var b=(float[][])field(state,name);
     for(int layer=0;layer<a.length;layer++)if(a[layer]!=null){double delta=diff(a[layer],b[layer]);if(delta!=0)System.out.println(name+" layer="+layer+" max="+delta);}
    }
   }
  }
 }
}
