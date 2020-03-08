/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 性能优化:
 *  - 通过下标直接访问替代ThreadLocal通过哈希和哈希表, 定位更快
 *  - FastThreadLocal利用字节填充来解决伪共享问题
 *  - FastThreadLocal利用 FastThreadLocalRunnable 解决可能的内存泄漏问题
 *
 * 缺点:
 * - 空间换时间, 数组长度随着 index 的增长而增长
 * - 必须使用 FastThreadLocalThread 性能才好
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * </p>
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 */
public class FastThreadLocal<V> {

    /**
     * 用于存储 FastThreadLocal 集合的下标
     */
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    /**
     * 删除当前线程所绑定的全部值
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     */
    public static void removeAll() {
        //获取 InternalThreadLocalMap
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        //如果是 null, 则直接返回
        if (threadLocalMap == null) {
            return;
        }

        try {
            //获取当前线程的所有 FastThreadLocal 集合
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            //如果不是默认值
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                //强转
                @SuppressWarnings("unchecked")
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                //变数组
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[0]);
                //遍历
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    //删除 InternalThreadLocalMap 中的值
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            //删除 InternalThreadLocalMap
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * 获取缓存的
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    /**
     * 添加一个 FastThreadLocal 到 variablesToRemove 集合中
     */
    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        //通过variablesToRemoveIndex的固定值，获取threadLocalMap成员变量indexedVariables[variablesToRemoveIndex]位置的对象
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<FastThreadLocal<?>> variablesToRemove;
        //如果获取到的 v 是 UNSET 或 null, 则创建
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            //创建以 IdentityHashMap 为基础的 Set 集合
            // IdentityHashMap 的值是否相等是以对象引用是否相等来判断的, 并不是 HashMap 的 equals 和 hashCode
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            //缓存到 InternalThreadLocalMap 中
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            //如果原来有值, 则强转
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }

        //添加到 variablesToRemove 中
        variablesToRemove.add(variable);
    }

    /**
     * 从 variablesToRemove 集合中删除一个 FastThreadLocal
     */
    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        //获取 variablesToRemove 集合
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        //如果没有 variablesToRemove , 则直接返回
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        //强转
        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        //删除对应的 FastThreadLocal
        variablesToRemove.remove(variable);
    }

    /**
     * 当前对象在 InternalThreadLocalMap 中的数组的下标
     */
    private final int index;

    /**
     * 默认构造
     */
    public FastThreadLocal() {
        //获取唯一标识
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * 获取值, 如果没有值则调用初始化方法设置值, 再将值返回
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        //获取当前线程下的 InternalThreadLocalMap
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        //然后根据 index 获取值
        Object v = threadLocalMap.indexedVariable(index);
        //如果值不是 UNSET , 则强转反回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        //如果没有值, 则初始化
        return initialize(threadLocalMap);
    }

    /**
     * 获取值, 如果存在, 则直接返回, 不存在则返回 null
     * Returns the current value for the current thread if it exists, {@code null} otherwise.
     */
    @SuppressWarnings("unchecked")
    public final V getIfExists() {
        //获取 InternalThreadLocalMap
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        //如果 InternalThreadLocalMap 不为 null
        if (threadLocalMap != null) {
            //用当前索引获取值
            Object v = threadLocalMap.indexedVariable(index);
            //如果值不是默认值, 则强转返回
            if (v != InternalThreadLocalMap.UNSET) {
                return (V) v;
            }
        }
        //没有则返回 null
        return null;
    }

    /**
     * 在指定 InternalThreadLocalMap 上获取值
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        //从指定索引获取值
        Object v = threadLocalMap.indexedVariable(index);
        //如果值不是默认值, 则强转返回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        //没有则调用初始化方法生成值返回
        return initialize(threadLocalMap);
    }

    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            //调用初始化方法
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        //设置值到指定索引的位置
        threadLocalMap.setIndexedVariable(index, v);
        //添加当前 FastThreadLocal 到 variablesToRemove 集合中
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * 设置值到当前线程中
     * Set the value for the current thread.
     */
    public final void set(V value) {
        //如果 value 不是 UNSET
        if (value != InternalThreadLocalMap.UNSET) {
            //获取当前线程缓存的 InternalThreadLocalMap
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            //设置值到 Map 中
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove();
        }
    }

    /**
     * 指定 InternalThreadLocalMap 设置值
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        //如果值不是默认值
        if (value != InternalThreadLocalMap.UNSET) {
            //设置
            setKnownNotUnset(threadLocalMap, value);
        } else {
            //如果是默认值, 则删除当前 FastThreadLocal 的值
            remove(threadLocalMap);
        }
    }

    /**
     * 设置非 Unset 的值
     * @return see {@link InternalThreadLocalMap#setIndexedVariable(int, Object)}.
     */
    private void setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {
        //添加值到 Map 中, 如果添加成功
        if (threadLocalMap.setIndexedVariable(index, value)) {
            addToVariablesToRemove(threadLocalMap, this);
        }
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        //如果 InternalThreadLocalMap 为 null, 则直接返回
        if (threadLocalMap == null) {
            return;
        }

        //删除指定索引的值
        Object v = threadLocalMap.removeIndexedVariable(index);
        //删除 variablesToRemove 里, 当前的 FastThreadLocal
        removeFromVariablesToRemove(threadLocalMap, this);

        //如果旧值不为默认值
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                //删除时, 回调 onRemoval()
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}. Be aware that {@link #remove()}
     * is not guaranteed to be called when the `Thread` completes which means you can not depend on this for
     * cleanup of the resources in the case of `Thread` completion.
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
