/*
 * Copyright 2025-2026 Integrallis Software, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.integrallis.models.backend.purejava.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.integrallis.vectors.core.PanamaConstants;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class NativeWidthAllocationTest {
  @ParameterizedTest
  @ValueSource(ints = {16, 32})
  void inferenceKernelsDoNotAllocateUnsupportedVectorObjects(int maximumVectorBytes)
      throws Exception {
    String classpath =
        String.join(
            File.pathSeparator,
            location(GroupedQueryAttentionKernel.class),
            location(NativeWidthAllocationProbe.class),
            location(PanamaConstants.class));
    Path output = Files.createTempFile("native-width-allocation-", ".log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-modules",
                "jdk.incubator.vector",
                "-Xmx256m",
                "-Xbatch",
                "-XX:MaxVectorSize=" + maximumVectorBytes,
                "-cp",
                classpath,
                NativeWidthAllocationProbe.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), "allocation probe timed out");
      String result = Files.readString(output);
      assertEquals(0, process.exitValue(), result);
      assertTrue(result.contains("BOUNDED kernels=6"), result);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      Files.deleteIfExists(output);
    }
  }

  private static String location(Class<?> type) throws Exception {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
  }
}
