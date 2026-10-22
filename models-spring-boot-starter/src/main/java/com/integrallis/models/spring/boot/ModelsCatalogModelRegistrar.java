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
import com.integrallis.models.backend.purejava.CatalogBackends;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Opens the model named by {@code integrallis.models.catalog-model}.
 *
 * <p>In its own file, and reached only from behind a classpath check, for the same reason as {@link
 * ModelsChatClientRegistrar}: it references the engine, and an application that supplies its own
 * model bean must not be made to carry a decoder.
 *
 * <p><b>What this closes.</b> The catalog has always known which models are installed, where, and
 * what each was measured at — and opening one still meant writing Java that named a file the
 * catalog already knew. One property now does it, and the artifact is still a pinned build
 * dependency rather than a runtime download.
 *
 * <p><b>It is a catalog id, not a path and not a coordinate.</b> Resolution goes through {@link
 * CatalogBackends}, so the value has to be an id some installed catalog reports. An id two catalogs
 * both hold is refused rather than resolved, and an id that is described but whose weights are not
 * on disk is refused with that distinction — neither is guessed at, because a property that
 * silently picked one of two artifacts would be unfalsifiable from the outside.
 */
final class ModelsCatalogModelRegistrar {

  /** Stable bean name for the backend opened from the catalog. */
  static final String MODELS_CATALOG_BACKEND_BEAN_NAME = "modelsCatalogBackend";

  private ModelsCatalogModelRegistrar() {}

  /**
   * Registers a backend definition for {@code catalogModel}.
   *
   * <p>The open is deferred to the instance supplier, so a misconfigured id fails when the context
   * creates the bean rather than while definitions are still being contributed — which is where
   * Spring can attribute the failure to this property.
   */
  static void register(
      BeanDefinitionRegistry registry, ConfigurableListableBeanFactory factory, String catalogModel) {
    RootBeanDefinition definition = new RootBeanDefinition(InferenceBackend.class);
    definition.setRole(BeanDefinition.ROLE_APPLICATION);
    definition.setInstanceSupplier(() -> CatalogBackends.open(catalogModel));
    // The backend holds mapped weights and native resources; the context owns its lifecycle now.
    definition.setDestroyMethodName("close");
    registry.registerBeanDefinition(MODELS_CATALOG_BACKEND_BEAN_NAME, definition);
  }
}
