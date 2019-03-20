/*
 * This file is part of mu, licensed under the MIT License.
 *
 * Copyright (c) 2018-2019 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.mu.reflect;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Method;

/**
 * A collection of utilities for working with methods.
 */
public interface Methods {
  /**
   * Gets a method.
   *
   * @param klass the class to look in
   * @param method the method to search for
   * @return the found method
   */
  static @Nullable Method get(final @NonNull Class<?> klass, final @NonNull Method method) {
    return get(klass, method.getName(), method.getParameterTypes());
  }

  /**
   * Gets a method.
   *
   * @param klass the class to look in
   * @param name the name of the method to search for
   * @param parameterTypes the parameter types of the method to search for
   * @return the found method
   */
  static @Nullable Method get(final @NonNull Class<?> klass, final @NonNull String name, final @Nullable Class<?>... parameterTypes) {
    try {
      return klass.getMethod(name, parameterTypes);
    } catch(final NoSuchMethodException e) {
      return null;
    }
  }

  /**
   * Gets a declared method.
   *
   * @param klass the class to look in
   * @param method the method to search for
   * @return the found method
   */
  static @Nullable Method getDeclared(final @NonNull Class<?> klass, final @NonNull Method method) {
    return getDeclared(klass, method.getName(), method.getParameterTypes());
  }

  /**
   * Gets a declared method.
   *
   * @param klass the class to look in
   * @param name the name of the method to search for
   * @param parameterTypes the parameter types of the method to search for
   * @return the found method
   */
  static @Nullable Method getDeclared(final @NonNull Class<?> klass, final @NonNull String name, final @Nullable Class<?>... parameterTypes) {
    try {
      return klass.getDeclaredMethod(name, parameterTypes);
    } catch(final NoSuchMethodException e) {
      return null;
    }
  }
}