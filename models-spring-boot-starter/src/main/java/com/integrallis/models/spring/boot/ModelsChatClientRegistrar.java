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

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * Registers a Spring AI {@link ChatClient.Builder} over the chat model this starter registered.
 *
 * <p>This class exists in its own file, and is referenced only from behind a classpath check in
 * {@link ModelsAutoConfiguration}, because {@code spring-ai-client-chat} is an optional dependency:
 * an application that wants only the {@code ChatModel} adapter must not be made to carry the
 * client. Loading this class is what pulls {@link ChatClient} in, so nothing may touch it until
 * that artifact is known to be present.
 *
 * <p><b>Why this is here at all.</b> Spring AI's own model starters contribute a {@code
 * ChatClient.Builder}, and an application assembled from {@code spring-ai-model} alone does not get
 * one -- so every user of this starter had to write the builder by hand. Observed in the wild: a
 * third-party Spring AI recipe did exactly that, for this starter and for a competing in-process
 * engine, and called it out as an inconvenience in both.
 *
 * <p><b>The builder is a prototype.</b> A {@code ChatClient.Builder} carries mutable defaults --
 * {@code defaultSystem}, {@code defaultTools}, {@code defaultAdvisors} -- so a singleton would let
 * one injection point's defaults leak into every other one. Each injection point gets its own.
 */
final class ModelsChatClientRegistrar {

  /** Stable bean name for the auto-configured Spring AI chat client builder. */
  static final String MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME = "modelsChatClientBuilder";

  private ModelsChatClientRegistrar() {}

  /**
   * Registers the builder definition unless the application already has one.
   *
   * <p>Called from the same registrar that registers the chat model, immediately after it, so the
   * model this builder resolves is known to exist by then. A {@code @ConditionalOnBean(ChatModel)}
   * on a {@code @Bean} method could not make that guarantee: conditions are evaluated while
   * configuration classes are being processed, and the chat model definition is contributed by a
   * later bean-definition registrar.
   */
  static void register(BeanDefinitionRegistry registry, ConfigurableListableBeanFactory factory) {
    if (registry.containsBeanDefinition(MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME)
        || factory.containsSingleton(MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME)
        || factory.getBeanNamesForType(ChatClient.Builder.class, true, false).length > 0) {
      // Spring AI's own chat-client auto-configuration, or the application, got there first. Two
      // builders resolving two models is the ambiguity this starter exists to avoid.
      return;
    }

    RootBeanDefinition definition = new RootBeanDefinition(ChatClient.Builder.class);
    definition.setRole(BeanDefinition.ROLE_APPLICATION);
    definition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
    definition.setInstanceSupplier(() -> build(factory));
    registry.registerBeanDefinition(MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME, definition);
  }

  private static ChatClient.Builder build(ConfigurableListableBeanFactory factory) {
    ChatModel model = factory.getBean(ChatModel.class);
    ObservationRegistry observations =
        factory
            .getBeanProvider(ObservationRegistry.class)
            .getIfAvailable(() -> ObservationRegistry.NOOP);
    ChatClient.Builder builder = ChatClient.builder(model, observations, null, null);

    // Customizers are applied here rather than left to the application, because an application
    // that has to build the builder itself is exactly the case where a customizer contributed by
    // some other library gets silently dropped.
    List<ChatClientBuilderCustomizer> customizers =
        new ArrayList<>(factory.getBeanProvider(ChatClientBuilderCustomizer.class).stream().toList());
    AnnotationAwareOrderComparator.sort(customizers);
    customizers.forEach(customizer -> customizer.customize(builder));
    return builder;
  }
}
