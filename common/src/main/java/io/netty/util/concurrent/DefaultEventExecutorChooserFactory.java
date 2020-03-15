/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.internal.UnstableApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * EventExecutorChooserFactory 接口的默认实现, 主要是采用轮询的方式选择 EventExecutor.
 * 性能优化: 在某些场景用位运算替代取模运算以获得更高的性能
 *
 * Default implementation which uses simple round-robin to choose next {@link EventExecutor}.
 */
@UnstableApi
public final class DefaultEventExecutorChooserFactory implements EventExecutorChooserFactory {

    /**
     * 单例模式
     */
    public static final DefaultEventExecutorChooserFactory INSTANCE = new DefaultEventExecutorChooserFactory();

    /**
     * 私有构造器
     */
    private DefaultEventExecutorChooserFactory() { }

    @SuppressWarnings("unchecked")
    @Override
    public EventExecutorChooser newChooser(EventExecutor[] executors) {
        //如果 executors 个数是2的指数
        if (isPowerOfTwo(executors.length)) {
            //返回 PowerOfTwoEventExecutorChooser
            return new PowerOfTwoEventExecutorChooser(executors);
        } else {
            //不是2的指数, 则返回 GenericEventExecutorChooser
            return new GenericEventExecutorChooser(executors);
        }
    }

    /**
     * 主要判断 val 是否是 2 的指数
     * 4 & -4 == 4 , 注意: 计算机低层的数据是用补码来存的, 可以学习一下原码, 反码, 补码的概念
     */
    private static boolean isPowerOfTwo(int val) {
        return (val & -val) == val;
    }

    private static final class PowerOfTwoEventExecutorChooser implements EventExecutorChooser {
        /**
         * 索引增长器
         */
        private final AtomicInteger idx = new AtomicInteger();
        /**
         * EventExecutor 数组
         */
        private final EventExecutor[] executors;

        PowerOfTwoEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        /**
         * 实现比较巧妙，通过 idx 自增，并使用【EventExecutor 数组的大小 - 1】进行进行 & 并操作。
         * 因为 - ( 二元操作符 ) 的计算优先级高于 & ( 一元操作符 ) 。
         * 因为 EventExecutor 数组的大小是以 2 为幂次方的数字，那么减一后，除了最高位是 0 ，剩余位都为 1 ( 例如 8 减一后等于 7 ，而 7 的二进制为 0111 。)，那么无论 idx 无论如何递增，再进行 & 并操作，都不会超过 EventExecutor 数组的大小。并且，还能保证顺序递增。
         */
        @Override
        public EventExecutor next() {
            //增长索引按数据长度减1, 按位(&)运算, 这样做性能更高
            return executors[idx.getAndIncrement() & executors.length - 1];
        }
    }

    private static final class GenericEventExecutorChooser implements EventExecutorChooser {
        /**
         * 索引增长器
         */
        private final AtomicInteger idx = new AtomicInteger();
        /**
         * EventExecutor 数组
         */
        private final EventExecutor[] executors;

        GenericEventExecutorChooser(EventExecutor[] executors) {
            this.executors = executors;
        }

        @Override
        public EventExecutor next() {
            //增长索引按数据长度取模
            return executors[Math.abs(idx.getAndIncrement() % executors.length)];
        }
    }
}
