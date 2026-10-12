package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.spi.GgufBatchedMatrixKernel;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** Diagnostic: retain actual prefix/decode recurrence inputs and output/state for a pinned oracle. */
public final class RecurrenceOracleCapture {
  record Capture(int tokens, int keyHeads, int valueHeads, int kd, int vd, float[][] fields) {}
  static String phase;
  static Capture first, last, decode;
  static GgufBatchedMatrixKernel capture(GgufBatchedMatrixKernel provider) {
    return (GgufBatchedMatrixKernel) Proxy.newProxyInstance(RecurrenceOracleCapture.class.getClassLoader(),
        new Class<?>[]{GgufBatchedMatrixKernel.class}, (proxy, method, a) -> {
          float[][] values = null;
          int t=0,kh=0,vh=0,kd=0,vd=0;
          if (method.getName().equals("gatedDeltaNet")) {
            t=(int)a[7];kh=(int)a[8];vh=(int)a[9];kd=(int)a[10];vd=(int)a[11];
            int[] sizes={t*kh*kd,t*kh*kd,t*vh*vd,t*vh,t*vh,vh*kd*vd};
            values=new float[8][];
            for(int n=0;n<6;n++) values[n]=Arrays.copyOf((float[])a[n],sizes[n]);
          }
          Object result;
          try { result=method.invoke(provider,a); } catch(InvocationTargetException e) { throw e.getCause(); }
          if(values!=null) {
            values[6]=Arrays.copyOf((float[])a[6],t*vh*vd);
            values[7]=Arrays.copyOf((float[])a[5],vh*kd*vd);
            var sample=new Capture(t,kh,vh,kd,vd,values);
            if(phase.equals("prefix")) {if(first==null)first=sample;last=sample;}
            else if(decode==null) decode=sample;
          }
          return result;
        });
  }
  static void write(Path folder,String name,Capture c)throws Exception {
    if(c==null)throw new AssertionError("missing recurrence sample "+name);
    try(var out=new DataOutputStream(Files.newOutputStream(folder.resolve(name+".bin")))) {
      out.writeInt(c.tokens);out.writeInt(c.keyHeads);out.writeInt(c.valueHeads);out.writeInt(c.kd);out.writeInt(c.vd);
      for(float[] field:c.fields){out.writeInt(field.length);for(float v:field)out.writeInt(Float.floatToRawIntBits(v));}
    }
  }
  public static void main(String[] args)throws Exception {
    Path out=Path.of(args[3]);Files.createDirectories(out);
    var tasks=ProductionRouteProof.JSON.readTree(Path.of(args[2]).toFile());
    try(var provider=RustGgufBatchedMatrixKernel.open(Path.of(args[1]));
        var backend=PureJavaBackend.load(Path.of(args[0]),ProductionRouteProof.WORKLOAD,capture(provider))) {
      for(var task:tasks) {
        String id=task.path("id").asText();
        if(!Set.of("easy-intent-00","snips-AddToPlaylist-1","original-routing-06-0").contains(id))continue;
        first=null;last=null;decode=null;backend.reset();
        int[] tokens=backend.tokenizer().encode(task.path("prompt").asText());
        phase="prefix";float[] logits=backend.prefill(tokens,0);
        phase="decode";backend.forward(ProductionRouteProof.argmax(logits),tokens.length);
        write(out,id+"-first",first);write(out,id+"-last",last);write(out,id+"-decode",decode);
        ProductionRouteProof.emit(Map.of("id",id,"prefixTokens",tokens.length,"samples",3));
      }
    }
  }
}
