/*
 * Copyright 2012 The Netty Project
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

package io.netty.buffer;

/**
 * 实现 PoolSubpageMetric 接口，Netty 对 Jemalloc Subpage 的实现类。
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    /**
     * 所属 PoolChunk 对象
     */
    final PoolChunk<T> chunk;
    /**
     * 在 {@link PoolChunk#memoryMap} 的节点编号
     */
    private final int memoryMapIdx;
    /**
     * 在 Chunk 中，偏移字节量
     *
     * @see PoolChunk#runOffset(int)
     */
    private final int runOffset;
    /**
     * Page 大小 {@link PoolChunk#pageSize}
     */
    private final int pageSize;
    /**
     * Subpage 分配信息数组
     *
     * 每个 long 的 bits 位代表一个 Subpage 是否分配。
     * 因为 PoolSubpage 可能会超过 64 个( long 的 bits 位数 )，所以使用数组。
     *   例如：Page 默认大小为 8KB ，Subpage 默认最小为 16 B ，所以一个 Page 最多可包含 8 * 1024 / 16 = 512 个 Subpage 。
     *        因此，bitmap 数组大小为 512 / 64 = 8 。
     * 另外，bitmap 的数组大小，使用 {@link #bitmapLength} 来标记。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
     *    为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
     */
    private final long[] bitmap;

    //这里的链表结构主要是给 PoolArena 中用的
    /**
     * 双向链表，前一个 PoolSubpage 对象
     */
    PoolSubpage<T> prev;
    /**
     * 双向链表，后一个 PoolSubpage 对象
     */
    PoolSubpage<T> next;

    /**
     * 是否未销毁
     */
    boolean doNotDestroy;
    /**
     * 每个 Subpage 的占用内存大小
     */
    int elemSize;
    /**
     * 总共 Subpage 的数量
     */
    private int maxNumElems;
    /**
     * {@link #bitmap} 长度
     */
    private int bitmapLength;
    /**
     * 下一个可分配 Subpage 的数组位置
     */
    private int nextAvail;
    /**
     * 剩余可用 Subpage 的数量
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        //创建头节点用的
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * 真正分配 PoolSubpage 的构造函数
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        //elemSize 要分配的数量

        // 未销毁
        doNotDestroy = true;
        // 初始化 elemSize
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 初始化 maxNumElems
            maxNumElems = numAvail = pageSize / elemSize;
            // 初始化 nextAvail
            nextAvail = 0;
            // 计算 bitmapLength 的大小, 相当于 maxNumElems / 64, 因为一个 long 有 64 位
            bitmapLength = maxNumElems >>> 6;
            // 未整除，补 1.
            if ((maxNumElems & 63) != 0) {
                //加 1, 相当于多 64 位
                bitmapLength ++;
            }

            // 初始化 bitmap
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        // 添加到 Arena 的双向链表中。
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        // 防御性编程，不存在这种情况。
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 可用数量为 0 ，或者已销毁，返回 -1 ，即不可分配。
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获得下一个可用的 Subpage 在 bitmap 中的总体位置
        final int bitmapIdx = getNextAvail();
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置
        int q = bitmapIdx >>> 6;
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置的第几 bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 修改 Subpage 在 bitmap 中不可分配。
        bitmap[q] |= 1L << r;

        // 可用 Subpage 内存块的计数减一
        if (-- numAvail == 0) { // 无可用 Subpage 内存块
            // 从双向链表中移除
            removeFromPool();
        }

        // 计算 handle
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        // 防御性编程，不存在这种情况。
        if (elemSize == 0) {
            return true;
        }
        // 获得 Subpage 在 bitmap 中数组的位置, 相当于 bitmapIdx / 64
        int q = bitmapIdx >>> 6;
        // 获得 Subpage 在 bitmap 中数组的位置的第几 bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        // 修改 Subpage 在 bitmap 中可分配。
        bitmap[q] ^= 1L << r;

        // 设置下一个可用为当前 Subpage
        setNextAvail(bitmapIdx);

        // 可用 Subpage 内存块的计数加一
        if (numAvail ++ == 0) {
            //如果原来是 0, 表示已经满了, 现在释放后有空位可以分配, 所以添加到池子中
            // 添加到 Arena 的双向链表中。
            addToPool(head);
            return true;
        }

        // 还有 Subpage 在使用, 也就是没有满
        if (numAvail != maxNumElems) {
            return true;
        } else {
            // 没有 Subpage 在使用, 也就是空的 Subpage, 则销毁, 并且移除
            // Subpage not in use (numAvail == maxNumElems)
            // 双向链表中，只有该节点，不进行移除
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // 标记为已销毁
            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            // 从双向链表中移除
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        // 将当前节点，插入到 head 和 head.next 中间
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        // 前后节点，互相指向
        prev.next = next;
        next.prev = prev;
        // 当前节点，置空
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        //nextAvail 大于 0 ，意味着已经“缓存”好下一个可用的位置，直接返回即可。
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        //寻找下一个 nextAvail
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        // 循环 bitmap
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            // ~ 操作，如果不等于 0 ，说明有可用的 Subpage
            // 如果 bits 都是1, 那么表示表示都取满了, 取反肯定为 0
            if (~bits != 0) {
                // 在这 bits 寻找可用 nextAvail
                return findNextAvail0(i, bits);
            }
        }
        // 未找到
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 计算基础值，表示在 bitmap 的数组下标
        // 相当于 i * 64
        final int baseVal = i << 6;

        // 遍历 64 位的 bits(long)
        for (int j = 0; j < 64; j ++) {
            // 计算当前 bit 是否未分配
            if ((bits & 1) == 0) {
                // 可能 bitmap 最后一个元素，并没有 64 位，通过 baseVal | j < maxNumElems 来保证不超过上限。
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            // 去掉当前 bit, 也就是取后一位给下一次循环做判断
            bits >>>= 1;
        }
        // 未找到
        return -1;
    }

    /**
     * 低 32 bits ：memoryMapIdx ，可以判断所属 Chunk 的哪个 Page 节点，即 memoryMap[memoryMapIdx] 。
     * 高 32 bits ：bitmapIdx ，可以判断 Page 节点中的哪个 Subpage 的内存块，即 bitmap[bitmapIdx] 。
     * 那么为什么会有 0x4000000000000000L 呢 ???
     * 因为在 PoolChunk#allocate(int normCapacity) 中： 如果分配的是 Page 内存块，返回的是 memoryMapIdx 。 如果分配的是 Subpage 内存块，
     * 返回的是 handle 。但是，如果说 bitmapIdx = 0 ，那么没有 0x4000000000000000L 情况下，就会和【分配 Page 内存块】冲突。
     * 因此，需要有 0x4000000000000000L 。 因为有了 0x4000000000000000L(最高两位为 01 ，其它位为 0 )，所以获取 bitmapIdx 时，
     * 通过 handle >>> 32 & 0x3FFFFFFF 操作。使用 0x3FFFFFFF( 最高两位为 00 ，其它位为 1 ) 进行消除 0x4000000000000000L 带来的影响。
     */
    private long toHandle(int bitmapIdx) {
        //bitmapIdx 表示在long 数组所有位元素排列中的下标
        //0x4000000000000000L表示高1位为1, 低63位为0
        //结果是高2位为01, 中30位为 bitmapIdx, 低 32 位为 memoryMapIdx
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        if (chunk == null) {
            // This is the head so there is no need to synchronize at all as these never change.
            doNotDestroy = true;
            maxNumElems = 0;
            numAvail = 0;
            elemSize = -1;
        } else {
            synchronized (chunk.arena) {
                if (!this.doNotDestroy) {
                    doNotDestroy = false;
                    // Not used for creating the String.
                    maxNumElems = numAvail = elemSize = -1;
                } else {
                    doNotDestroy = true;
                    maxNumElems = this.maxNumElems;
                    numAvail = this.numAvail;
                    elemSize = this.elemSize;
                }
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        if (chunk == null) {
            // It's the head.
            return 0;
        }

        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        if (chunk == null) {
            // It's the head.
            return -1;
        }

        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        //销毁
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
