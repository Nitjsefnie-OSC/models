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

import com.integrallis.models.api.InferenceBackend;
import com.integrallis.models.api.TextGenerationModel;
import com.integrallis.models.spring.ai.ModelsSpringAiChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.embedding.observation.EmbeddingModelMeterObservationHandler;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Spring Boot auto-configuration for the Models Spring AI adapter.
 *
 * <p>The application owns model resolution and contributes either a {@link TextGenerationModel} or
 * an {@link InferenceBackend}. This configuration adapts that runtime to Spring AI without taking a
 * dependency on a model catalog. A high-level {@code TextGenerationModel} takes precedence when
 * both contracts are present.
 *
 * <p>The adapter definition is registered after configuration classes have contributed their bean
 * definitions but before application beans are instantiated. This lets model beans from later
 * auto-configurations participate without leaving a phantom {@code ChatModel} definition when no
 * local runtime exists.
 *
 * <p>When {@code spring-ai-client-chat} is on the classpath, a {@link
 * org.springframework.ai.chat.client.ChatClient.Builder} is registered over that chat model too --
 * see {@link ModelsChatClientRegistrar}. It is optional in both directions: absent that artifact
 * nothing is registered and nothing fails, and an application or another auto-configuration that
 * supplies its own builder keeps it.
 */
@AutoConfiguration
@AutoConfigureAfter(
    name = {
      "org.springframework.ai.model.chat.observation.autoconfigure.ChatObservationAutoConfiguration",
      "org.springframework.ai.model.embedding.observation.autoconfigure.EmbeddingObservationAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
      "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
      "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration",
      "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
      "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
      "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration"
    })
@ConditionalOnClass(name = "org.springframework.ai.chat.model.ChatModel")
@EnableConfigurationProperties(ModelsProperties.class)
public class ModelsAutoConfiguration {

  /** Stable bean name for the auto-configured local Spring AI chat model. */
  public static final String MODELS_CHAT_MODEL_BEAN_NAME = "modelsChatModel";

  /** Stable bean name for the auto-configured Spring AI chat client builder. */
  public static final String MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME =
      ModelsChatClientRegistrar.MODELS_CHAT_CLIENT_BUILDER_BEAN_NAME;

  private static final String CHAT_CLIENT_CLASS = "org.springframework.ai.chat.client.ChatClient";

  private static final String CATALOG_BACKENDS_CLASS =
      "com.integrallis.models.backend.purejava.CatalogBackends";

  private static final String CATALOG_MODEL_PROPERTY = "integrallis.models.catalog-model";

  /**
   * Registers the local Spring AI adapter when a Models runtime definition is present.
   *
   * @return the definition registrar
   */
  @Bean
  static BeanDefinitionRegistryPostProcessor modelsChatModelBeanDefinitionRegistrar() {
    return new ModelsChatModelBeanDefinitionRegistrar();
  }

  @Bean
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnMissingBean(ChatModelMeterObservationHandler.class)
  ChatModelMeterObservationHandler modelsChatModelMeterObservationHandler(
      MeterRegistry meterRegistry) {
    return new ChatModelMeterObservationHandler(meterRegistry);
  }

  @Bean
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnMissingBean(EmbeddingModelMeterObservationHandler.class)
  EmbeddingModelMeterObservationHandler modelsEmbeddingModelMeterObservationHandler(
      MeterRegistry meterRegistry) {
    return new EmbeddingModelMeterObservationHandler(meterRegistry);
  }

  private static final class ModelsChatModelBeanDefinitionRegistrar
      implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
        throws BeansException {
      if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
        throw new IllegalStateException(
            "Models requires a ConfigurableListableBeanFactory, got " + registry.getClass());
      }
      if (registry.containsBeanDefinition(MODELS_CHAT_MODEL_BEAN_NAME)
          || beanFactory.containsSingleton(MODELS_CHAT_MODEL_BEAN_NAME)
          || beanFactory.getBeanNamesForType(ModelsSpringAiChatModel.class, true, false).length
              > 0) {
        return;
      }

      String[] modelNames = beanFactory.getBeanNamesForType(TextGenerationModel.class, true, false);
      String[] backendNames = beanFactory.getBeanNamesForType(InferenceBackend.class, true, false);

      // A model named in configuration is contributed here, before the check below, so the rest of
      // this registrar treats it exactly like one the application wrote in Java. An application
      // bean always wins: configuration is the convenience, not an override.
      if (modelNames.length == 0 && backendNames.length == 0) {
        String catalogModel = catalogModel(beanFactory);
        if (catalogModel != null
            && ClassUtils.isPresent(CATALOG_BACKENDS_CLASS, beanFactory.getBeanClassLoader())) {
          ModelsCatalogModelRegistrar.register(registry, beanFactory, catalogModel);
          backendNames = new String[] {ModelsCatalogModelRegistrar.MODELS_CATALOG_BACKEND_BEAN_NAME};
        }
      }

      if (modelNames.length == 0 && backendNames.length == 0) {
        return;
      }

      RootBeanDefinition definition = new RootBeanDefinition(ModelsSpringAiChatModel.class);
      definition.setRole(BeanDefinition.ROLE_APPLICATION);
      definition.setInstanceSupplier(
          () -> {
            ModelsProperties properties = beanFactory.getBean(ModelsProperties.class);
            ObservationRegistry observations =
                beanFactory
                    .getBeanProvider(ObservationRegistry.class)
                    .getIfAvailable(() -> ObservationRegistry.NOOP);
            TextGenerationModel model =
                beanFactory.getBeanProvider(TextGenerationModel.class).getIfAvailable();
            if (model != null) {
              return new ModelsSpringAiChatModel(
                  model,
                  properties.parsedChatTemplate(),
                  properties.samplingOptions(),
                  observations);
            }
            InferenceBackend backend =
                beanFactory.getBeanProvider(InferenceBackend.class).getIfAvailable();
            if (backend == null) {
              throw new IllegalStateException(
                  "A Models runtime bean disappeared while creating "
                      + MODELS_CHAT_MODEL_BEAN_NAME);
            }
            return new ModelsSpringAiChatModel(
                backend,
                properties.parsedChatTemplate(),
                properties.samplingOptions(),
                observations);
          });
      registry.registerBeanDefinition(MODELS_CHAT_MODEL_BEAN_NAME, definition);

      // Immediately after, and only now that a chat model definitely exists. The classpath check
      // guards the class load, not just the call: ModelsChatClientRegistrar references ChatClient,
      // so touching it at all without spring-ai-client-chat would throw NoClassDefFoundError.
      if (ClassUtils.isPresent(CHAT_CLIENT_CLASS, beanFactory.getBeanClassLoader())) {
        ModelsChatClientRegistrar.register(registry, beanFactory);
      }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
        throws BeansException {}

    /**
     * Reads the catalog id from the environment rather than from the bound properties bean.
     *
     * <p>The decision has to be made while definitions are being contributed, and asking for
     * {@link ModelsProperties} here would force that bean to be created before the application's
     * own configuration classes have finished -- so the value is read through {@link Binder}, which
     * applies the same relaxed binding the properties bean would.
     */
    private static String catalogModel(ConfigurableListableBeanFactory beanFactory) {
      Environment environment =
          beanFactory.getBeanProvider(Environment.class).getIfAvailable();
      if (environment == null) {
        return null;
      }
      String configured =
          Binder.get(environment).bind(CATALOG_MODEL_PROPERTY, String.class).orElse(null);
      return configured == null || configured.isBlank() ? null : configured.strip();
    }
  }
}
