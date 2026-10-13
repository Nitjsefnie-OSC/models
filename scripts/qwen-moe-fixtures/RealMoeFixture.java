package experiments.moe;

import com.fasterxml.jackson.databind.*;
import com.integrallis.models.api.*;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.backend.nativekernel.RustFfmBackend;
import com.integrallis.models.backend.purejava.gguf.GgufParser;
import com.integrallis.models.backend.purejava.qwen35.Qwen35Config;
import java.lang.foreign.Arena;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real-weight consistency only; cross-backend/task quality requires an independent oracle. */
public final class RealMoeFixture {
  static final ObjectMapper JSON = new ObjectMapper();
  static void emit(Map<String, ?> row) throws Exception { System.out.println(JSON.writeValueAsString(row)); }
  static byte[] bytes(float[] values) {
    var b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float v : values) { if (!Float.isFinite(v)) throw new AssertionError("Nonfinite logit"); b.putFloat(v); }
    return b.array();
  }
  static String hash(float[] values) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(values))); }
  static int best(float[] v) { int b=0; for(int i=1;i<v.length;i++) if(v[i]>v[b]) b=i; return b; }
  static List<float[]> snapshots(BatchInferenceBackend backend, int[] tokens) throws Exception {
    var result = new ArrayList<float[]>();
    try (var session=backend.openSession()) {
      float[] logits=backend.prefill(session,tokens,0);
      for(int step=0;step<3;step++) {
        result.add(logits.clone());
        if(step<2) logits=backend.forward(session,best(logits),tokens.length+step);
      }
    }
    return result;
  }
  static BatchInferenceBackend load(Path model, Path nativeLib, String route, int batch) throws Exception {
    var config=new BackendConfiguration(Map.of("real-moe-fixture", "consistency-only"),
        Map.of("models.purejava.maxContextLength","256", "models.purejava.prefillBatchSize",Integer.toString(batch)),List.of());
    return route.equals("native") ? RustFfmBackend.load(model,nativeLib,config) : PureJavaBackend.load(model,config);
  }
  public static void main(String[] args) throws Exception {
    Path model=Path.of(args[0]), nativeLib=Path.of(args[1]), tasksPath=Path.of(args[2]), out=Path.of(args[5]);
    String architecture=args[3], route=args[4]; Files.createDirectories(out);
    try(var arena=Arena.ofConfined()) {
      var file=GgufParser.parse(model,arena); var config=Qwen35Config.fromMetadata(file.metadata());
      if(!config.architecture().equals(architecture) || !config.usesMixtureOfExperts()) throw new AssertionError("Wrong affected MoE architecture");
      emit(Map.of("kind","model","architecture",config.architecture(),"experts",config.numExperts(),"activeExperts",config.numExpertsUsed()));
    }
    var selected=new ArrayList<JsonNode>();
    var tasks=JSON.readTree(tasksPath.toFile());
    for(String dataset:List.of("jevbench","snips")) {
      JsonNode choice=null;
      for(var task:tasks) if(task.path("dataset").asText().equals(dataset)) { choice=task;break; }
      if(choice==null) throw new AssertionError("Missing public dataset"); selected.add(choice);
    }
    var expected=new ArrayList<List<String>>(); var encoded=new ArrayList<int[]>();
    try(var backend=load(model,nativeLib,route,16)) {
      emit(Map.of("kind","diagnostics","route",route,"value",backend.diagnostics()));
      for(int taskIndex=0;taskIndex<selected.size();taskIndex++) {
        var task=selected.get(taskIndex); int[] tokens=backend.tokenizer().encode(task.path("prompt").asText());
        if(tokens.length<=16 || tokens.length>240) throw new AssertionError("Expected bounded chunk-spanning public prompt"); encoded.add(tokens);
        List<String> first=null;
        for(int repeat=0;repeat<3;repeat++) {
          var values=snapshots(backend,tokens); var hashes=new ArrayList<String>();
          for(int step=0;step<values.size();step++) { hashes.add(hash(values.get(step))); if(repeat==0) Files.write(out.resolve("task-"+taskIndex+"-step-"+step+".f32"),bytes(values.get(step))); }
          emit(Map.of("kind","fresh-session","dataset",task.path("dataset").asText(),"id",task.path("id").asText(),"repeat",repeat,"inputTokens",tokens,"hashes",hashes));
          if(first!=null&&!first.equals(hashes)) throw new AssertionError("Fresh session changed after compilation"); first=hashes;
        }
        expected.add(first);
        String implicit=hash(backend.prefill(tokens,0)); backend.reset(); String reset=hash(backend.prefill(tokens,0));
        if(!first.getFirst().equals(implicit)||!implicit.equals(reset)) throw new AssertionError("Implicit reset changed logits");
        emit(Map.of("kind","implicit-reset","task",taskIndex,"hash",reset));
      }
    }
    try(var backend=load(model,nativeLib,route,1)) {
      for(int taskIndex=0;taskIndex<selected.size();taskIndex++) {
        var hashes=new ArrayList<String>(); for(var values:snapshots(backend,encoded.get(taskIndex))) hashes.add(hash(values));
        if(!expected.get(taskIndex).equals(hashes)) throw new AssertionError("Serial/chunked prefill changed logits");
        emit(Map.of("kind","batch-parity","task",taskIndex,"hashes",hashes));
      }
    }
    emit(Map.of("kind","complete","scope","Real-weight repeat/reset/chunk consistency; no task-quality/performance qualification", "route",route));
  }
}
