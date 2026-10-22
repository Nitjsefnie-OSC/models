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
import static org.mockito.Mockito.mock;

import com.integrallis.models.api.BackendDiagnostics;
import com.integrallis.models.api.GenerationUsage;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.api.TokenStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The {@link ChatClient.Builder} this starter contributes.
 *
 * <p>Before this existed, an application on this starter had to build the builder by hand, because
 * Spring AI contributes one only from its own model starters. These tests pin the four behaviours
 * that make the contributed bean safe to rely on: it appears when there is a model, it does not
 * appear when there is not, it never displaces one the application already has, and each injection
 * point gets its own instance.
 */
@Tag("unit")
class ModelsChatClientAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ModelsAutoConfiguration.class));

  @Test
  void contributesABuilderOverTheAdaptedLocalModel() {
    runner
        .withUserConfiguration(LocalModelConfiguration.class)
        .run(
            context -> {
              assertThat(context)
                  .hasBean(ModelsAutoConfiguration.MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME);
              ChatClient.Builder builder = context.getBean(ChatClient.Builder.class);

              // End to end, not just bean presence: the builder has to reach the local model.
              String answer = builder.build().prompt("ping").call().content();
              assertThat(answer).isEqualTo("local answer");
            });
  }

  @Test
  void contributesNoBuilderWhenTheApplicationHasNoLocalModel() {
    // The starter is on the classpath in plenty of applications that talk to a remote model or to
    // nothing at all. A builder that resolved a model lazily and failed on first use would turn
    // those into runtime errors.
    runner.run(
        context -> {
          assertThat(context)
              .doesNotHaveBean(ModelsAutoConfiguration.MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME);
          assertThat(context).doesNotHaveBean(ChatClient.Builder.class);
        });
  }

  @Test
  void leavesAnApplicationSuppliedBuilderAlone() {
    runner
        .withUserConfiguration(LocalModelConfiguration.class, OwnBuilderConfiguration.class)
        .run(
            context -> {
              assertThat(context)
                  .doesNotHaveBean(ModelsAutoConfiguration.MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME);
              assertThat(context).hasSingleBean(ChatClient.Builder.class);
              assertThat(context.getBean(ChatClient.Builder.class))
                  .isSameAs(context.getBean(OwnBuilderConfiguration.OWN_BUILDER_BEAN_NAME));
            });
  }

  @Test
  void givesEveryInjectionPointItsOwnBuilder() {
    runner
        .withUserConfiguration(LocalModelConfiguration.class)
        .run(
            context -> {
              ChatClient.Builder first = context.getBean(ChatClient.Builder.class);
              ChatClient.Builder second = context.getBean(ChatClient.Builder.class);

              // A builder carries mutable defaults. Were this a singleton, one consumer's
              // defaultSystem would silently become everyone's.
              assertThat(first).isNotSameAs(second);
            });
  }

  @Test
  void appliesEveryBuilderCustomizerInOrder() {
    runner
        .withUserConfiguration(LocalModelConfiguration.class, CustomizersConfiguration.class)
        .run(
            context -> {
              CustomizersConfiguration.APPLIED.clear();
              context.getBean(ChatClient.Builder.class);

              // Ordered, and all of them: a customizer contributed by some other library is
              // exactly what gets dropped when each application hand-rolls its own builder.
              assertThat(CustomizersConfiguration.APPLIED).containsExactly("first", "second");
            });
  }

  @Test
  void theBuilderCarriesWhatACustomizerSetOnIt() {
    runner
        .withUserConfiguration(LocalModelConfiguration.class, SystemPromptCustomizerConfiguration.class)
        .run(
            context -> {
              // Observable rather than assumed: the customizer sets a default system prompt, and
              // the only way to see it took effect is that the model received it.
              LocalModelConfiguration.LAST_PROMPT = null;
              context.getBean(ChatClient.Builder.class).build().prompt("ping").call().content();
              assertThat(LocalModelConfiguration.LAST_PROMPT)
                  .contains(SystemPromptCustomizerConfiguration.SYSTEM_PROMPT);
            });
  }

  @Test
  void startsWithoutTheChatClientArtifactAndStillAdaptsTheModel() {
    // The starter must not require spring-ai-client-chat. Hiding the class is what an application
    // depending only on spring-ai-model sees, and the context has to come up anyway -- the
    // classpath guard in ModelsAutoConfiguration is what this exercises.
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ModelsAutoConfiguration.class))
        .withUserConfiguration(LocalModelConfiguration.class)
        .withClassLoader(new FilteredClassLoader(ChatClient.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean(ModelsAutoConfiguration.MODELS_CHAT_MODEL_BEAN_NAME);
              assertThat(context)
                  .doesNotHaveBean(ModelsAutoConfiguration.MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class LocalModelConfiguration {
    static volatile String LAST_PROMPT;

    @Bean
    TextGenerationModel textGenerationModel() {
      return new TextGenerationModel() {
        @Override
        public String modelName() {
          return "chat-client-test";
        }

        @Override
        public BackendDiagnostics diagnostics() {
          return BackendDiagnostics.unavailable("chat-client-test");
        }

        @Override
        public void generate(String prompt, SamplingOptions options, TokenStream stream) {
          LAST_PROMPT = prompt;
          stream.onToken("local answer");
          stream.onComplete(new GenerationUsage(1, 2));
        }
      };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnBuilderConfiguration {
    static final String OWN_BUILDER_BEAN_NAME = "applicationChatClientBuilder";

    @Bean(OWN_BUILDER_BEAN_NAME)
    ChatClient.Builder applicationChatClientBuilder() {
      return ChatClient.builder(mock(ChatModel.class));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomizersConfiguration {
    static final List<String> APPLIED = new ArrayList<>();

    @Bean
    @Order(1)
    ChatClientBuilderCustomizer first() {
      return builder -> APPLIED.add("first");
    }

    @Bean
    @Order(2)
    ChatClientBuilderCustomizer second() {
      return builder -> APPLIED.add("second");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class SystemPromptCustomizerConfiguration {
    static final String SYSTEM_PROMPT = "you-are-a-fixture";

    @Bean
    ChatClientBuilderCustomizer systemPrompt() {
      return builder -> builder.defaultSystem(SYSTEM_PROMPT);
    }
  }
}
