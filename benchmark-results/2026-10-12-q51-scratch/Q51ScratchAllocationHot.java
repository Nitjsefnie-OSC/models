import com.integrallis.models.backend.purejava.ops.TensorOps;
import com.integrallis.models.backend.purejava.gguf.GgufTensorType;
import com.integrallis.vectors.core.GgufQ4Kernel;
import java.lang.foreign.*;
import java.lang.management.ManagementFactory;
public class Q51ScratchAllocationHot {
  public static void main(String[] args) {
    int columns=16384; float[] input=new float[columns*2],output=new float[2];
    java.util.Arrays.fill(input,0.125f);
    var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    try(var arena=Arena.ofConfined()) {
      var weight=arena.allocate(columns/32*24); weight.fill((byte)0);
      for(int batch:new int[]{1,2}) {
        for(int i=0;i<4096;i++) project(batch,output,input,weight,columns);
        long id=Thread.currentThread().threadId(), before=bean.getThreadAllocatedBytes(id);
        for(int i=0;i<100;i++) project(batch,output,input,weight,columns);
        long allocated=bean.getThreadAllocatedBytes(id)-before;
        System.out.println("batch="+batch+" bytesPerCall="+(allocated/100.0));
        
      }
    }
  }
  static void project(int batch,float[] out,float[] input,MemorySegment weights,int columns) {
    if(batch==1)TensorOps.ggufMatmul(out,input,weights,GgufTensorType.Q5_1,1,columns);
    else TensorOps.ggufBatchedMatmul(out,input,weights,GgufTensorType.Q5_1,batch,1,columns,null,null,null,null,null,GgufQ4Kernel.SHORT_PAIRWISE);
  }
}
