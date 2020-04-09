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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 继承 SingleThreadEventLoop 抽象类，NIO EventLoop 实现类，实现对注册到其中的 Channel 的就绪的 IO 事件，
 * 和对用户提交的任务进行处理。
 *
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 是否禁用 SelectionKey 的优化，默认开启
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    /**
     * 少于该 N 值，不开启空轮询重建新的 Selector 对象的功能, 在类初始化时使用
     */
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    /**
     * NIO Selector 空轮询该 N 次后，重建新的 Selector 对象
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        // 解决 Selector#open() 方法
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        // 初始化
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        //如果少于 MIN_PREMATURE_SELECTOR_RETURNS
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            //置 0
            selectorAutoRebuildThreshold = 0;
        }

        //设置到属性中
        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * 包装的 Selector 对象，经过优化
     * The NIO {@link Selector}.
     */
    private Selector selector;
    /**
     * 未包装的 Selector 对象
     */
    private Selector unwrappedSelector;
    /**
     * 注册的 SelectionKey 集合。Netty 自己实现，经过优化。
     */
    private SelectedSelectionKeySet selectedKeys;
    /**
     * SelectorProvider 对象，用于创建 Selector 对象
     */
    private final SelectorProvider provider;

    /**
     * 代表 Selector 是醒着状态, 也就是没有在挂起等待
     */
    private static final long AWAKE = -1L;
    /**
     * 代表 Selector 在无限期阻塞挂起, 直到有IO就绪事件
     */
    private static final long NONE = Long.MAX_VALUE;

    //记录着 Selector 的状态, 如果是非 AWAKE 或 NONE, 则表示 Selector 超时唤醒的超时时间
    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    /**
     * Select 策略
     */
    private final SelectStrategy selectStrategy;

    /**
     * 处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例.
     *  - 50 表示IO任务与非IO任务时间一样
     *  - 数字越小则表示非IO任务时间运行时间越大
     */
    private volatile int ioRatio = 50;
    /**
     * 取消 SelectionKey 的数量
     */
    private int cancelledKeys;
    /**
     * 是否需要再次 select Selector 对象
     */
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory queueFactory) {
        //调用父类构造函数
        super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
                rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        // 创建 Selector 对象
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    /**
     * 创建任务队列
     */
    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        //如果队列工厂为 null
        if (queueFactory == null) {
            //用默认的创建
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        //用工厂创建任务队列
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    /**
     * Selector 对, 包装过和未包装过的
     */
    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        /**
         * 包装过的 Selector
         */
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        // 创建 Selector 对象，作为 unwrappedSelector
        final Selector unwrappedSelector;
        try {
            //用 provider 打开
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        //禁用 SelectionKey 的优化，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 获得 SelectorImpl 类
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 获得 SelectorImpl 类失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            //如果是异常类, 则打印日志
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        //强转 Selector 实现类
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        // 创建 SelectedSelectionKeySet 对象
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 获得 "selectedKeys" "publicSelectedKeys" 的 Field
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    //java 9 设置值的方式
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    // 设置 Field 可访问
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 的 Field 中
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                    //有异常则返回异常
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        // 设置 SelectedSelectionKeySet 对象到 unwrappedSelector 中失败，则直接返回 SelectorTuple 对象。即，selector 也使用 unwrappedSelector 。
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        // 设置 SelectedSelectionKeySet 对象到 selectedKeys 中
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        // 创建 SelectedSelectionKeySetSelector 对象
        // 创建 SelectorTuple 对象。即，selector 也使用 SelectedSelectionKeySetSelector 对象。
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        //创建队列
        return newTaskQueue0(maxPendingTasks);
    }

    /**
     * 性能优化: 创建 JCTools 实现的相关队列, 优化过的. 主要针对多生产者一个消费者的优化, 恰好符合 NioEventLoop 的情况
     *
     */
    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        //最大任务为如果为 int 最大值则创建无界队列, 否则创建有界队列
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * 注册 Java NIO Channel
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            //注册
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * 设置 ioRatio 属性
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        // 只能是 0 - 100 之间
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * 重建 Selector
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        //如果不是当前线程, 则将任务放入任务队列
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        //当前线程直接重建
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * #rebuildSelector0() 方法，相比 #openSelector() 方法，主要是需要将老的 Selector 对象的“数据”复制到新的 Selector 对象上，并关闭老的 Selector 对象。
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        // 新的 Selector 对象
        final SelectorTuple newSelectorTuple;

        //旧 Selector 为 null 则直接返回
        if (oldSelector == null) {
            return;
        }

        try {
            //重新创建
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // 将注册在 NioEventLoop 上的所有 Channel ，注册到新创建 Selector 对象上
        // Register all channels to the new Selector.
        // 计算重新注册成功的 Channel 数量
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                // SelectionKey 不合法, 或者已经在新的 Selector 上注册了, 则不处理
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                // 取消老的 SelectionKey
                key.cancel();
                // 将 Channel 注册到新的 Selector 对象上
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                // 修改 Channel 的 selectionKey 指向新的 SelectionKey 上
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                // 计数 ++
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                // 关闭发生异常的 Channel
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    // 调用 NioTask 的取消注册事件
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        // 修改 selector 和 unwrappedSelector 指向新的 Selector 对象
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        // 关闭老的 Selector 对象
        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * NioEventLoop 运行，处理任务
     */
    @Override
    protected void run() {
        int selectCnt = 0;
        //“死”循环，直到 NioEventLoop 关闭
        for (;;) {
            try {
                int strategy;
                try {
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                        // 默认实现下，不存在这个情况。
                    case SelectStrategy.CONTINUE:
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // NIO 不支持
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        //获取第一个调度任务的执行时间
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        // -1 则表示没有定时任务, 则也就是不需要唤醒
                        if (curDeadlineNanos == -1L) {
                            curDeadlineNanos = NONE; // nothing on the calendar
                        }
                        // 缓存到 nextWakeupNanos, 也就是下一个要执行的定时任务
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            //如果没有任务
                            if (!hasTasks()) {
                                //选择( 查询 )任务, 因为到时间就有任务要执行, 所以阻塞要醒过来
                                strategy = select(curDeadlineNanos);
                            }
                        } finally {
                            //能执行到这里, 表示 Selector 有 IO 就绪事件或超时自动唤醒
                            //设置当前是 AWAKE 状态, 表示当前 Selector 是没有挂起的, 也就是不需要唤醒
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    //有异常, 则重建 Selector
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    //重置轮询次数
                    selectCnt = 0;
                    handleLoopException(e);
                    //重来
                    continue;
                }

                //统计 select 轮询一次
                selectCnt++;
                //NioEventLoop cancel 方法
                cancelledKeys = 0;
                needsToSelectAgain = false;
                //处理 Channel 的就绪的 IO 事件，占处理任务的总时间的比例.
                // ioRatio  = 就绪IO事件处理时间 / 任务总时间 * 100
                final int ioRatio = this.ioRatio;
                //是否有成功执行任务
                boolean ranTasks;
                // ioRatio == 100 表示不考虑时间占比的分配
                if (ioRatio == 100) {
                    try {
                        //strategy > 0 表示有事件就绪
                        if (strategy > 0) {
                            // 处理 Channel 感兴趣的就绪 IO 事件
                            processSelectedKeys();
                        }
                    } finally {
                        // 运行所有普通任务和定时任务，不限制时间
                        // Ensure we always run tasks.
                        ranTasks = runAllTasks();
                    }
                    // ioRatio != 100, 则考虑时间占比的分配
                } else if (strategy > 0) {
                    //获取 io 开始时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理 Channel 感兴趣的就绪 IO 事件
                        processSelectedKeys();
                    } finally {
                        // 运行所有普通任务和定时任务，限制时间
                        // Ensure we always run tasks.
                        //计算出 Channel 的就绪IO 事件的处理时间
                        final long ioTime = System.nanoTime() - ioStartTime;
                        //这个比例有点看不懂
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                    //没有 IO 就绪事件要处理, 则直接处理一轮非IO任务, 最多只能处理 64 个
                } else {
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                //只要有处理任务, 也就是非空轮询
                if (ranTasks || strategy > 0) {
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        //日志
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    //没有空轮询, 重置
                    selectCnt = 0;

                //没有执行任务, 也就是空轮询
                } else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case)
                    //重置
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                //CancelledKeyException 异常只打印
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Throwable t) {
                //处理其他异常
                handleLoopException(t);
            }
            //EventLoop 优雅关闭
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                // 检测线程状态
                if (isShuttingDown()) {
                    // 关闭注册的channel
                    closeAll();
                    //确定是否可以关闭
                    if (confirmShutdown()) {
                        //可以关闭则直接返回
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    /**
     * 是否重置 selectCnt
     */
    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        // 线程被打断。一般情况下不会出现，出现基本是 bug ，或者错误使用。
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            //线程已经中断, 不需要返回 true
            return true;
        }
        //SELECTOR_AUTO_REBUILD_THRESHOLD 有值且 selectCnt 大于 SELECTOR_AUTO_REBUILD_THRESHOLD
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            //重建 Selector
            rebuildSelector();
            // 返回 true 重建
            return true;
        }
        //返回 false
        return false;
    }

    /**
     * 当发生异常时，调用 #handleLoopException(Throwable t) 方法，处理异常
     */
    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * 处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeys() {
        // selectedKeys 不为 null, 则走优化过的方法
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            //关闭 Selector
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        //取消
        key.cancel();
        //取消 key 个数统计
        cancelledKeys ++;
        //如果大于等于 CLEANUP_INTERVAL
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            //重置
            cancelledKeys = 0;
            //设置 select 一次
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {

        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        //空的直接返回
        if (selectedKeys.isEmpty()) {
            return;
        }

        // 遍历 SelectionKey 迭代器
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            // 获得 SelectionKey 对象
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            // 处理一个 Channel 就绪的 IO 事件
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            // 使用 NioTask 处理一个 Channel 就绪的 IO 事件
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 无下一个节点，结束
            if (!i.hasNext()) {
                break;
            }

            //cancel 方法
            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 基于 Netty SelectedSelectionKeySetSelector ，处理 Channel 新增就绪的 IO 事件
     */
    private void processSelectedKeysOptimized() {
        // 遍历数组
        for (int i = 0; i < selectedKeys.size; ++i) {
            //获取 SelectionKey
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            //置 null, 帮助 GC
            selectedKeys.keys[i] = null;

            //获取 attachment 对象
            final Object a = k.attachment();

            //如果是 AbstractNioChannel
            if (a instanceof AbstractNioChannel) {
                // 处理一个 Channel 就绪的 IO 事件
                processSelectedKey(k, (AbstractNioChannel) a);
            //不是 AbstractNioChannel 就是 NioTask 类型了
            } else {
                // 使用 NioTask 处理一个 Channel 就绪的 IO 事件
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            //cancel 方法
            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 处理一个 Channel 就绪的 IO 事件
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        //获取 NioUnsafe
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        // 如果 SelectionKey 是不合法的，则关闭 Channel
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                //获取 EventLoop
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            //如果是当前线程
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                //关闭
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            // 获得就绪的 IO 事件的 ops
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            // OP_CONNECT 事件就绪
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                // 移除对 OP_CONNECT 感兴趣
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
                // 完成连接
                unsafe.finishConnect();
            }

            // OP_WRITE 事件就绪
            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 向 Channel 写入数据
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // SelectionKey.OP_READ 或 SelectionKey.OP_ACCEPT 就绪
            // readyOps == 0 是对 JDK Bug 的处理，防止空的死循环
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            // 发生异常，关闭 Channel
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            //处理 IO 就绪事件
            task.channelReady(k.channel(), k);
            // 执行成功
            state = 1;
            //有异常
        } catch (Exception e) {
            //取消 key
            k.cancel();
            //取消注册 Channel
            invokeChannelUnregistered(task, k, e);
            // 执行异常
            state = 2;
        } finally {
            switch (state) {
            case 0:
                // SelectionKey 取消
                k.cancel();
                // 执行 Channel 取消注册
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                // SelectionKey 不合法，则执行 Channel 取消注册
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    /**
     * 关闭注册的channel
     */
    private void closeAll() {
        //执行一次 select
        selectAgain();
        //获取所有 keys
        Set<SelectionKey> keys = selector.keys();
        //创建集合
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        //遍历
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            //如果是 AbstractNioChannel , 则添加
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        //遍历逐个关闭
        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        //非 EventLoop 线程 且 nextWakeupNanos 是非唤醒状态, 则进行唤醒
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            //因为 NioEventLoop 的线程阻塞，主要是调用 Selector#select(long timeout) 方法，阻塞等待有 Channel 感兴趣的 IO 事件，或者超时。
            // 所以需要调用 Selector#wakeup() 方法，进行唤醒 Selector
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        //这里主要用于判断是提交的定时任务会不会比原来的任务队列头任务早执行, 如果早执行, 那么需要唤醒 Selector 重新设置定时
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        return selector.selectNow();
    }

    /**
     * 调用 Selector.select() 方法
     * @param deadlineNanos 到期时间
     */
    private int select(long deadlineNanos) throws IOException {
        //如果 deadlineNanos 是 NONE
        if (deadlineNanos == NONE) {
            //调用无限时间阻塞
            return selector.select();
        }
        //计算超时时间
        // Timeout will only be 0 if deadline is within 5 microsecs
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        //如果超时小于等于 0 , 则调用 selectNow, 否则调用有超时的阻塞
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        //设置 needsToSelectAgain 为 false
        needsToSelectAgain = false;
        try {
            //执行一下 select
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
