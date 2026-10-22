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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.ClassUtils;

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

  private static final System.Logger LOGGER =
      System.getLogger(ModelsChatClientRegistrar.class.getName());

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

  /**
   * The customizer interfaces this starter applies, newest first.
   *
   * <p>Spring AI renamed this type in 2.0: {@code ChatClientCustomizer} is the only one that exists
   * in 1.1, and in 2.0 it still exists but is deprecated for removal in favour of {@code
   * ChatClientBuilderCustomizer}. Both carry the same {@code customize(ChatClient.Builder)} method.
   */
  private static final List<String> CUSTOMIZER_TYPES =
      List.of(
          "org.springframework.ai.chat.client.ChatClientBuilderCustomizer",
          "org.springframework.ai.chat.client.ChatClientCustomizer");

  /**
   * Applies every customizer bean of either interface, in {@code @Order}, each bean once.
   *
   * <p><b>Reflective for both types, and that is not fastidiousness.</b> This starter compiles and
   * is CI-tested against Spring AI 1.1.4, 1.1.8 and 2.0.0. {@code ChatClientBuilderCustomizer} does
   * not exist in 1.1, so importing it fails that compile; {@code ChatClientCustomizer} exists in
   * 2.0 but is deprecated for removal, and this build uses {@code -Werror}, so importing that fails
   * the 2.0 compile. Neither type can be named at compile time across the matrix, and moving the
   * import to another class would not help — the compile is what fails, wherever it lives. Both
   * were tried, in that order, before this.
   *
   * <p>A bean implementing both interfaces is applied once: on 2.0 the two bean sets overlap, and
   * customizing twice would double whatever the customizer does.
   *
   * <p>A failure is logged and swallowed. A customizer is an optional contribution, and refusing to
   * produce a usable builder because an unrelated library's bean could not be invoked is worse.
   */
  private static void applyCustomizers(
      ConfigurableListableBeanFactory factory, ChatClient.Builder builder) {
    List<Object> ordered = new ArrayList<>();
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (String name : CUSTOMIZER_TYPES) {
      Class<?> type;
      try {
        type = ClassUtils.forName(name, factory.getBeanClassLoader());
      } catch (ClassNotFoundException | LinkageError absent) {
        continue;
      }
      for (Object bean : factory.getBeanProvider(type).stream().toList()) {
        if (seen.add(bean)) {
          ordered.add(bean);
        }
      }
    }
    AnnotationAwareOrderComparator.sort(ordered);
    for (Object bean : ordered) {
      try {
        bean.getClass().getMethod("customize", ChatClient.Builder.class).invoke(bean, builder);
      } catch (ReflectiveOperationException | RuntimeException failure) {
        LOGGER.log(
            System.Logger.Level.WARNING,
            "a ChatClient builder customizer could not be applied: {0}",
            failure.toString());
      }
    }
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
    applyCustomizers(factory, builder);
    return builder;
  }
}
