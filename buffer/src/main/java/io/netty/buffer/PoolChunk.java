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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * 实现 PoolChunkMetric 接口，Netty 对 Jemalloc Chunk 的实现类
 */
final class PoolChunk<T> implements PoolChunkMetric {

    /**
     * 32 - 1 = 31
     */
    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /**
     * 所属 Arena 对象
     */
    final PoolArena<T> arena;
    /**
     * 内存空间。
     */
    final T memory;
    /**
     * 是否非池化
     */
    final boolean unpooled;
    /**
     * 内存的偏移量
     */
    final int offset;
    /**
     * 分配信息满二叉树
     * index 为节点编号
     *
     * 可以这么理解这个值, 它记录着最高可分配的深度, 默认是当前深度可完全分配, 如果当前节点的子节点被分配了一部分, 则为子节点的最小深度
     *
     * memoryMap 数组的值，总结为 3 种情况：
     * 1、该节点没有被分配: memoryMap[id] = depthMap[id]。
     *
     * 2、该节点被分配了一部分: memoryMap[id] 为子节点的较小的值, 也就是 depthMap[id] < memoryMap[id] <= 最大高度(11)
     *    - 最大高度 >= memoryMap[id] > depthMap[id] ，至少有一个子节点被分配，不能再分配该高度满足的内存，但可以根据实际分配较小一些的内存。
     *    比如，上图中父节点 2 分配了子节点 4，值从 1 更新为 2，表示该节点不能再分配 8MB 的只能最大分配 4MB 内存，即只剩下节点 5 可用。
     *
     * 3、memoryMap[id] = 最大高度 + 1 ，该节点及其子节点已被完全分配，没有剩余空间。
     */
    private final byte[] memoryMap;
    /**
     * 高度信息满二叉树
     *
     * index 为节点编号
     */
    private final byte[] depthMap;
    /**
     * PoolSubpage 数组, 这个数组的顺序是对应着 Page 的位置, 每个 Chunk 默认有 2048 个 Page , 也就是默认最多有 2048 个 PoolSubpage 链表
     */
    private final PoolSubpage<T>[] subpages;
    /**
     * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
     *
     * subpageOverflowMask 属性，判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。默认，-8192 。
     * 对于 -8192 的二进制，除了首 bits 为 1 ，其它都为 0 。这样，对于小于 8K 字节的申请，求 subpageOverflowMask & length 都等于 0 ；
     * 对于大于 8K 字节的申请，求 subpageOverflowMask & length 都不等于 0 。相当于说，做了 if ( length < pageSize ) 的计算优化。
     *
     */
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    /**
     * 1 << 13
     * Page 大小，默认 8KB = 8192 B
     */
    private final int pageSize;
    /**
     * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192 。
     *
     * 具体用途，见 {@link #allocateRun(int)} 方法，计算指定容量所在满二叉树的层级。
     */
    private final int pageShifts;
    /**
     * 满二叉树的高度。默认为 11 。
     */
    private final int maxOrder;
    /**
     * Chunk 内存块占用大小。默认为 16M = 16 * 1024 * 1024 。
     */
    private final int chunkSize;
    /**
     * log2 {@link #chunkSize} 的结果。默认为 log2( 16M ) = 24 。也就是 16M 的二进制位数
     */
    private final int log2ChunkSize;
    /**
     * 可分配 {@link #subpages} 的数量，即数组大小。默认为 1 << maxOrder 相当于 1 << 11 = 2048 。
     */
    private final int maxSubpageAllocs;
    //标记节点不可用。默认为 maxOrder + 1 = 12 。
    /** Used to mark memory as unusable */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    /**
     * 剩余可用字节数
     */
    private int freeBytes;

    /**
     * 所属 PoolChunkList 对象
     */
    PoolChunkList<T> parent;
    /**
     * 上一个 Chunk 对象
     */
    PoolChunk<T> prev;
    /**
     * 下一个 Chunk 对象
     */
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        //池化
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        //默认为 11
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        // ~( 1 << 13 - 1), 也就是高19位都是1, 低13位都为0
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        // 1 << 11 = 2048
        maxSubpageAllocs = 1 << maxOrder;

