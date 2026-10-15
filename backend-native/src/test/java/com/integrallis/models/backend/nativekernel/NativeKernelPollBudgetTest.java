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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The poll budget is read once, at library open, and a bad value throws there rather than quietly
 * changing how workers park. {@link NativePerformanceCliffTest} observes the value the shim is
 * actually told; these tests pin what the property is allowed to say in the first place.
 */
class NativeKernelPollBudgetTest {
  private String saved;

  /** Captured once per test: every other class in this module opens a library, which reads it. */
  @BeforeEach
  void captureProperty() {
    saved = System.getProperty(NativeKernelLibrary.POLL_MILLIS_PROPERTY);
  }

  @AfterEach
  void restoreProperty() {
    if (saved == null) {
      System.clearProperty(NativeKernelLibrary.POLL_MILLIS_PROPERTY);
    } else {
      System.setProperty(NativeKernelLibrary.POLL_MILLIS_PROPERTY, saved);
    }
  }

  private void set(String value) {
    if (value == null) {
      System.clearProperty(NativeKernelLibrary.POLL_MILLIS_PROPERTY);
    } else {
      System.setProperty(NativeKernelLibrary.POLL_MILLIS_PROPERTY, value);
    }
  }

  @Test
  @DisplayName("an unset or blank budget keeps the shipped default rather than parking forever")
  void unsetAndBlankUseTheDefault() {
    set(null);
    assertThat(NativeKernelLibrary.configuredPollMillis())
        .isEqualTo(NativeKernelLibrary.DEFAULT_POLL_MILLIS);

    set("   ");
    assertThat(NativeKernelLibrary.configuredPollMillis())
        .as("a blank property is an unset property, not a zero budget")
        .isEqualTo(NativeKernelLibrary.DEFAULT_POLL_MILLIS);
  }

  @Test
  @DisplayName("a value inside the range is taken, surrounding whitespace and all")
  void acceptsTheInclusiveRange() {
    set("1");
    assertThat(NativeKernelLibrary.configuredPollMillis()).isEqualTo(1);

    set("60000");
    assertThat(NativeKernelLibrary.configuredPollMillis()).isEqualTo(60_000);

    set("  4  ");
    assertThat(NativeKernelLibrary.configuredPollMillis())
        .as("a property read from a file or a shell commonly carries whitespace")
        .isEqualTo(4);
  }

  @Test
  @DisplayName("a budget outside the range is refused, naming the property and the value")
  void refusesValuesOutsideTheRange() {
    for (String rejected : new String[] {"0", "-1", "60001", "9223372036854775807"}) {
      set(rejected);
      assertThatThrownBy(NativeKernelLibrary::configuredPollMillis)
          .as("budget %s", rejected)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(NativeKernelLibrary.POLL_MILLIS_PROPERTY)
          .hasMessageContaining(rejected);
    }
  }

  @Test
  @DisplayName("a non-integer budget is refused rather than silently defaulted")
  void refusesNonIntegers() {
    for (String rejected : new String[] {"4ms", "4.5", "", "0x4", "9223372036854775808"}) {
      set(rejected);
      if (rejected.isEmpty()) {
        assertThat(NativeKernelLibrary.configuredPollMillis())
            .as("an empty property is blank, so it defaults")
            .isEqualTo(NativeKernelLibrary.DEFAULT_POLL_MILLIS);
        continue;
      }
      assertThatThrownBy(NativeKernelLibrary::configuredPollMillis)
          .as("budget %s", rejected)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(NativeKernelLibrary.POLL_MILLIS_PROPERTY)
          .hasCauseInstanceOf(NumberFormatException.class);
    }
  }
}
