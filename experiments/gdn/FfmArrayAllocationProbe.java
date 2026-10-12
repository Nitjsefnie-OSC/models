package com.integrallis.models.backend.nativekernel;
import java.nio.*;import java.nio.file.*;import java.security.*;import java.util.*;
public final class FfmArrayAllocationProbe {
 static final com.sun.management.ThreadMXBean ALLOC=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
 static String hash(float[] a)throws Exception {var b=ByteBuffer.allocate(a.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float f:a){if(!Float.isFinite(f))throw new AssertionError();b.putFloat(f);}return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.array()));}
 public static void main(String[]args)throws Exception {
  ALLOC.setThreadAllocatedMemoryEnabled(true);
  try(var lib=NativeKernelLibrary.open(Path.of(args[0]),1)) {
   for(int tokens:new int[]{1,32}) {
    int heads=16,dim=128,n=tokens*heads*dim;float[]q=new float[n],k=new float[n],v=new float[n],g=new float[tokens*heads],beta=new float[g.length],state=new float[heads*dim*dim],out=new float[n];
    var rng=new Random(716309);for(int i=0;i<n;i++){q[i]=rng.nextFloat()-.5f;k[i]=rng.nextFloat()-.5f;v[i]=rng.nextFloat()-.5f;}Arrays.fill(g,-.01f);Arrays.fill(beta,.05f);
    int warm=tokens==1?20000:2000,calls=tokens==1?2000:200;
    for(int i=0;i<warm;i++)lib.gatedDeltaNetF32(q,k,v,g,beta,state,out,tokens,heads,heads,dim,dim);
    for(int sample=0;sample<5;sample++) {
     long before=ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
     for(int i=0;i<calls;i++)lib.gatedDeltaNetF32(q,k,v,g,beta,state,out,tokens,heads,heads,dim,dim);
     long nanos=System.nanoTime()-start,bytes=ALLOC.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
     System.out.printf(Locale.ROOT,"{\"tokens\":%d,\"sample\":%d,\"calls\":%d,\"javaAllocatedBytes\":%d,\"nanos\":%d,\"stateSha256\":\"%s\",\"outputSha256\":\"%s\"}%n",tokens,sample,calls,bytes,nanos,hash(state),hash(out));
    }
   }
  }
 }
}
