package com.integrallis.models.backend.purejava.qwen35;
import com.integrallis.models.backend.nativekernel.RustFfmBackend;
import java.nio.file.Path;
import java.util.*;
public final class QualityFactorial {
  public static void main(String[] args) throws Exception {
    var tasks=ProductionRouteProof.JSON.readTree(Path.of(args[2]).toFile());
    try(var backend=RustFfmBackend.load(Path.of(args[0]),Path.of(args[1]),ProductionRouteProof.WORKLOAD)) {
      ProductionRouteProof.emit(Map.of("kind","diagnostics","values",backend.diagnostics()));
      for(var task:tasks) {
        int[] input=backend.tokenizer().encode(task.path("prompt").asText());
        try(var session=backend.openSession()) {
          float[] logits=backend.prefill(session,input,0).clone();
          Map<String,Float> scores=new LinkedHashMap<>();String prediction="";float best=-Float.MAX_VALUE;
          for(int n=0;n<task.path("labels").size();n++) {
            int[] encoded=backend.tokenizer().encode(" "+(char)('A'+n));
            float score=logits[encoded[encoded.length-1]];String label=task.path("labels").get(n).asText();scores.put(label,score);
            if(score>best){best=score;prediction=label;}
          }
          ProductionRouteProof.emit(Map.of("kind","request","id",task.path("id").asText(),"dataset",task.path("dataset").asText(),
              "inputTokens",input,"expected",task.path("expected").asText(),"prediction",prediction,"labelScores",scores,"prefillLogitsSha256",ProductionRouteProof.digest(logits)));
        }
      }
    }
  }
}
