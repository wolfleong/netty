/*
 * Copyright 2015 The Netty Project
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

/**
 * 优先级队列节点接口
 *
 * 性能优化
 *  - 这里两个方法, 主要维护节点在优先级队列的索引位置, 跟 ScheduledThreadPoolExecutor 里自定义优先级队列的处理是一样的
 *  - 这个优化主要是为了快速删除任务, 如果没有这个索引, 需要先遍历寻找到节点
 * Provides methods for {@link DefaultPriorityQueue} to maintain internal state. These methods should generally not be
 * used outside the scope of {@link DefaultPriorityQueue}.
 */
public interface PriorityQueueNode {
    /**
     * 索引不在队列的值
     * This should be used to initialize the storage returned by {@link #priorityQueueIndex(DefaultPriorityQueue)}.
     */
    int INDEX_NOT_IN_QUEUE = -1;

    /**
     * 获取队列中的索引
     * Get the last value set by {@link #priorityQueueIndex(DefaultPriorityQueue, int)} for the value corresponding to
     * {@code queue}.
     * <p>
     * Throwing exceptions from this method will result in undefined behavior.
     */
    int priorityQueueIndex(DefaultPriorityQueue<?> queue);

    /**
     * 设置队列中的索引
     * Used by {@link DefaultPriorityQueue} to maintain state for an element in the queue.
     * <p>
     * Throwing exceptions from this method will result in undefined behavior.
     * @param queue The queue for which the index is being set.
     * @param i The index as used by {@link DefaultPriorityQueue}.
     */
    void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i);
}
