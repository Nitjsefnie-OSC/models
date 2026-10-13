import com.integrallis.models.backend.purejava.ops.PinnedReduction;
import com.integrallis.models.backend.purejava.quant.Q5_1Dequantizer;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import jdk.incubator.vector.FloatVector;

/** Fixed synthetic public-kernel diagnosis; model qualification is a separate gate. */
public final class WidthBench {
  static volatile int sink;
  static final com.sun.management.ThreadMXBean ALLOC = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
  static final Q5_1Dequantizer DECODER = new Q5_1Dequantizer();
  static volatile float[] observed;
  static float invoke(String kernel,float[] a,float[] b,MemorySegment packed,int repeat) {
    if(kernel.equals("dot")) {
      a[repeat%a.length] += 0.000001f;
      return PinnedReduction.dot(a,0,b,0,a.length);
    }
    DECODER.dequantize(packed,0,a,0,a.length);
    return a[repeat%a.length];
  }
  public static void main(String[] args)throws Exception {
    ALLOC.setThreadAllocatedMemoryEnabled(true);
    String kernel=args[0];long thread=Thread.currentThread().threadId();
    System.out.println("{\"kind\":\"host\",\"preferredBits\":"+FloatVector.SPECIES_PREFERRED.vectorBitSize()+",\"kernel\":\""+kernel+"\"}");
    int shapeIndex=0;
    for(int size:new int[]{896,1536,4096,4864}) {
      int calls=args.length>1 ? Integer.parseInt(args[1].split(",")[shapeIndex++]) : 1000;
      Random random=new Random(80217+size);float[] a=new float[size],b=new float[size];observed=a;
      for(int n=0;n<size;n++){a[n]=random.nextFloat()-0.5f;b[n]=random.nextFloat()-0.5f;}
      byte[] bytes=new byte[size/32*24];random.nextBytes(bytes);
      ByteBuffer packed=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      for(int block=0;block<size/32;block++){packed.putShort(block*24,(short)(0x3000|(block*29&1023)));packed.putShort(block*24+2,(short)(0xb000|(block*37&1023)));}
      MemorySegment memory=MemorySegment.ofArray(bytes);
      long allocated=ALLOC.getThreadAllocatedBytes(thread),start=System.nanoTime();
      sink=Float.floatToRawIntBits(invoke(kernel,a,b,memory,0));
      long cold=System.nanoTime()-start,coldBytes=ALLOC.getThreadAllocatedBytes(thread)-allocated;
      System.out.println("{\"kind\":\"cold\",\"size\":"+size+",\"nanos\":"+cold+",\"bytes\":"+coldBytes+"}");
      for(int n=0;n<20000;n++)sink=Float.floatToRawIntBits(invoke(kernel,a,b,memory,n));
      for(int block=0;block<5;block++) {
        allocated=ALLOC.getThreadAllocatedBytes(thread);start=System.nanoTime();
        for(int n=0;n<calls;n++)sink=Float.floatToRawIntBits(invoke(kernel,a,b,memory,n));
        long elapsed=System.nanoTime()-start,count=ALLOC.getThreadAllocatedBytes(thread)-allocated;
        System.out.println("{\"kind\":\"measure\",\"size\":"+size+",\"block\":"+block+",\"calls\":"+calls+",\"nanos\":"+elapsed+",\"bytes\":"+count+",\"checksum\":"+sink+"}");
      }
      ByteBuffer output=ByteBuffer.allocate(size*4).order(ByteOrder.LITTLE_ENDIAN);for(float x:a)output.putInt(Float.floatToRawIntBits(x));
      String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.array()));
      System.out.println("{\"kind\":\"output\",\"size\":"+size+",\"sha256\":\""+hash+"\",\"checksum\":"+sink+"}");
    }
  }
}
