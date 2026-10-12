package com.integrallis.models.backend.purejava.qwen35;

import com.integrallis.vectors.core.VectorUtil;

public final class ProductionJavaKernelProbe {
  public static void main(String[] args) {
    var checks = new GatedDeltaNetKernelTest();
    checks.matchesRowOrderAcrossTailsPrefixesAndStrongDecay();
    checks.advancesOnlyTheRequestedStateSlot();
    checks.rejectsAliasedWritesBeforeStartingWorkers();
    System.out.println("PASS " + VectorUtil.runtimeCapabilities());
  }
}