        // 初始化 memoryMap 和 depthMap
        // Generate the memory map.
        //满二叉树, 刚好叶子节点等于所有非叶子节点
        //2048 << 1 = 4096
        memoryMap = new byte[maxSubpageAllocs << 1];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                //设置深度
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        // 初始化 subpages
        subpages = newSubpageArray(maxSubpageAllocs);
        //初始化 , 默认长度为 8 的双端队列
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        //非池化
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        //计算 chunkSize 的二进制位数
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        // 全部使用，100%
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        // 部分使用，最高 99%
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 分配内存空间。
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        // 大于等于 Page(8K) 大小，分配 Page 内存块
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            handle =  allocateRun(normCapacity);
        } else {
            // 小于 Page 大小，分配 Subpage 内存块
            handle = allocateSubpage(normCapacity);
        }

        //分配失败, 返回 false
        if (handle < 0) {
            return false;
        }

        //先从缓存中获取一个 ByteBuffer, 没有则返回 null
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        //将分配的内存初始化到 Buffer
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        //如果给定索引位置不是根节点
        while (id > 1) {
            //获取上一层的索引
            int parentId = id >>> 1;
            //获到当前位置的左值
            byte val1 = value(id);
            //获取当前位置的右值
            byte val2 = value(id ^ 1);
            //取最小的值
            byte val = val1 < val2 ? val1 : val2;
            //设置到父层级中
            setValue(parentId, val);
            //往上遍历
            id = parentId;
        }
    }

    /**
     * 更新 Page 对应的节点的祖先可用
     *
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        // 获得当前节点的子节点的层级
        int logChild = depth(id) + 1;
        //如果不是根节点
        while (id > 1) {
            //获取父节点的下标
            int parentId = id >>> 1;
            //右子节点值
            byte val1 = value(id);
            //左节点的值
            byte val2 = value(id ^ 1);
            // 获得当前节点的层级
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            // 两个子节点都可用，则直接设置父节点的层级
            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                // 两个子节点任一不可用，则取子节点较小值，并设置到父节点
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            // 跳到父节点
            id = parentId;
        }
    }

    /**
     * 分配节点。
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        //id 是数组下标, val 是数组对应的值
        int id = 1;
        //这样处理相当于, 高(32 - d)位都为 1, 低 d 位为 0, 如: 当 d 为2时, initial 为 11111111111111111111111111111100
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        // 获得根节点的指值。
        // 如果根节点的值，大于 d ，说明，第 d 层没有符合的节点，也就是说 [0, d-1] 层也没有符合的节点。即，当前 Chunk 没有符合的节点。
        byte val = value(id);
        if (val > d) { // unusable
            return -1;
        }
        // 获得第 d 层，匹配的节点。
        // id & initial 来保证，高度小于 d 会继续循环
        // val >=d 有两种情况, id 这个位置已经被分配了部分或 id 这个位置就是 d 层级
        // (id & initial) == 0 表示当前位置的索引是小于 d 层的最小索引, 所以要继续往下走
        // (id & initial) == 0 相当于 id < (1 << d), 也就是没有到 d 层级的值
        // 1 << d 表示 d 层的最小索引, 如果当前节点(id)已经被分配了一部分, 剩下可分配的层数是 d, 也就是 val 为 d, 那么 id & initial 肯定为 0
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            // 进入下一层
            // 获得左节点的编号
            id <<= 1; //相当于 id = id * 2
            // 获得左节点的值
            val = value(id);
            // 如果值大于 d ，说明，以左节点作为根节点形成虚拟的虚拟满二叉树，没有符合的节点。
            if (val > d) {
                // 获得右节点的编号
                id ^= 1; //相当于 id = id + 1
                // 获得右节点的值
                val = value(id);
            }
        }
        // 校验获得的节点值合理
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        // 更新获得的节点不可用
        setValue(id, unusable); // mark as unusable
        // 更新获得的节点的祖先节点变成部分分配
        updateParentsAlloc(id);
        return id;
    }

    /**
     * 分配 Page 内存块。
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        //
        //能进来方法表示 normCapacity >= 8192, 8192 相当于 1 << 13 , 而 log2(8192) = 13,   那么 log2(normCapacity) >= 13
        //默认 pageShifts 为 13 , 那么也就是 log2(normCapacity) - pageShifts >= 0
        //log2(normCapacity) - pageShifts 相当于 normCapacity / pageSize 的计算优化, 这个就表示满二叉树的层级
        //maxOrder 默认为 11, 每个块的大小默认是 8192B 也就是 8k
        // 获得层级, 因为层级是反的, 所以要 maxOrder 减
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        // 获得节点
        int id = allocateNode(d);
        // 未获得到节点，直接返回
        if (id < 0) {
            return id;
        }
        //成功获得节点
        // 减少剩余可用字节数
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * 分配 Subpage 内存块。
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 获得对应内存规格的 Subpage 双向链表的 head 节点
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况。
        synchronized (head) {
            // 获得最底层的一个节点。Subpage 只能使用二叉树的最底层的节点。
            // id 默认的范围 [2048 , 4095]
            int id = allocateNode(d);
            // 获取失败，直接返回
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            // 减少剩余可用字节数
            freeBytes -= pageSize;

            // 获得节点对应的 subpages 数组的编号
            int subpageIdx = subpageIdx(id);
            // 获得节点对应的 subpages 数组的 PoolSubpage 对象
            PoolSubpage<T> subpage = subpages[subpageIdx];
            // 初始化 PoolSubpage 对象
            if (subpage == null) {
                // 不存在，则进行创建 PoolSubpage 对象
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                //如果已经存在, 则表示这个 subpage 已经被回收过了
                // 存在，则重新初始化 PoolSubpage 对象
                subpage.init(head, normCapacity);
            }
            // 分配 PoolSubpage 内存块
            return subpage.allocate();
        }
    }

    /**
     * 释放指定位置的内存块。根据情况，内存块可能是 SubPage ，也可能是 Page ，也可能是释放 SubPage 并且释放对应的 Page 。
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        // 获得 memoryMap 数组的编号( 下标 )
        int memoryMapIdx = memoryMapIdx(handle);
        // 获得 bitmap 数组的编号( 下标 )。注意，此时获得的还不是真正的 bitmapIdx 值，需要经过 `bitmapIdx & 0x3FFFFFFF` 运算。
        int bitmapIdx = bitmapIdx(handle);

        // 释放 Subpage
        if (bitmapIdx != 0) { // free a subpage
            // 获得 PoolSubpage 对象
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            // 获得对应内存规格的 Subpage 双向链表的 head 节点
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况。
            synchronized (head) {
                // 释放 Subpage 。
                // 0x3FFFFFFF 相当于 (1 << 30) - 1, 也就是高 34 位都为 0, 低 30 都为 1
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
                //返回 false ，说明 Page 中无切分正在使用的 Subpage 内存块，所以可以继续向下执行，释放 Page
            }
        }
        // 释放 Page

        // 增加剩余可用字节数
        freeBytes += runLength(memoryMapIdx);
        // 设置 Page 对应的节点可用
        setValue(memoryMapIdx, depth(memoryMapIdx));
        // 更新 Page 对应的节点的祖先可用
        updateParentsFree(memoryMapIdx);

        //回收 nioBuffer
        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    /**
     * 将内存地址初始化到 ByteBuf 中
     */
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        // 获得 memoryMap 数组的编号( 下标 )
        int memoryMapIdx = memoryMapIdx(handle);
        // 获得 bitmap 数组的编号( 下标 )。注意，此时获得的还不是真正的 bitmapIdx 值，需要经过 `bitmapIdx & 0x3FFFFFFF` 运算。
        int bitmapIdx = bitmapIdx(handle);
        // 内存块为 Page
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            // 初始化 Page 内存块到 PooledByteBuf 中
            //runOffset(memoryMapIdx) + offset 代码块，计算 Page 内存块在 memory 中的开始位置
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            // 内存块为 SubPage
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        // 获得 memoryMap 数组的编号( 下标 )
        int memoryMapIdx = memoryMapIdx(handle);

        // 获得 SubPage 对象
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        // 初始化 SubPage 内存块到 PooledByteBuf 中
        //runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset 代码块，计算 SubPage 内存块在 memory 中的开始位置
        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    /**
     * 获取 val 最高位的二进制位数, 如 2 就是 2, 4 就是 3, 16就是 5
     */
    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    /**
     * id 下标所代表的内存大小
     */
    private int runLength(int id) {
        // (11 - depth(id)) 相当于 id 位置所代表的倍数, 再加上每块的倍数
        //log2ChunkSize - depth(id) 相当于 13 + 11 - depth(id)
        // 1 << 13 = 8192
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    /**
     * 获得节点对应的 subpages 数组的编号
     */
    private int subpageIdx(int memoryMapIdx) {
        //去掉最高位( bit )。例如节点 2048 计算后的结果为 0
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    /**
     * 取 handle 的低 32 位
     */
    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    /**
     * 取 handle 的高 32 位
     */
    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
