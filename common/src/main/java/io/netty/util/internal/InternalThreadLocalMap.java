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

package io.netty.util.internal;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 这个 InternalThreadLocalMap 是个内部类, 用于给 FastThreadLocal 和 Netty 存储线程本地变量,
 * 会随版本变化而变化, 不要直接使用, 要用就直接用 FastThreadLocal
 * The internal data structure that stores the thread-local variables for Netty and all {@link FastThreadLocal}s.
 * Note that this class is for internal use only and is subject to change at any time.  Use {@link FastThreadLocal}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap extends UnpaddedInternalThreadLocalMap {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(InternalThreadLocalMap.class);

    /**
     * 默认数组容量
     */
    private static final int DEFAULT_ARRAY_LIST_INITIAL_CAPACITY = 8;
    /**
     * StringBuilder 的初始大小, 默认是 1024
     */
    private static final int STRING_BUILDER_INITIAL_SIZE;
    /**
     * StringBuilder 的最大大小, 默认是 1024 * 4
     */
    private static final int STRING_BUILDER_MAX_SIZE;

    /**
     * 资源未赋值变质量
     */
    public static final Object UNSET = new Object();

    private BitSet cleanerFlags;

    static {
        //静态变量初始化
        STRING_BUILDER_INITIAL_SIZE =
                SystemPropertyUtil.getInt("io.netty.threadLocalMap.stringBuilder.initialSize", 1024);
        logger.debug("-Dio.netty.threadLocalMap.stringBuilder.initialSize: {}", STRING_BUILDER_INITIAL_SIZE);

        STRING_BUILDER_MAX_SIZE = SystemPropertyUtil.getInt("io.netty.threadLocalMap.stringBuilder.maxSize", 1024 * 4);
        logger.debug("-Dio.netty.threadLocalMap.stringBuilder.maxSize: {}", STRING_BUILDER_MAX_SIZE);
    }

    /**
     * 根据线程是否是 FastThreadLocalThread , 来获取不同的 ThreadLocalMap , 这个是没有初始化的, 如果没有值则返回 null
     */
    public static InternalThreadLocalMap getIfSet() {
        //获取当前线程
        Thread thread = Thread.currentThread();
        //如果是 FastThreadLocalThread 类型, 则直接从线程中获取
        if (thread instanceof FastThreadLocalThread) {
            return ((FastThreadLocalThread) thread).threadLocalMap();
        }
        //否则从原生的 ThreadLocal 获取
        return slowThreadLocalMap.get();
    }

    /**
     * 根据线程实例的类型的不同调用不同的方法获取 InternalThreadLocalMap
     * - 这个方法与 getIfSet 的区别是, 如果没有值会初始化一个默认值
     */
    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();
        if (thread instanceof FastThreadLocalThread) {
            return fastGet((FastThreadLocalThread) thread);
        } else {
            return slowGet();
        }
    }

    /**
     * 从 FastThreadLocalThread 获取 InternalThreadLocalMap
     */
    private static InternalThreadLocalMap fastGet(FastThreadLocalThread thread) {
        //获取 InternalThreadLocalMap
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        //如果为 null, 则初始化
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        //返回
        return threadLocalMap;
    }

    /**
     * 从原生的 ThreadLocal 中获取
     */
    private static InternalThreadLocalMap slowGet() {
        //获取线程的 ThreadLocal
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = UnpaddedInternalThreadLocalMap.slowThreadLocalMap;
        //获取 ThreadLocal 中获取值
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        //如果值是空, 则初始化
        if (ret == null) {
            ret = new InternalThreadLocalMap();
            //设置到 ThreadLocal 中
            slowThreadLocalMap.set(ret);
        }
        //返回 InternalThreadLocalMap
        return ret;
    }

    /**
     * 根据线程实例的类型删除 ThreadLocalMap
     */
    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof FastThreadLocalThread) {
            ((FastThreadLocalThread) thread).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    /**
     * 删除原生 ThreadLocal 的 InternalThreadLocalMap
     */
    public static void destroy() {
        slowThreadLocalMap.remove();
    }

    /**
     * 分配 id
     */
    public static int nextVariableIndex() {
        //自增一个 id
        int index = nextIndex.getAndIncrement();
        //如果变负数, 则表示溢出
        if (index < 0) {
            nextIndex.decrementAndGet();
            throw new IllegalStateException("too many thread-local indexed variables");
        }
        //返回
        return index;
    }

    public static int lastVariableIndex() {
        return nextIndex.get() - 1;
    }

    // Cache line padding (must be public)
    // With CompressedOops enabled, an instance of this class should occupy at least 128 bytes.
    public long rp1, rp2, rp3, rp4, rp5, rp6, rp7, rp8, rp9;

    private InternalThreadLocalMap() {
        //创建对象时, 初始化
        super(newIndexedVariableTable());
    }

    /**
     * 初始化数组, 长度为 32, 所有的值都是 UNSET
     */
    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[32];
        Arrays.fill(array, UNSET);
        return array;
    }

    public int size() {
        int count = 0;

        if (futureListenerStackDepth != 0) {
            count ++;
        }
        if (localChannelReaderStackDepth != 0) {
            count ++;
        }
        if (handlerSharableCache != null) {
            count ++;
        }
        if (counterHashCode != null) {
            count ++;
        }
        if (random != null) {
            count ++;
        }
        if (typeParameterMatcherGetCache != null) {
            count ++;
        }
        if (typeParameterMatcherFindCache != null) {
            count ++;
        }
        if (stringBuilder != null) {
            count ++;
        }
        if (charsetEncoderCache != null) {
            count ++;
        }
        if (charsetDecoderCache != null) {
            count ++;
        }
        if (arrayList != null) {
            count ++;
        }

        for (Object o: indexedVariables) {
            if (o != UNSET) {
                count ++;
            }
        }

        //为什么要减少一个呢, 主要是 indexedVariables 数组中第一个是 FastThreadLocal 集合, 不算
        // We should subtract 1 from the count because the first element in 'indexedVariables' is reserved
        // by 'FastThreadLocal' to keep the list of 'FastThreadLocal's to remove on 'FastThreadLocal.removeAll()'.
        return count - 1;
    }

    /**
     * 返回 StringBuilder
     */
    public StringBuilder stringBuilder() {
        //获取变量中的 StringBuilder
        StringBuilder sb = stringBuilder;
        //如果 sb 为 null
        if (sb == null) {
            //根据初始化长度创建
            return stringBuilder = new StringBuilder(STRING_BUILDER_INITIAL_SIZE);
        }
        //如果 sb 容量大于最大值
        if (sb.capacity() > STRING_BUILDER_MAX_SIZE) {
            //设置 sb 的长度为初始值, 并且丢弃其他
            sb.setLength(STRING_BUILDER_INITIAL_SIZE);
            sb.trimToSize();
        }
        //把长度设置为0，相当于清空sb
        sb.setLength(0);
        //返回 sb
        return sb;
    }

    /**
     * 返回 Map<Charset, CharsetEncoder> 没有则创建
     */
    public Map<Charset, CharsetEncoder> charsetEncoderCache() {
        Map<Charset, CharsetEncoder> cache = charsetEncoderCache;
        if (cache == null) {
            charsetEncoderCache = cache = new IdentityHashMap<Charset, CharsetEncoder>();
        }
        return cache;
    }

    /**
     * 返回 Map<Charset, CharsetDecoder> , 没有则创建
     */
    public Map<Charset, CharsetDecoder> charsetDecoderCache() {
        Map<Charset, CharsetDecoder> cache = charsetDecoderCache;
        if (cache == null) {
            charsetDecoderCache = cache = new IdentityHashMap<Charset, CharsetDecoder>();
        }
        return cache;
    }

    /**
     * 返回默认容量的 ArrayList
     */
    public <E> ArrayList<E> arrayList() {
        return arrayList(DEFAULT_ARRAY_LIST_INITIAL_CAPACITY);
    }

    /**
     * 返回指定容量的 ArrayList , 没有则创建
     */
    @SuppressWarnings("unchecked")
    public <E> ArrayList<E> arrayList(int minCapacity) {
        //获取变量中的 arrayList
        ArrayList<E> list = (ArrayList<E>) arrayList;
        //如果为 null
        if (list == null) {
            //指定容量, 创建 ArrayList
            arrayList = new ArrayList<Object>(minCapacity);
            //返回
            return (ArrayList<E>) arrayList;
        }
        //如果原来 arrayList 存则, 则清空
        list.clear();
        //确保容量不比 minCapacity 少
        list.ensureCapacity(minCapacity);
        //返回
        return list;
    }

    public int futureListenerStackDepth() {
        return futureListenerStackDepth;
    }

    public void setFutureListenerStackDepth(int futureListenerStackDepth) {
        this.futureListenerStackDepth = futureListenerStackDepth;
    }

    /**
     * 获取 ThreadLocalRandom 对象, 没有则创建
     */
    public ThreadLocalRandom random() {
        ThreadLocalRandom r = random;
        if (r == null) {
            random = r = new ThreadLocalRandom();
        }
        return r;
    }

    public Map<Class<?>, TypeParameterMatcher> typeParameterMatcherGetCache() {
        Map<Class<?>, TypeParameterMatcher> cache = typeParameterMatcherGetCache;
        if (cache == null) {
            typeParameterMatcherGetCache = cache = new IdentityHashMap<Class<?>, TypeParameterMatcher>();
        }
        return cache;
    }

    public Map<Class<?>, Map<String, TypeParameterMatcher>> typeParameterMatcherFindCache() {
        Map<Class<?>, Map<String, TypeParameterMatcher>> cache = typeParameterMatcherFindCache;
        if (cache == null) {
            typeParameterMatcherFindCache = cache = new IdentityHashMap<Class<?>, Map<String, TypeParameterMatcher>>();
        }
        return cache;
    }

    @Deprecated
    public IntegerHolder counterHashCode() {
        return counterHashCode;
    }

    @Deprecated
    public void setCounterHashCode(IntegerHolder counterHashCode) {
        this.counterHashCode = counterHashCode;
    }

    public Map<Class<?>, Boolean> handlerSharableCache() {
        Map<Class<?>, Boolean> cache = handlerSharableCache;
        if (cache == null) {
            // Start with small capacity to keep memory overhead as low as possible.
            handlerSharableCache = cache = new WeakHashMap<Class<?>, Boolean>(4);
        }
        return cache;
    }

    public int localChannelReaderStackDepth() {
        return localChannelReaderStackDepth;
    }

    public void setLocalChannelReaderStackDepth(int localChannelReaderStackDepth) {
        this.localChannelReaderStackDepth = localChannelReaderStackDepth;
    }

    /**
     * 获取指定下标的值
     */
    public Object indexedVariable(int index) {
        //获取数组
        Object[] lookup = indexedVariables;
        //如果下标小于数组长度, 则获取 index 的值, 否则返回默认值
        return index < lookup.length? lookup[index] : UNSET;
    }

    /**
     * 存储对象
     * -  只有当前 ThreadLocal 的值 value 是第一次设置的才返回 true
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    public boolean setIndexedVariable(int index, Object value) {
        //获取对象数组
        Object[] lookup = indexedVariables;
        //如果下标小于数组长度
        if (index < lookup.length) {
            //获取指定下标旧值
            Object oldValue = lookup[index];
            //设置新值
            lookup[index] = value;
            //判断 oldValue == UNSET 第一次设置
            return oldValue == UNSET;
        } else {
            //如果下标大于数组长度, 则需要扩容处理
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    /**
     * 扩容后赋值
     * - 性能优化
     * - 这是一个算法优化, 用位操作来进行扩容容量计算, 按代码看来, 是扩容为原来的2倍, 这算法与 HashMap 中的 tableSizeFor 相同
     * @param index 值的索引
     * @param value 值
     */
    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;
        newCapacity |= newCapacity >>>  1;
        newCapacity |= newCapacity >>>  2;
        newCapacity |= newCapacity >>>  4;
        newCapacity |= newCapacity >>>  8;
        newCapacity |= newCapacity >>> 16;
        newCapacity ++;

        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }

    /**
     * 移除数组 index 位置元素
     */
    public Object removeIndexedVariable(int index) {
        //获取对象数组
        Object[] lookup = indexedVariables;
        //如果 index 小于数组长度
        if (index < lookup.length) {
            //获取旧值
            Object v = lookup[index];
            //重置为默认值
            lookup[index] = UNSET;
            //返回
            return v;
        } else {
            //超出则返回默认值
            return UNSET;
        }
    }

    /**
     * 判断数组index位置是否设置过值
     */
    public boolean isIndexedVariableSet(int index) {
        Object[] lookup = indexedVariables;
        //在指定位置只要不是 UNSET 都算设置过值
        return index < lookup.length && lookup[index] != UNSET;
    }

    public boolean isCleanerFlagSet(int index) {
        return cleanerFlags != null && cleanerFlags.get(index);
    }

    public void setCleanerFlag(int index) {
        if (cleanerFlags == null) {
            cleanerFlags = new BitSet();
        }
        cleanerFlags.set(index);
    }
}
