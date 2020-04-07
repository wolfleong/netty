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
package io.netty.channel.nio;

import sun.nio.ch.SelectorImpl;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 继承 AbstractSet 抽象类，已 select 的 NIO SelectionKey 集合, 用于优化 Selector.
 *
 * 性能优化
 *  - 原来的 Selector 是用 HashSet 来实现的, 可以看 {@link SelectorImpl} 的实现
 *  - 直接数组实现的 Set 比 HashMap 的性能好
 */
final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> {

    /**
     * SelectionKey 数组
     */
    SelectionKey[] keys;
    /**
     * SelectKey 的个数
     */
    int size;

    SelectedSelectionKeySet() {
        // 默认 1024 大小
        keys = new SelectionKey[1024];
    }

    @Override
    public boolean add(SelectionKey o) {
        //元素为 null 则返回 false
        if (o == null) {
            return false;
        }

        // 添加到数组
        keys[size++] = o;
        //容量不够, 扩容来凑
        if (size == keys.length) {
            increaseCapacity();
        }

        return true;
    }

    @Override
    public boolean remove(Object o) {
        //不能删除
        return false;
    }

    @Override
    public boolean contains(Object o) {
        //不能查询是否包含
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<SelectionKey> iterator() {
        return new Iterator<SelectionKey>() {
            private int idx;

            @Override
            public boolean hasNext() {
                return idx < size;
            }

            @Override
            public SelectionKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return keys[idx++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    void reset() {
        //全部重置
        reset(0);
    }

    /**
     * 从指定索引 start 开始, 重置 size 个元素为 null
     */
    void reset(int start) {
        Arrays.fill(keys, start, size, null);
        size = 0;
    }

    /**
     * 扩容
     */
    private void increaseCapacity() {
        //容量变成原来的 2 倍
        SelectionKey[] newKeys = new SelectionKey[keys.length << 1];
        System.arraycopy(keys, 0, newKeys, 0, size);
        keys = newKeys;
    }
}
