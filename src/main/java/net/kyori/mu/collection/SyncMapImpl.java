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
package net.kyori.mu.collection;

import java.util.AbstractMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import static java.util.Objects.requireNonNull;

/* package */ final class SyncMapImpl<K, V> extends AbstractMap<K, V> implements SyncMap<K, V> {
  private final Object lock = new Object();
  private final Function<Integer, Map<K, ExpungingValue<V>>> function;
  private volatile Map<K, ExpungingValue<V>> read;
  private volatile boolean readAmended;
  private int readMisses;
  private Map<K, ExpungingValue<V>> dirty;
  private EntrySet entrySet;

  /* package */ SyncMapImpl(final Function<Integer, Map<K, ExpungingValue<V>>> function, final int initialCapacity) {
    this.function = function;
    this.read = function.apply(initialCapacity);
  }

  @Override
  public int size() {
    if(this.readAmended) {
      synchronized(this.lock) {
        if(this.readAmended) {
          return this.getSize(this.dirty);
        }
      }
    }
    return this.getSize(this.read);
  }

  private int getSize(final @NonNull Map<K, ExpungingValue<V>> map) {
    int size = 0;
    for(ExpungingValue<V> value : map.values()) {
      if(value.exists()) size++;
    }
    return size;
  }

  @Nullable
  private ExpungingValue<V> getValue(final @Nullable Object key) {
    ExpungingValue<V> entry = this.read.get(key);
    if(entry == null && this.readAmended) {
      synchronized(this.lock) {
        if(this.readAmended && (entry = this.read.get(key)) == null && this.dirty != null) {
          entry = this.dirty.get(key);
          this.missLocked();
        }
      }
    }
    return entry;
  }

  @Override
  public boolean containsKey(final @Nullable Object key) {
    final ExpungingValue<V> entry = this.getValue(key);
    return entry != null && entry.exists();
  }

  @Override
  public V get(final @Nullable Object key) {
    final ExpungingValue<V> entry = this.getValue(key);
    if(entry == null) return null;
    return entry.get();
  }

  @Override
  public V put(final @Nullable K key, final @NonNull V value) {
    requireNonNull(value, "value");
    final ExpungingValue<V> entry = this.read.get(key);
    final V previous = entry != null ? entry.get() : null;
    if(entry != null && entry.trySet(value)) {
      return previous;
    }
    return this.putDirty(key, value, false);
  }

  private V putDirty(final @Nullable K key, final @NonNull V value, final boolean ifPresent) {
    ExpungingValue<V> entry;
    V previous = null;
    synchronized(this.lock) {
      if(!ifPresent) {
        entry = this.read.get(key);
        if(entry != null && entry.tryUnexpungeAndSet(value)) {
          // If we had an expunged entry, then this.dirty != null and we need to insert the entry there too.
          this.dirty.put(key, entry);
          return null;
        }
      }
      if(this.dirty != null && (entry = this.dirty.get(key)) != null) {
        previous = entry.set(value);
      } else if(!ifPresent) {
        if(!this.readAmended) {
          this.dirtyLocked();
          this.readAmended = true;
        }
        if(this.dirty != null) {
          this.dirty.put(key, new ExpungingValueImpl<>(value));
          previous = null;
        }
      }
    }
    return previous;
  }

  @Override
  public V remove(final @Nullable Object key) {
    ExpungingValue<V> entry = this.read.get(key);
    if(entry == null && this.readAmended) {
      synchronized(this.lock) {
        if(this.readAmended && (entry = this.read.get(key)) == null && this.dirty != null) {
          entry = this.dirty.remove(key);
        }
      }
    }
    return entry != null ? entry.clear() : null;
  }

  @Override
  public boolean remove(final @Nullable Object key, final @NonNull Object value) {
    requireNonNull(value, "value");
    ExpungingValue<V> entry = this.read.get(key);
    boolean absent = entry == null;
    if(absent && this.readAmended) {
      synchronized(this.lock) {
        if(this.readAmended && (absent = (entry = this.read.get(key)) == null) && this.dirty != null) {
          absent = (entry = this.dirty.get(key)) == null;
          if(!absent && entry.replace(value, null)) {
            this.dirty.remove(key);
            return true;
          }
        }
      }
    }
    if(!absent) entry.replace(value, null);
    return false;
  }

  @Override
  public V putIfAbsent(final @Nullable K key, final @NonNull V value) {
    requireNonNull(value, "value");
    ExpungingValue<V> entry = this.read.get(key);
    if(entry != null) {
      final Map.Entry<Boolean, V> result = entry.putIfAbsent(value);
      if(result.getKey() == Boolean.TRUE) {
        return result.getValue();
      }
    }
    synchronized(this.lock) {
      entry = this.read.get(key);
      if(entry != null && entry.tryUnexpungeAndSet(value)) {
        this.dirty.put(key, entry);
        return null;
      } else if(this.dirty != null && (entry = this.dirty.get(key)) != null) {
        final Map.Entry<Boolean, V> result = entry.putIfAbsent(value);
        this.missLocked();
        return result.getValue();
      } else {
        if(!this.readAmended) {
          this.dirtyLocked();
          this.readAmended = true;
        }
        if (this.dirty != null) {
          this.dirty.put(key, new ExpungingValueImpl<>(value));
        }
      }
      return null;
    }
  }

  @Override
  public V replace(final @Nullable K key, final @NonNull V value) {
    requireNonNull(value, "value");
    final ExpungingValue<V> entry = this.read.get(key);
    final V previous = entry != null ? entry.get() : null;
    if(entry != null && entry.trySet(value)) {
      return previous;
    }
    if(!this.readAmended) return previous;
    return this.putDirty(key, value, true);
  }

  @Override
  public boolean replace(final @Nullable K key, final @NonNull V oldValue, final @NonNull V newValue) {
    requireNonNull(oldValue, "oldValue");
    requireNonNull(newValue, "newValue");
    ExpungingValue<V> entry = this.read.get(key);
    if(entry != null && entry.replace(oldValue, newValue)) {
      return true;
    }
    if(this.readAmended) {
      synchronized(this.lock) {
        if(this.readAmended && this.dirty != null) {
          entry = this.dirty.get(key);
          if(entry.replace(oldValue, newValue)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  public void clear() {
    synchronized(this.lock) {
      this.read.clear();
      this.dirty = null;
      this.readMisses = 0;
      this.readAmended = false;
    }
  }

  @NonNull
  @Override
  public Set<Entry<K, V>> entrySet() {
    if(this.entrySet != null) return this.entrySet;
    return this.entrySet = new EntrySet();
  }

  private void promoteIfNeeded() {
    if(this.readAmended) {
      synchronized(this.lock) {
        if(this.readAmended && this.dirty != null) {
          this.promoteLocked();
        }
      }
    }
  }

  private void promoteLocked() {
    if(this.dirty != null) this.read = this.dirty;
    this.dirty = null;
    this.readMisses = 0;
    this.readAmended = false;
  }

  private void missLocked() {
    this.readMisses++;
    final int length = this.dirty != null ? this.dirty.size() : 0;
    if(this.readMisses > length) {
      this.promoteLocked();
    }
  }

  private void dirtyLocked() {
    if(this.dirty == null) {
      this.dirty = this.function.apply(this.read.size());
      for(final Map.Entry<K, ExpungingValue<V>> entry : this.read.entrySet()) {
        if(!entry.getValue().tryMarkExpunged()) this.dirty.put(entry.getKey(), entry.getValue());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static class ExpungingValueImpl<V> implements SyncMap.ExpungingValue<V> {
    private static final Object EXPUNGED = new Object();
    private static final AtomicReferenceFieldUpdater<ExpungingValueImpl, Object> valueUpdater =
      AtomicReferenceFieldUpdater.newUpdater(ExpungingValueImpl.class, Object.class, "value");
    private volatile Object value;

    private ExpungingValueImpl(final @NonNull V value) {
      this.value = value;
    }

    @Nullable
    @Override
    public V get() {
      final Object value = valueUpdater.get(this);
      if(value == EXPUNGED) return null;
      return (V) value;
    }

    @Override
    public Entry<Boolean, V> putIfAbsent(final @NonNull V value) {
      for(;;) {
        final Object previous = valueUpdater.get(this);
        if(previous == EXPUNGED) {
          return new AbstractMap.SimpleImmutableEntry<>(Boolean.FALSE, null);
        }
        if(previous != null) {
          return new AbstractMap.SimpleImmutableEntry<>(Boolean.TRUE, (V) previous);
        }
        if(valueUpdater.compareAndSet(this, null, value)) {
          return new AbstractMap.SimpleImmutableEntry<>(Boolean.TRUE, null);
        }
      }
    }

    @Override
    public boolean isExpunged() {
      return valueUpdater.get(this) == EXPUNGED;
    }

    @Override
    public boolean exists() {
      final Object value = valueUpdater.get(this);
      return value != null && value != EXPUNGED;
    }

    @Override
    public V set(final @NonNull V value) {
      final Object previous = valueUpdater.getAndSet(this, value);
      return previous == EXPUNGED ? null : (V) previous;
    }

    @Override
    public boolean trySet(final @NonNull V newValue) {
      for(;;) {
        final Object present = valueUpdater.get(this);
        if(present == EXPUNGED) {
          return false;
        }
        if(valueUpdater.compareAndSet(this, present, newValue)) {
          return true;
        }
      }
    }

    @Override
    public boolean tryMarkExpunged() {
      Object value = valueUpdater.get(this);
      while(value == null) {
        if(valueUpdater.compareAndSet(this, null, EXPUNGED)) {
          return true;
        }
        value = valueUpdater.get(this);
      }
      return false;
    }

    @Override
    public boolean tryUnexpungeAndSet(V element) {
      return valueUpdater.compareAndSet(this, EXPUNGED, element);
    }

    @Override
    public boolean replace(final @NonNull Object expected, final @Nullable V newValue) {
      for(;;) {
        final Object value = valueUpdater.get(this);
        if(value == EXPUNGED || !Objects.equals(value, expected)) {
          return false;
        }
        if(valueUpdater.compareAndSet(this, value, newValue)) {
          return true;
        }
      }
    }

    @Nullable
    @Override
    public V clear() {
      for(;;) {
        final Object value = valueUpdater.get(this);
        if(value == null || value == EXPUNGED) {
          return null;
        }
        if(valueUpdater.compareAndSet(this, value, null)) {
          return (V) value;
        }
      }
    }
  }

  private class MapEntry implements Map.Entry<K, V> {
    private final K key;

    private MapEntry(final Map.Entry<K, ExpungingValue<V>> entry) {
      this.key = entry.getKey();
    }

    @Override
    public K getKey() {
      return this.key;
    }

    @Override
    public V getValue() {
      return SyncMapImpl.this.get(this.key);
    }

    @Override
    public V setValue(final @NonNull V value) {
      return SyncMapImpl.this.put(this.key, value);
    }

    @Override
    public String toString() {
      return "SyncMapImpl.MapEntry{key=" + this.getKey() + ", value=" + this.getValue() + "}";
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if(this == other) return true;
      if(!(other instanceof Map.Entry)) return false;
      final Map.Entry<?, ?> that = (Map.Entry<?, ?>) other;
      return Objects.equals(this.getKey(), that.getKey())
        && Objects.equals(this.getValue(), that.getValue());
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.getKey(), this.getValue());
    }
  }

  private class EntrySet extends AbstractSet<Map.Entry<K, V>> {
    @Override
    public int size() {
      return SyncMapImpl.this.size();
    }

    @Override
    public boolean contains(final @Nullable Object entry) {
      if(!(entry instanceof Map.Entry)) return false;
      final Map.Entry<?, ?> mapEntry = (Entry<?, ?>) entry;
      final V value = SyncMapImpl.this.get(mapEntry.getKey());
      return value != null && Objects.equals(mapEntry.getValue(), value);
    }

    @Override
    public boolean remove(final @Nullable Object entry) {
      if(!(entry instanceof Map.Entry)) return false;
      final Map.Entry<?, ?> mapEntry = (Entry<?, ?>) entry;
      return SyncMapImpl.this.remove(mapEntry.getKey()) != null;
    }

    @Override
    public void clear() {
      SyncMapImpl.this.clear();
    }

    @NonNull
    @Override
    public Iterator<Map.Entry<K, V>> iterator() {
      SyncMapImpl.this.promoteIfNeeded();
      return new EntryIterator(SyncMapImpl.this.read.entrySet().iterator());
    }
  }

  private class EntryIterator implements Iterator<Map.Entry<K, V>> {
    private final Iterator<Map.Entry<K, ExpungingValue<V>>> backingIterator;
    private Map.Entry<K, V> next;
    private Map.Entry<K, V> current;

    private EntryIterator(final @NonNull Iterator<Map.Entry<K, ExpungingValue<V>>> backingIterator) {
      this.backingIterator = backingIterator;
      final Map.Entry<K, ExpungingValue<V>> entry = this.getNextValue();
      this.next = (entry != null ? new MapEntry(entry) : null);
    }

    private Map.Entry<K, ExpungingValue<V>> getNextValue() {
      Map.Entry<K, ExpungingValue<V>> entry = null;
      while(this.backingIterator.hasNext() && entry == null) {
        final ExpungingValue<V> value = (entry = this.backingIterator.next()).getValue();
        if(!value.exists()) {
          entry = null;
        }
      }
      return entry;
    }

    @Override
    public boolean hasNext() {
      return this.next != null;
    }

    @Override
    public Map.Entry<K, V> next() {
      this.current = this.next;
      final Map.Entry<K, ExpungingValue<V>> entry = this.getNextValue();
      this.next = (entry != null ? new MapEntry(entry) : null);
      if(this.current == null) throw new NoSuchElementException();
      return this.current;
    }

    @Override
    public void remove() {
      if(this.current == null) return;
      SyncMapImpl.this.remove(this.current.getKey());
    }

    @Override
    public void forEachRemaining(final @NonNull Consumer<? super Map.Entry<K, V>> action) {
      if(this.next != null) action.accept(this.next);
      this.backingIterator.forEachRemaining(entry -> {
        if(entry.getValue().exists()) action.accept(new MapEntry(entry));
      });
    }
  }
}