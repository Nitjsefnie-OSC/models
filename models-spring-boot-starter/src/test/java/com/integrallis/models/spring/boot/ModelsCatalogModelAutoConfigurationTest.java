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
package com.integrallis.models.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.integrallis.models.api.BackendDiagnostics;
import com.integrallis.models.api.GenerationUsage;
import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.api.TokenStream;
import com.integrallis.models.backend.purejava.CatalogBackends;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Naming an installed catalog model in configuration instead of opening one in Java.
 *
 * <p>No weights are loaded here, so what these tests pin is the wiring and the refusals: that the
 * property is read at all, that an application's own model bean still wins, that an unresolvable id
 * fails loudly with the id in the message, and that an application without the engine on its
 * classpath is unaffected. Resolution itself -- which catalog wins, and the difference between an
 * unknown id and an unfetched one -- is settled in {@code CatalogBackendsTest}.
 */
@Tag("unit")
class ModelsCatalogModelAutoConfigurationTest {

  private static final String PROPERTY = "integrallis.models.catalog-model=";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ModelsAutoConfiguration.class));

  @Test
  void readsThePropertyAndFailsWithTheIdWhenNoInstalledCatalogHoldsIt() {
    // The whole path in one assertion: the property is read during definition registration, a
    // backend definition is contributed, and creating it reaches CatalogBackends, which refuses.
    runner
        .withPropertyValues(PROPERTY + "no_such_model")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("no_such_model")
                  .hasStackTraceContaining("no installed catalog can open");
            });
  }

  @Test
  void contributesNothingWhenThePropertyIsAbsent() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context)
              .doesNotHaveBean(ModelsCatalogModelRegistrar.MODELS_CATALOG_BACKEND_BEAN_NAME);
          assertThat(context).doesNotHaveBean(InferenceBackend.class);
        });
  }

  @Test
  void treatsABlankPropertyAsAbsent() {
    runner
        .withPropertyValues(PROPERTY + "   ")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(ModelsCatalogModelRegistrar.MODELS_CATALOG_BACKEND_BEAN_NAME);
            });
  }

  @Test
  void leavesAnApplicationSuppliedModelInCharge() {
    // Configuration is the convenience, never an override. An application that wrote the Java gets
    // its own model even with the property set, and the unresolvable id is never touched -- which
    // is why this context starts at all.
    runner
        .withPropertyValues(PROPERTY + "no_such_model")
        .withUserConfiguration(LocalModelConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(ModelsCatalogModelRegistrar.MODELS_CATALOG_BACKEND_BEAN_NAME);
              assertThat(context).hasBean(ModelsAutoConfiguration.MODELS_CHAT_MODEL_BEAN_NAME);
            });
  }

  @Test
  void ignoresThePropertyWhenTheEngineIsNotOnTheClasspath() {
    // An application using this starter to adapt a remote model has no decoder. Setting the
    // property there is a configuration mistake, but it must not stop the context coming up with
    // a NoClassDefFoundError from a class this starter only optionally depends on.
    runner
        .withPropertyValues(PROPERTY + "no_such_model")
        .withClassLoader(new FilteredClassLoader(CatalogBackends.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(ModelsCatalogModelRegistrar.MODELS_CATALOG_BACKEND_BEAN_NAME);
            });
  }

  @Test
  void bindsThePropertyOntoTheBoundPropertiesBeanToo() {
    // Same spelling, same meaning, both places it is read: the registrar reads it from the
    // Environment and the properties bean binds it. A value that meant one thing to the registrar
    // and another to ModelsProperties would be the drifting-setting failure this repo has already
    // paid for.
    runner
        .withPropertyValues(PROPERTY + "some_model")
        .withUserConfiguration(LocalModelConfiguration.class)
        .run(
            context ->
                assertThat(context.getBean(ModelsProperties.class).catalogModel())
                    .isEqualTo("some_model"));
  }

  @Configuration(proxyBeanMethods = false)
  static class LocalModelConfiguration {

    @Bean
    TextGenerationModel textGenerationModel() {
      return new TextGenerationModel() {
        @Override
        public String modelName() {
          return "catalog-property-test";
        }

        @Override
        public BackendDiagnostics diagnostics() {
          return BackendDiagnostics.unavailable("catalog-property-test");
        }

        @Override
        public void generate(String prompt, SamplingOptions options, TokenStream stream) {
          stream.onToken("local answer");
          stream.onComplete(new GenerationUsage(1, 2));
        }
      };
    }
  }
}
