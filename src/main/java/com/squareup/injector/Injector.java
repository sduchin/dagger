/**
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.injector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Provider;

/**
 * Dependency injector.
 *
 * <p>The following injection features are supported:
 * <ul>
 *   <li>Field injection. A class may have any number of field injections, and
 *       fields may be of any visibility. Static fields will be injected each
 *       time an instance is injected.
 *   <li>Constructor injection. A class may have a single {@code
 *       @Inject}-annotated constructor. Classes that have fields injected
 *       may omit the {@link @Inject} annotation if they have a public
 *       no-arguments constructor.
 *   <li>Injection of {@code @Provides} method parameters.
 *   <li>{@code @Provides} methods annotated {@code @Singleton}.
 *   <li>Constructor-injected classes annotated {@code @Singleton}.
 *   <li>Injection of {@link Provider}s.
 *   <li>Qualifier annotations on injected parameters and fields.
 *   <li>JSR 330 annotations.
 * </ul>
 *
 * <p>The following injection features are not currently supported:
 * <ul>
 *   <li>Method injection.</li>
 *   <li>Circular dependencies.</li>
 * </ul>
 *
 * @author Jesse Wilson
 */
public final class Injector {
  private static final Object UNINITIALIZED = new Object();

  /** All errors encountered during injection. */
  private final List<String> errors = new ArrayList<String>();

  /** All of the injector's bindings. */
  private final Map<Key<?>, Binding<?>> bindings = new HashMap<Key<?>, Binding<?>>();

  /**
   * Creates an injector defined by {@code modules} and immediately uses it to
   * create an instance of {@code type}. The modules can be of any type, and
   * must contain {@code @Provides} methods.
   */
  public <T> T inject(Class<T> type, Object... modules) {
    return inject(new Key<T>(type, null), modules);
  }

  private <T> T inject(Key<T> key, Object[] modules) {
    if (!bindings.isEmpty()) {
      throw new IllegalStateException("Injectors may only inject once.");
    }

    Map<Key<?>, Binding<?>> combined = Modules.moduleToMap(Modules.combine(modules));
    for (Binding<?> binding : combined.values()) {
      putBinding(binding);
    }

    Linker linker = new Linker(this);
    linker.requestBinding(key, "root injection"); // Seed this requirement early.
    linker.link(bindings.values());

    if (!errors.isEmpty()) {
      StringBuilder message = new StringBuilder();
      message.append("Errors creating injector:");
      for (String error : errors) {
        message.append("\n  ").append(error);
      }
      throw new IllegalArgumentException(message.toString());
    }

    Binding<T> root = linker.requestBinding(key, "root injection");
    return root.get(); // Linker.link() guarantees that this will be non-null.
  }

  @SuppressWarnings("unchecked") // Typesafe heterogeneous container.
  <T> Binding<T> getBinding(Key<T> key) {
    return (Binding<T>) bindings.get(key);
  }

  <T> void putBinding(final Binding<T> binding) {
    Binding<T> toInsert = binding;
    if (binding.isSingleton()) {
      toInsert = new Binding<T>(binding.requiredBy, binding.key) {
        private Object onlyInstance = UNINITIALIZED;
        @Override void attach(Linker linker) {
          binding.attach(linker);
        }
        @Override public void injectMembers(T t) {
          binding.injectMembers(t);
        }
        @Override public T get() {
          if (onlyInstance == UNINITIALIZED) {
            onlyInstance = binding.get();
          }
          return (T) onlyInstance;
        }
        @Override public boolean isSingleton() {
          return binding.isSingleton();
        }
      };
    }

    if (bindings.put(toInsert.key, toInsert) != null) {
      throw new IllegalArgumentException("Duplicate binding: " + toInsert.key);
    }
  }

  void addError(String message) {
    errors.add(message);
  }
}
