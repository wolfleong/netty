/*
 * Copyright 2013 The Netty Project
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

import java.util.Arrays;

/**
 * 默认的 Future 监听回调对象的列表实现, 底层是用数组
 */
final class DefaultFutureListeners {

    /**
     * 数组
     */
    private GenericFutureListener<? extends Future<?>>[] listeners;
    /**
     * Listener 的个数
     */
    private int size;
    /**
     * GenericProgressiveFutureListener 类型监听器的个数
     */
    private int progressiveSize; // the number of progressive listeners

    @SuppressWarnings("unchecked")
    DefaultFutureListeners(
            GenericFutureListener<? extends Future<?>> first, GenericFutureListener<? extends Future<?>> second) {
        listeners = new GenericFutureListener[2];
        listeners[0] = first;
        listeners[1] = second;
        size = 2;
        if (first instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
        if (second instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    /**
     * 添加 Listener, 如果数据容量不够, 则扩容, 并且做相应的统计
     */
    public void add(GenericFutureListener<? extends Future<?>> l) {
        GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        final int size = this.size;
        //如果数组已经满了, 则扩容, 增加2个
        if (size == listeners.length) {
            this.listeners = listeners = Arrays.copyOf(listeners, size << 1);
        }
        listeners[size] = l;
        this.size = size + 1;

        //统计
        if (l instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    /**
     * 删除 Listener
     */
    public void remove(GenericFutureListener<? extends Future<?>> l) {
        //获取数组
        final GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        //获取原来长度
        int size = this.size;
        //迭代
        for (int i = 0; i < size; i ++) {
            //找到要删除的对象
            if (listeners[i] == l) {
                //计算要移动的个数
                int listenersToMove = size - i - 1;
                //如果大于 0
                if (listenersToMove > 0) {
                    //复后面的 Listener 到前面
                    System.arraycopy(listeners, i + 1, listeners, i, listenersToMove);
                }
                //长度减 1, 并设置后面的值为 null
                listeners[-- size] = null;
                //重新保存长度
                this.size = size;

                //如果是 GenericProgressiveFutureListener 类型, 则统计减少 1
                if (l instanceof GenericProgressiveFutureListener) {
                    progressiveSize --;
                }
                return;
            }
        }
    }

    public GenericFutureListener<? extends Future<?>>[] listeners() {
        return listeners;
    }

    public int size() {
        return size;
    }

    public int progressiveSize() {
        return progressiveSize;
    }
}
