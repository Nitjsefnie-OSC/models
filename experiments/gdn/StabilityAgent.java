package com.integrallis.models.backend.purejava.qwen35;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Set;

/** Instrument only the published dependency in an isolated diagnostic JVM; no JAR is edited. */
public final class StabilityAgent {
  public static void premain(String mode, Instrumentation instrumentation) {
    Set<String> methods =
        switch (mode == null ? "all" : mode) {
          case "all" -> Set.of("swiGlu", "sigmoidAndScaledSoftplus", "causalDepthwiseConv1dSilu");
          case "gate" -> Set.of("sigmoidAndScaledSoftplus");
          case "conv" -> Set.of("causalDepthwiseConv1dSilu");
          default -> throw new IllegalArgumentException(mode);
        };
    int expectedSites =
        methods.size() == 3 ? 5 : methods.contains("sigmoidAndScaledSoftplus") ? 3 : 1;
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> redefining,
              ProtectionDomain domain,
              byte[] bytes) {
            if (!name.equals("com/integrallis/vectors/core/PanamaVectorUtilSupport")) return null;
            try {
              int[] sites = {0};
              var cf = ClassFile.of();
              var vector = ClassDesc.of("jdk.incubator.vector.FloatVector");
              var unary = ClassDesc.of("jdk.incubator.vector.VectorOperators$Unary");
              byte[] transformed =
                  cf.transformClass(
                      cf.parse(bytes),
                      ClassTransform.transformingMethodBodies(
                          method -> methods.contains(method.methodName().stringValue()),
                          (builder, element) -> {
                            if (element instanceof InvokeInstruction invoke
                                && invoke.owner().asSymbol().equals(vector)
                                && invoke.name().stringValue().equals("lanewise")
                                && invoke.typeSymbol().equals(MethodTypeDesc.of(vector, unary))) {
                              builder.invokestatic(
                                  ClassDesc.of(StableVectorOps.class.getName()),
                                  "lanewise",
                                  MethodTypeDesc.of(vector, vector, unary));
                              sites[0]++;
                            } else builder.with(element);
                          }));
              if (sites[0] != expectedSites || !cf.verify(transformed).isEmpty())
                throw new AssertionError("unexpected transformed sites/verification: " + sites[0]);
              var sha = MessageDigest.getInstance("SHA-256");
              System.err.println(
                  "STABILITY_AGENT methods="
                      + methods
                      + " sites="
                      + sites[0]
                      + " originalSHA256="
                      + HexFormat.of().formatHex(sha.digest(bytes))
                      + " transformedSHA256="
                      + HexFormat.of().formatHex(sha.digest(transformed)));
              return transformed;
            } catch (Throwable failure) {
              failure.printStackTrace();
              // Instrumentation otherwise swallows transformer exceptions and silently uses
              // baseline.
              Runtime.getRuntime().halt(92);
              return null;
            }
          }
        });
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.err.println(
                      "STABILITY_AGENT executedCalls=" + StableVectorOps.calls.sum());
                  if (StableVectorOps.calls.sum() == 0) Runtime.getRuntime().halt(93);
                }));
  }
}
