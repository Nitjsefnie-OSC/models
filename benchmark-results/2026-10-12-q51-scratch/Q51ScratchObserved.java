import com.integrallis.models.backend.purejava.ops.TensorOps;
import com.integrallis.models.backend.purejava.gguf.GgufTensorType;
import com.integrallis.vectors.core.GgufQ4Kernel;
import java.lang.foreign.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
public class Q51ScratchObserved {
  private static volatile float checksum;
  public static void main(String[] args) {
    int columns=16384;
    var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    try(var arena=Arena.ofConfined()) {
      var weight=arena.allocate(columns/32*24);
      var leShort=ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
      var leInt=ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
      for(int block=0;block<columns/32;block++) {
        long offset=block*24L;
        weight.set(leShort,offset,(short)(0x3000+(block&255)));
        weight.set(leShort,offset+2,(short)(0xb400+(block&255)));
        weight.set(leInt,offset+4,block*0x9e3779b9);
        for(int lane=0;lane<16;lane++)weight.set(ValueLayout.JAVA_BYTE,offset+8+lane,(byte)(block*31+lane*17));
      }
      for(int batch:new int[]{1,2}) {
        float[] input=new float[columns*2],output=new float[2];
        for(int i=0;i<input.length;i++)input[i]=(i%31-15)*0.015625f;
        for(int i=0;i<4096;i++) project(batch,output,input,weight,columns,i);
        long id=Thread.currentThread().threadId(), before=bean.getThreadAllocatedBytes(id);
        for(int i=0;i<100;i++) project(batch,output,input,weight,columns,i+4096);
        long allocated=bean.getThreadAllocatedBytes(id)-before;
        System.out.println("batch="+batch+" bytesPerCall="+(allocated/100.0)+" checksumBits="+Integer.toHexString(Float.floatToRawIntBits(checksum)));
        if(!Float.isFinite(checksum)||checksum==0)throw new AssertionError("unobservable numeric result");
      }
    }
  }
  static void project(int batch,float[] out,float[] input,MemorySegment weights,int columns,int iteration) {
    input[iteration%columns] += (iteration&1)==0 ? 0.015625f : -0.015625f;
    if(batch==1)TensorOps.ggufMatmul(out,input,weights,GgufTensorType.Q5_1,1,columns);
    else TensorOps.ggufBatchedMatmul(out,input,weights,GgufTensorType.Q5_1,batch,1,columns,null,null,null,null,null,GgufQ4Kernel.SHORT_PAIRWISE);
    checksum=out[0]+(batch==2?out[1]:0);
  }
}
