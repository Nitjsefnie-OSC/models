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
package com.integrallis.models.backend.nativekernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledNativeKernelLibraryTest {
  private static final byte[] LIBRARY_BYTES =
      "models-native-test-library".getBytes(StandardCharsets.UTF_8);

  @TempDir Path temporaryDirectory;

  @Test
  void verifiesAndExtractsTheCurrentPlatformLibraryOnce() throws IOException {
    NativeKernelPlatform platform = NativeKernelPlatform.detect("Linux", "amd64");
    Path artifact = createArtifact(platform, sha256(LIBRARY_BYTES), LIBRARY_BYTES);
    Path cache = temporaryDirectory.resolve("cache");

    try (URLClassLoader loader = classLoader(artifact)) {
      Path first = BundledNativeKernelLibrary.resolve(loader, platform, cache);
      Path second = BundledNativeKernelLibrary.resolve(loader, platform, cache);

      assertThat(first).isEqualTo(second).isRegularFile();
      assertThat(Files.readAllBytes(first)).containsExactly(LIBRARY_BYTES);
      assertThat(first)
          .startsWith(
              cache.resolve("abi-" + NativeKernelLibrary.ABI_VERSION).resolve(platform.id()));
    }

    Files.delete(artifact);
    assertThat(artifact).doesNotExist();
  }

  @Test
  void rejectsAnArtifactWhoseLibraryDoesNotMatchItsDigest() throws IOException {
    NativeKernelPlatform platform = NativeKernelPlatform.detect("Mac OS X", "arm64");
    Path artifact = createArtifact(platform, "0".repeat(64), LIBRARY_BYTES);

    try (URLClassLoader loader = classLoader(artifact)) {
      assertThatThrownBy(
              () ->
                  BundledNativeKernelLibrary.resolve(
                      loader, platform, temporaryDirectory.resolve("cache")))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("SHA-256");
    }

    Files.delete(artifact);
    assertThat(artifact).doesNotExist();
  }

  @Test
  void rejectsMetadataForAnotherPlatform() throws IOException {
    NativeKernelPlatform packagedPlatform = NativeKernelPlatform.detect("Linux", "aarch64");
    NativeKernelPlatform runtimePlatform = NativeKernelPlatform.detect("Linux", "amd64");
    Path artifact = createArtifact(packagedPlatform, sha256(LIBRARY_BYTES), LIBRARY_BYTES);

    try (URLClassLoader loader = classLoader(artifact)) {
      assertThatThrownBy(
              () ->
                  BundledNativeKernelLibrary.resolve(
                      loader, runtimePlatform, temporaryDirectory.resolve("cache")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(runtimePlatform.id());
    }
  }

  @Test
  void packagedHostArtifactCanBeExtractedAndOpened() throws IOException {
    String configuredArtifact = System.getProperty("models.native.test.artifact");
    assertThat(configuredArtifact)
        .as("Gradle must supply the packaged host native artifact")
        .isNotBlank();
    Path artifact = Path.of(configuredArtifact);
    NativeKernelPlatform platform = NativeKernelPlatform.current();

    try (URLClassLoader loader = classLoader(artifact)) {
      Path library =
          BundledNativeKernelLibrary.resolve(
              loader, platform, temporaryDirectory.resolve("packaged-cache"));

      assertThat(library.getFileName().toString()).isEqualTo(platform.libraryFileName());
      try (NativeKernelLibrary kernels = NativeKernelLibrary.open(library)) {
        assertThat(kernels.abiVersion()).isEqualTo(NativeKernelLibrary.ABI_VERSION);
        assertThat(kernels.supports(NativeKernelCapability.PERSISTENT_WORKER_CONTEXT)).isTrue();
      }
    }
  }

  @Test
  void rejectsAnArtifactBuiltAgainstAnotherAbi() throws IOException {
    NativeKernelPlatform platform = NativeKernelPlatform.detect("Linux", "amd64");
    Path artifact =
        createArtifactWithMetadata(
            platform,
            """
            abi=%d
            platform=%s
            library=%s
            sha256=%s
            """
                .formatted(
                    NativeKernelLibrary.ABI_VERSION - 1,
                    platform.id(),
                    platform.libraryFileName(),
                    sha256(LIBRARY_BYTES)));

    try (URLClassLoader loader = classLoader(artifact)) {
      assertThatThrownBy(
              () ->
                  BundledNativeKernelLibrary.resolve(
                      loader, platform, temporaryDirectory.resolve("cache")))
          .as("an artifact from an earlier ABI must not be loaded against this Java side")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ABI")
          .hasMessageContaining(Integer.toString(NativeKernelLibrary.ABI_VERSION));
    }
  }

  @Test
  void rejectsMetadataNamingAnotherLibraryFile() throws IOException {
    NativeKernelPlatform platform = NativeKernelPlatform.detect("Linux", "amd64");
    Path artifact =
        createArtifactWithMetadata(
            platform,
            """
            abi=%d
            platform=%s
            library=libsomething_else.so
            sha256=%s
            """
                .formatted(NativeKernelLibrary.ABI_VERSION, platform.id(), sha256(LIBRARY_BYTES)));

    try (URLClassLoader loader = classLoader(artifact)) {
      assertThatThrownBy(
              () ->
                  BundledNativeKernelLibrary.resolve(
                      loader, platform, temporaryDirectory.resolve("cache")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("libsomething_else.so")
          .hasMessageContaining(platform.libraryFileName());
    }
  }

  @Test
  void rejectsADigestThatIsNotLowercaseHexOfTheRightLength() throws IOException {
    NativeKernelPlatform platform = NativeKernelPlatform.detect("Linux", "amd64");
    for (String malformed :
        new String[] {
          sha256(LIBRARY_BYTES).toUpperCase(java.util.Locale.ROOT),
          sha256(LIBRARY_BYTES).substring(1),
          "z".repeat(64)
        }) {
      Path artifact =
          createArtifactWithMetadata(
              platform,
              """
              abi=%d
              platform=%s
              library=%s
              sha256=%s
              """
                  .formatted(
                      NativeKernelLibrary.ABI_VERSION,
                      platform.id(),
                      platform.libraryFileName(),
                      malformed));

      try (URLClassLoader loader = classLoader(artifact)) {
        assertThatThrownBy(
                () ->
                    BundledNativeKernelLibrary.resolve(
                        loader,
                        platform,
                        temporaryDirectory.resolve("cache-" + malformed.length())))
            .as("digest %s", malformed)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SHA-256");
      }
      Files.delete(artifact);
    }
  }

  @Test
  void rejectsMetadataMissingARequiredProperty() throws IOException {
    NativeKernelPlatform platform = NativeKernelPlatform.detect("Linux", "amd64");
    Path artifact =
        createArtifactWithMetadata(
            platform,
            """
            abi=%d
            platform=%s
            library=%s
            sha256=
            """
                .formatted(
                    NativeKernelLibrary.ABI_VERSION, platform.id(), platform.libraryFileName()));

    try (URLClassLoader loader = classLoader(artifact)) {
      assertThatThrownBy(
              () ->
                  BundledNativeKernelLibrary.resolve(
                      loader, platform, temporaryDirectory.resolve("cache")))
          .as("a blank value is as missing as an absent key")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("missing")
          .hasMessageContaining("sha256");
    }
  }

  /**
   * The no-argument {@code resolve()} is the shipped entry point: it takes the host platform and
   * the configured cache root for itself. Platform-classified kernel JARs are the usual thing
   * missing from a deployment, so what it says when none is on the classpath is a real diagnostic
   * and not an internal detail. It fails before reading the cache root, so this exercises both
   * roots without writing anything outside the temporary directory.
   */
  @Test
  void shippedResolutionNamesTheResourceItExpectedWhenNoKernelJarIsOnTheClasspath() {
    String savedCache = System.getProperty(BundledNativeKernelLibrary.CACHE_DIRECTORY_PROPERTY);
    NativeKernelPlatform platform = NativeKernelPlatform.current();
    try {
      for (String configuredCache :
          new String[] {temporaryDirectory.resolve("configured-cache").toString(), null}) {
        if (configuredCache == null) {
          System.clearProperty(BundledNativeKernelLibrary.CACHE_DIRECTORY_PROPERTY);
        } else {
          System.setProperty(BundledNativeKernelLibrary.CACHE_DIRECTORY_PROPERTY, configuredCache);
        }

        assertThatThrownBy(BundledNativeKernelLibrary::resolve)
            .as("configured cache %s", configuredCache)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(platform.id())
            .hasMessageContaining(BundledNativeKernelLibrary.METADATA_FILE_NAME);
      }
    } finally {
      if (savedCache == null) {
        System.clearProperty(BundledNativeKernelLibrary.CACHE_DIRECTORY_PROPERTY);
      } else {
        System.setProperty(BundledNativeKernelLibrary.CACHE_DIRECTORY_PROPERTY, savedCache);
      }
    }
  }

  private Path createArtifactWithMetadata(NativeKernelPlatform platform, String metadata)
      throws IOException {
    Path artifact = temporaryDirectory.resolve(platform.id() + "-" + metadata.hashCode() + ".jar");
    try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
      addEntry(
          jar,
          platform.resourceDirectory() + BundledNativeKernelLibrary.METADATA_FILE_NAME,
          metadata.getBytes(StandardCharsets.UTF_8));
      addEntry(jar, platform.resourceDirectory() + platform.libraryFileName(), LIBRARY_BYTES);
    }
    return artifact;
  }

  private Path createArtifact(NativeKernelPlatform platform, String digest, byte[] libraryBytes)
      throws IOException {
    Path artifact = temporaryDirectory.resolve(platform.id() + ".jar");
    String metadata =
        """
        abi=%d
        platform=%s
        library=%s
        sha256=%s
        """
            .formatted(
                NativeKernelLibrary.ABI_VERSION, platform.id(), platform.libraryFileName(), digest);
    try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
      addEntry(
          jar,
          platform.resourceDirectory() + BundledNativeKernelLibrary.METADATA_FILE_NAME,
          metadata.getBytes(StandardCharsets.UTF_8));
      addEntry(jar, platform.resourceDirectory() + platform.libraryFileName(), libraryBytes);
    }
    return artifact;
  }

  private static URLClassLoader classLoader(Path artifact) throws IOException {
    return new URLClassLoader(new URL[] {artifact.toUri().toURL()}, null);
  }

  private static void addEntry(JarOutputStream jar, String name, byte[] bytes) throws IOException {
    jar.putNextEntry(new JarEntry(name));
    jar.write(bytes);
    jar.closeEntry();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }
}
