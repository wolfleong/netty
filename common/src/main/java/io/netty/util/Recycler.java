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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Recycler是一个轻量级的对象缓存池，用来实现对象的复用。基于 ThreadLocal 实现的轻量级对象池
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    /**
     * 表示一个不需要回收的包装对象，用于在禁止使用Recycler功能时进行占位的功能
     * 仅当 io.netty.recycler.maxCapacityPerThread <= 0 时用到
     */
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };

    /**
     * 唯一ID生成器
     * 用在两处：
     * 1、当前线程ID
     * 2、WeakOrderQueue的id
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    /**
     * static变量, 生成并获取一个唯一id.
     * 用于pushNow()中的item.recycleId和item.lastRecycleId的设定
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    /**
     * 默认每个线程的Stack最多缓存 4 * 1024 个对象
     */
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    /**
     * 每个线程的Stack最多缓存多少个对象
     */
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    /**
     *  初始化容量
     */
    private static final int INITIAL_CAPACITY;
    /**
     * 最大可共享的容量
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    /**
     * WeakOrderQueue最大数量
     */
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    /**
     * WeakOrderQueue 中的数组 DefaultHandle<?>[] elements容量
     */
    private static final int LINK_CAPACITY;
    /**
     * 掩码
     */
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    /**
     * 丢弃频率
     */
    private final int interval;
    private final int maxDelayedQueuesPerThread;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            //每个线程创建一个 Stack
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
                //如果 DELAYED_RECYCLED 有值
               if (DELAYED_RECYCLED.isSet()) {
                   //删除 Stack 对应 kv
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        interval = safeFindNextPositivePowerOfTwo(ratio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        //每个线程最大容量为 0, 也就是不缓存
        if (maxCapacityPerThread == 0) {
            //直接创建对象返回
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        //获取线程的 Stack
        Stack<T> stack = threadLocal.get();
        //从 Stack 中获取 DefaultHandle
        DefaultHandle<T> handle = stack.pop();
        //如果 handle 为 null
        if (handle == null) {
            //创建 DefaultHandle
            handle = stack.newHandle();
            //创建对象, 并添加到 handle 中
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        //校验对象的池对象是否正确
        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        //回收
        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    /**
     * 对象的包装类，在Recycler中缓存的对象都会包装成DefaultHandle类。
     */
    private static final class DefaultHandle<T> implements Handle<T> {
        /**
         * pushNow() = OWN_THREAD_ID
         * 在 pushLater 中的 add(DefaultHandle handle) 操作中 == id（当前的WeakOrderQueue的唯一ID）
         * 在 poll() 中置位0
         */
        int lastRecycledId;
        /**
         * 只有在 pushNow() 中会设置值 OWN_THREAD_ID
         * 在 poll() 中置位0
         */
        int recycleId;

        /**
         * 标记是否已经被回收
         */
        boolean hasBeenRecycled;

        /**
         * 当前的 DefaultHandle 对象所属的Stack
         */
        Stack<?> stack;
        /**
         * 对象池中的真正对象
         */
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            // 防护性判断
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            //获取 Stack
            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            //回收当前对象
            stack.push(this);
        }
    }

    /**
     * 这里存储其他线程的 Stack 对应的 WeakOrderQueue, 其他线程可能有多个
     *
     * 1、每个Recycler类（而不是每一个Recycler对象）都有一个DELAYED_RECYCLED
     * 原因：可以根据一个Stack<T>对象唯一的找到一个WeakOrderQueue对象，所以此处不需要每个对象建立一个DELAYED_RECYCLED
     * 2、由于DELAYED_RECYCLED是一个类变量，所以需要包容多个T，此处泛型需要使用?
     * 3、WeakHashMap：当Stack没有强引用可达时，整个Entry{Stack<?>, WeakOrderQueue}都会加入相应的弱引用队列等待回收
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            //每个线程默认创建一个 WeakHashMap
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain

    /**
     * 存储其它线程回收到本线程stack的对象，当某个线程从Stack中获取不到对象时会从WeakOrderQueue中获取对象。
     * 每个线程的Stack拥有1个WeakOrderQueue链表，链表每个节点对应1个其它线程的WeakOrderQueue，其它线程回收到该Stack的对象就存储在这个WeakOrderQueue里。
     */
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        /**
         * 占位空队列
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        /**
         * 链表队列, 用 readIndex 表示队头, AtomicInteger.value 表示队尾
         */
        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            //缓存的元素
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            //队头指针
            int readIndex;
            //下一个 Link
            Link next;
        }

        /**
         * 队列头节点
         */
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        private static final class Head {
            /**
             * Stack 共享队列容量大小
             */
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             */
            void reclaimAllSpaceAndUnlink() {
                //获取 link
                Link head = link;
                link = null;
                //回收的容量
                int reclaimSpace = 0;
                //如果 head 不为 null
                while (head != null) {
                    //累加回收的容量
                    reclaimSpace += LINK_CAPACITY;
                    //下一个 Link
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    //置 null, help GC
                    head.next = null;
                    head = next;
                }
                //如果有回收
                if (reclaimSpace > 0) {
                    //还原到 Stack 总共享容量中
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                //自旋
                for (;;) {
                    //获取 Stack 剩余的共享容量
                    int available = availableSharedCapacity.get();
                    //如果 Stack 剩余的共享容量不够分配, 则返回 false
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    //足够分配, 则 cas 分配
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        /**
         * Link 头节点
         */
        // chain of data items
        private final Head head;
        /**
         * Link 尾节点
         */
        private Link tail;
        /**
         * 下一个队列
         */
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        /**
         * 队列 id
         */
        private final int id = ID_GENERATOR.getAndIncrement();
        /**
         * 丢弃频率
         */
        private final int interval;
        /**
         * 丢弃统计个数
         */
        private int handleRecycleCount;

        /**
         * 创建空的 WeakOrderQueue 用的
         */
        private WeakOrderQueue() {
            super(null);
            //创建 head
            head = new Head(null);
            interval = 0;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            // 创建有效Link节点，恰好是尾节点
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            // 创建Link链表头节点，只是占位符
            head = new Head(stack.availableSharedCapacity);
            //头尾连接起来
            head.link = tail;
            interval = stack.interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            //如果不够分配一个 Link 的数量, 则返回 null, 创建失败
            // We allocated a Link so reserve the space
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            //创建 WeakOrderQueue
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            //设置到 Stack 的 head 中
            stack.setHead(queue);

            return queue;
        }

        /**
         * 链表下一个 WeakOrderQueue
         */
        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * 回收所有空的 Link
         */
        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            this.next = null;
        }

        /**
         * 入队缓存
         */
        void add(DefaultHandle<?> handle) {
            //设置 lastRecycledId
            handle.lastRecycledId = id;

            // While we also enforce the recycling ratio one we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control if the Stack
            //判断是否丢弃
            if (handleRecycleCount < interval) {
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }
            //重置丢弃个数
            handleRecycleCount = 0;

            //获取尾 Link
            Link tail = this.tail;
            int writeIndex;
            //如果 tail 的队列已经在 LINK_CAPACITY, 表示 Link 队列已经满了
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                //创建新的队列
                Link link = head.newLink();
                //如果没创建成功, 则丢弃
                if (link == null) {
                    // Drop it.
                    return;
                }
                //添加到尾部
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;

                //获取 tail 队列的队尾指针
                writeIndex = tail.get();
            }
            //入队
            tail.elements[writeIndex] = handle;
            //清空所属 stack
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // tail 队尾指针前移
            tail.lazySet(writeIndex + 1);
        }

        /**
         * 是否有数据
         */
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred

        /**
         * 有三种情况没转换到数据
         *  - 没有 link
         *  - link 为空
         *  - link 有数据但被丢弃
         */
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            // 寻找第一个Link（Head不是Link）
            Link head = this.head.link;
            //没有则返回 false
            if (head == null) {
                return false;
            }

            //如果当前队列已经读完, 则取下一个队列
            if (head.readIndex == LINK_CAPACITY) {
                // readIndex 只能一直往后走的, 不会出现有两个 LINK_CAPACITY 的 Link
                //没有下一个 Link , 则返回 false
                if (head.next == null) {
                    return false;
                }
                //获取下一个 Link
                head = head.next;
                //删除当前 Link
                this.head.relink(head);
            }

            //当前 Link 的队头指针
            final int srcStart = head.readIndex;
            //当前 Link 的队尾指针
            int srcEnd = head.get();
            //Link 队列中的元素个数
            final int srcSize = srcEnd - srcStart;
            //没有元素, 返回 false
            if (srcSize == 0) {
                return false;
            }

            // 获取转移元素的目的地Stack中当前的元素个数
            final int dstSize = dst.size;
            // 计算期盼的容量
            final int expectedCapacity = dstSize + srcSize;


            //如果expectedCapacity大于目的地Stack的长度
            if (expectedCapacity > dst.elements.length) {
                //对目的地 Stack 进行扩容
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                //计算 Link 中最终的可转移的最后一个元素的下标
                //actualCapacity - dstSize 表示扩容后多出来的长度, srcStart + actualCapacity - dstSize 有可能比原来的还小
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                // 获取Link节点的DefaultHandle[]
                final DefaultHandle[] srcElems = head.elements;
                // 获取目的地Stack的DefaultHandle[]
                final DefaultHandle[] dstElems = dst.elements;
                // dst 数组的大小，会随着元素的迁入而增加，如果最后发现没有增加，那么表示没有迁移成功任何一个元素
                int newDstSize = dstSize;
                //遍历 src 数组
                for (int i = srcStart; i < srcEnd; i++) {
                    //获取元素
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                        //如果 recycleId != lastRecycledId
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    //清空原来的
                    srcElems[i] = null;

                    //判断是否要丢弃
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    //不丢弃, 入栈
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                //如果 Link 已经容量已经没有了, 删除当前 Link
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }

                //设置队头指针位置
                head.readIndex = srcEnd;
                //如果迁移后的数组长度不变, 则没迁移成功
                if (dst.size == newDstSize) {
                    //数据被丢弃了
                    return false;
                }
                //设置新的长度
                dst.size = newDstSize;
                //返回迁移成功
                return true;
            } else {
                // 队列的 Link 没有元素
                // The destination stack is full already.
                return false;
            }
        }
    }

    /**
     * 存储本线程回收的对象。对象的获取和回收对应Stack的pop和push，即获取对象时从Stack中pop出1个DefaultHandle，
     * 回收对象时将对象包装成DefaultHandle push到Stack中。Stack会与线程绑定，即每个用到Recycler的线程都会拥有1个Stack，
     * 在该线程中获取对象都是在该线程的Stack中pop出一个可用对象。
     *
     */
    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        /**
         * Reclycer 对象自身
         */
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        /**
         * 该Stack所属的线程
         */
        final WeakReference<Thread> threadRef;
        /**
         * 可用的共享内存大小，默认为maxCapacity/maxSharedCapacityFactor = 4k/2 = 2k = 2048
         */
        final AtomicInteger availableSharedCapacity;
        /**
         * DELAYED_RECYCLED 中最多可存储的 {Stack，WeakOrderQueue} 键值对个数
         */
        private final int maxDelayedQueues;

        /**
         * elements最大的容量：默认最大为4k，4096
         */
        private final int maxCapacity;
        /**
         * 回收频率个数, 如果 interval 为 8, 也就是说要丢弃 7 个才回收一个
         */
        private final int interval;
        /**
         * Stack底层数据结构，真正的用来存储数据
         */
        DefaultHandle<?>[] elements;
        /**
         * elements中的元素个数，同时也可作为操作数组的下标
         */
        int size;
        /**
         * 回收个数, 相当于丢弃对象数
         */
        private int handleRecycleCount;
        /**
         * cursor：当前正在处理的 WeakOrderQueue
         * prev：cursor的前一个WeakOrderQueue
         */
        private WeakOrderQueue cursor, prev;
        /**
         * WeakOrderQueue 链表头节点, 每次往前面插入, 默认为 null
         */
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues) {
            this.parent = parent;
            //创建 WeakReference
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            //创建 DefaultHandle 数组
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            //头插入
            queue.setNext(head);
            head = queue;
        }

        /**
         * Stack 扩容
         */
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        /**
         * 出栈, 也就是从缓存中获取数据
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            //如果当前 Stack 没有元素
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }

            //当前 Stack 有缓存

            //数量减 1
            size --;
            //出栈
            DefaultHandle ret = elements[size];
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            this.size = size;

            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            //重置
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        /**
         * 转移队列元素到 Stack
         */
        private boolean scavenge() {
            //转移成功
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            //转移失败, 重置
            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        /**
         * 转移元素, 每次只转一个 Link 的数据, 如果 WeakOrderQueue 对应的线程被加收, 则转所有 Link 的数据
         */
        private boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            //如果 cursor 为 null
            if (cursor == null) {
                prev = null;
                cursor = head;
                // 如果head==null，表示当前的Stack对象没有WeakOrderQueue，直接返回
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                //将 WeakOrderQueue 的元素转换到当前 Stack
                if (cursor.transfer(this)) {
                    success = true;
                    //转换成功, 退出
                    break;
                }
                //获取下一个 WeakOrderQueue
                WeakOrderQueue next = cursor.getNext();
                //如果已经被回收, 做清理操作
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    //如果当前的WeakOrderQueue的线程已经不可达了，则
                    // 如果该WeakOrderQueue中有数据，则将其中的数据全部转移到当前Stack中
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                //如果有成功转换
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    // 将当前的WeakOrderQueue的前一个节点prev指向当前的WeakOrderQueue的下一个节点，即将当前的WeakOrderQueue从Queue链表中移除。方便后续GC
                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();
                        prev.setNext(next);
                    }
                } else {
                    //处理下一个
                    prev = cursor;
                }

                //下一个
                cursor = next;

            } while (cursor != null && !success);

            //记录前一个节点
            this.prev = prev;
            //记录当前处理游标
            this.cursor = cursor;
            //返回
            return success;
        }

        /**
         * 回收对象, 入栈
         */
        void push(DefaultHandle<?> item) {
            //获取当前线程
            Thread currentThread = Thread.currentThread();
            //如果当前线程与Stack中的线程一致
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        /**
         * 立刻将item元素压入Stack中
         */
        private void pushNow(DefaultHandle<?> item) {
            //游离的对象的 recycleId 和 lastRecycledId 都必须为 0
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            //设置 recycleId 与 lastRecycledId
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            //size >= maxCapacity 如果队列元素超过最大容量, 则不回收
            //size < maxCapacity 如果队列元素没超最大容量, 则判断是否丢弃处理
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            //要回收 item

            //如果容量不够
            if (size == elements.length) {
                //2倍扩容
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            //入栈
            elements[size] = item;
            //元素个数加 1
            this.size = size + 1;
        }

        /**
         * 异线程回收对象
         * 先将 item 元素加入 WeakOrderQueue，后续再从 WeakOrderQueue 中将元素压入 Stack 中
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            //获取线程对应的 delayedRecycled
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            //根据当前 Stack 获取对应的 WeakOrderQueue
            WeakOrderQueue queue = delayedRecycled.get(this);
            //如果 queue 为 null
            if (queue == null) {
                // 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，则后续的无法回收 - 内存保护
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    //丢弃当前对象
                    return;
                }
                //创建 WeakOrderQueue , 如果没创建成功, 则直接丢弃对象
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                //添加 Stack 对应的 WeakOrderQueue
                delayedRecycled.put(this, queue);
                //如果发现 WeakOrderQueue 是 DUMMY
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            //入队缓存
            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        /**
         * 两个drop的时机
         * 1、pushNow：当前线程将数据 push 到 Stack 中
         * 2、transfer：将其他线程的WeakOrderQueue中的数据转移到当前的Stack中
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            //如果还没回收
            if (!handle.hasBeenRecycled) {
                //如果 interval 为 8 ,  每8个对象：扔掉7个，回收一个
                //如果回收个数小于丢弃个数
                if (handleRecycleCount < interval) {
                    //丢弃统计增加
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                //重置丢弃个数
                handleRecycleCount = 0;
                //设置已经回收
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        /**
         * 创建默认的 DefaultHandle
         */
        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
