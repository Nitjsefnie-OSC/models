package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.models.backend.nativekernel.RustGgufBatchedMatrixKernel;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.purejava.gguf.GgufParser;
import com.integrallis.models.backend.purejava.spi.GgufBatchedMatrixKernel;
import java.lang.foreign.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Diagnostic only: tensor and logit capture from actual immutable production artifacts. */
public final class OracleLayerCapture {
  static Path folder;
  static int sequence;
  static boolean capture;
  static final Map<String,String> names=new HashMap<>();
  static String key(MemorySegment s)throws Exception {
    return s.byteSize()+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.asSlice(0,Math.min(256,s.byteSize())).toArray(ValueLayout.JAVA_BYTE)));
  }
  static void tensor(String name,float[] data,int length,Map<String,Object> info)throws Exception {
    String file=String.format("%04d-%s.f32",sequence++,name.replaceAll("[^A-Za-z0-9_.-]","_"));
    var bytes=ByteBuffer.allocate(length*4).order(ByteOrder.LITTLE_ENDIAN);
    for(int i=0;i<length;i++)bytes.putFloat(data[i]);
    Files.write(folder.resolve(file),bytes.array());
    var record=new LinkedHashMap<String,Object>(info);record.put("name",name);record.put("file",file);record.put("elements",length);
    Files.writeString(folder.resolve("tensors.jsonl"),ProductionRouteProof.JSON.writeValueAsString(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
  }
  static void projection(float[] output,MemorySegment weights,float[] input,int batch,int rows,int cols)throws Exception {
    String name=names.get(key(weights));
    if(name==null)return;
    if(!name.startsWith("blk."))return;
    Map<String,Object> info=Map.of("batch",batch,"rows",rows,"cols",cols,"weight",name);
    if(name.endsWith("attn_qkv.weight") || name.endsWith("attn_q.weight") || name.endsWith("ffn_gate.weight"))
      tensor(name+".input",input,batch*cols,info);
    if(name.endsWith("ssm_out.weight") || name.endsWith("attn_output.weight") || name.endsWith("ffn_down.weight"))
      tensor(name+".output",output,batch*rows,info);
  }
  static GgufBatchedMatrixKernel observe(GgufBatchedMatrixKernel provider) {
    return (GgufBatchedMatrixKernel)Proxy.newProxyInstance(OracleLayerCapture.class.getClassLoader(),new Class<?>[]{GgufBatchedMatrixKernel.class},(proxy,m,a)->{
      Object result;try{result=m.invoke(provider,a);}catch(InvocationTargetException e){throw e.getCause();}
      if(!capture)return result;
      switch(m.getName()) {
        case "multiply" -> projection((float[])a[0],(MemorySegment)a[2],(float[])a[1],(int)a[4],(int)a[5],(int)a[6]);
        case "multiplyDual", "multiplyTriple" -> {
          int count=m.getName().equals("multiplyDual")?2:3;
          for(int i=0;i<count;i++)projection((float[])a[i*4],(MemorySegment)a[i*4+1],(float[])a[count*4],(int)a[count*4+1],(int)a[i*4+3],(int)a[count*4+2]);
        }
        case "multiplyGrouped" -> {
          for(int i=0;i<(int)a[4];i++)projection(((float[][])a[0])[i],((MemorySegment[])a[1])[i],(float[])a[5],(int)a[6],((int[])a[3])[i],(int)a[7]);
        }
        default -> {}
      }
      return result;
    });
  }
  public static void main(String[] args)throws Exception {
    Path out=Path.of(args[3]);Files.createDirectories(out);
    try(var arena=Arena.ofShared()) {
      var gguf=GgufParser.parse(Path.of(args[0]),arena);
      for(var info:gguf.tensorInfos()) {
        if(info.nDimensions()!=2 || !info.name().startsWith("blk."))continue;
        var weights=gguf.getTensor(info.name()).dataSegment();
        String existing=names.put(key(weights),info.name());
        if(existing!=null)throw new AssertionError("ambiguous weight fingerprint "+existing+" / "+info.name());
      }
    }
    var tasks=ProductionRouteProof.JSON.readTree(Path.of(args[2]).toFile());
    try(var provider=RustGgufBatchedMatrixKernel.open(Path.of(args[1]));var backend=PureJavaBackend.load(Path.of(args[0]),ProductionRouteProof.WORKLOAD,observe(provider))) {
      for(var task:tasks) {
        String id=task.path("id").asText();folder=out.resolve(id);Files.createDirectories(folder);sequence=0;
        backend.reset();int[] input=backend.tokenizer().encode(task.path("prompt").asText());
        capture=id.equals("easy-intent-00");float[] logits=backend.prefill(input,0);capture=false;
        var tokens=new ArrayList<Integer>();
        for(int step=0;step<8;step++) {
          tensor("logits-"+step,logits,logits.length,Map.of("step",step));
          int token=ProductionRouteProof.argmax(logits);tokens.add(token);
          if(step<7)logits=backend.forward(token,input.length+step);
        }
        var record=Map.of("id",id,"inputTokens",input,"tokens",tokens);
        Files.writeString(folder.resolve("result.json"),ProductionRouteProof.JSON.writeValueAsString(record)+"\n");ProductionRouteProof.emit(record);
      }
    }
  }
}
