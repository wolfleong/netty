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
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * 实现 OrderedEventExecutor 接口，继承 AbstractScheduledEventExecutor 抽象类，
 * 基于单线程的 EventExecutor 抽象类，即一个 EventExecutor 对应一个线程。
 *
 * Abstract base class for {@link OrderedEventExecutor}'s that execute all its submitted tasks in a single thread.
 *
 */
public abstract class SingleThreadEventExecutor extends AbstractScheduledEventExecutor implements OrderedEventExecutor {

    static final int DEFAULT_MAX_PENDING_EXECUTOR_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventexecutor.maxPendingTasks", Integer.MAX_VALUE));

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SingleThreadEventExecutor.class);

    // 未开始
    private static final int ST_NOT_STARTED = 1;
    // 已开始
    private static final int ST_STARTED = 2;
    // 正在关闭中
    private static final int ST_SHUTTING_DOWN = 3;
    // 已关闭
    private static final int ST_SHUTDOWN = 4;
    // 已经终止
    private static final int ST_TERMINATED = 5;

    /**
     * 没有操作的 Task
     */
    private static final Runnable NOOP_TASK = new Runnable() {
        @Override
        public void run() {
            // Do nothing.
        }
    };

    /**
     * state 字段原子更新器
     */
    private static final AtomicIntegerFieldUpdater<SingleThreadEventExecutor> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(SingleThreadEventExecutor.class, "state");
    /**
     * threadProperties 字段原子更新器
     */
    private static final AtomicReferenceFieldUpdater<SingleThreadEventExecutor, ThreadProperties> PROPERTIES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(
                    SingleThreadEventExecutor.class, ThreadProperties.class, "threadProperties");

    /**
     * 任务队列
     */
    private final Queue<Runnable> taskQueue;

    /**
     * 执行任务的线程
     */
    private volatile Thread thread;
    /**
     * 线程属性
     */
    @SuppressWarnings("unused")
    private volatile ThreadProperties threadProperties;
    /**
     * 执行器, 默认是 ThreadPerTaskExecutor, 也就是创建线程执行
     */
    private final Executor executor;
    /**
     * 线程是否已经中断
     */
    private volatile boolean interrupted;

    /**
     * 线程锁
     */
    private final CountDownLatch threadLock = new CountDownLatch(1);
    /**
     * 关闭的钩子
     */
    private final Set<Runnable> shutdownHooks = new LinkedHashSet<Runnable>();
    /**
     * 添加任务时，是否唤醒线程
     */
    private final boolean addTaskWakesUp;
    /**
     * 最大等待执行任务数量，即 {@link #taskQueue} 的队列大小
     */
    private final int maxPendingTasks;
    /**
     * 拒绝执行处理器
     */
    private final RejectedExecutionHandler rejectedExecutionHandler;

    /**
     * 最后执行时间
     */
    private long lastExecutionTime;

    /**
     * 线程状态
     */
    @SuppressWarnings({ "FieldMayBeFinal", "unused" })
    private volatile int state = ST_NOT_STARTED;

    /**
     * 优雅关闭静默时间，单位：毫秒
     */
    private volatile long gracefulShutdownQuietPeriod;
    /**
     * 优雅关闭的超时时间
     */
    private volatile long gracefulShutdownTimeout;
    /**
     * 开始优雅关闭的时间
     */
    private long gracefulShutdownStartTime;

    /**
     * EventExecutor 终止的异步结果
     */
    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param threadFactory     the {@link ThreadFactory} which will be used for the used {@link Thread}
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(
            EventExecutorGroup parent, ThreadFactory threadFactory,
            boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
        this(parent, new ThreadPerTaskExecutor(threadFactory), addTaskWakesUp, maxPendingTasks, rejectedHandler);
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_EXECUTOR_TASKS, RejectedExecutionHandlers.reject());
    }

    /**
     * Create a new instance
     *
     * @param parent            the {@link EventExecutorGroup} which is the parent of this instance and belongs to it
     * @param executor          the {@link Executor} which will be used for executing
     * @param addTaskWakesUp    {@code true} if and only if invocation of {@link #addTask(Runnable)} will wake up the
     *                          executor thread
     * @param maxPendingTasks   the maximum number of pending tasks before new tasks will be rejected.
     * @param rejectedHandler   the {@link RejectedExecutionHandler} to use.
     */
    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, int maxPendingTasks,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = Math.max(16, maxPendingTasks);
        this.executor = ThreadExecutorMap.apply(executor, this);
        taskQueue = newTaskQueue(this.maxPendingTasks);
        rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    protected SingleThreadEventExecutor(EventExecutorGroup parent, Executor executor,
                                        boolean addTaskWakesUp, Queue<Runnable> taskQueue,
                                        RejectedExecutionHandler rejectedHandler) {
        super(parent);
        this.addTaskWakesUp = addTaskWakesUp;
        this.maxPendingTasks = DEFAULT_MAX_PENDING_EXECUTOR_TASKS;
        this.executor = ThreadExecutorMap.apply(executor, this);
        this.taskQueue = ObjectUtil.checkNotNull(taskQueue, "taskQueue");
        this.rejectedExecutionHandler = ObjectUtil.checkNotNull(rejectedHandler, "rejectedHandler");
    }

    /**
     * @deprecated Please use and override {@link #newTaskQueue(int)}.
     */
    @Deprecated
    protected Queue<Runnable> newTaskQueue() {
        return newTaskQueue(maxPendingTasks);
    }

    /**
     * Create a new {@link Queue} which will holds the tasks to execute. This default implementation will return a
     * {@link LinkedBlockingQueue} but if your sub-class of {@link SingleThreadEventExecutor} will not do any blocking
     * calls on the this {@link Queue} it may make sense to {@code @Override} this and return some more performant
     * implementation that does not support blocking operations at all.
     */
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        //默认是 LinkedBlockingQueue
        return new LinkedBlockingQueue<Runnable>(maxPendingTasks);
    }

    /**
     * 打断 EventLoop 的线程
     * Interrupt the current running {@link Thread}.
     */
    protected void interruptThread() {
        Thread currentThread = thread;
        // 线程不存在，也就是线程还没创建, 则先标记线程被打断, 创建时再中断
        if (currentThread == null) {
            interrupted = true;
        } else {
            // 打断线程
            currentThread.interrupt();
        }
    }

    /**
     * @see Queue#poll()
     */
    protected Runnable pollTask() {
        assert inEventLoop();
        return pollTaskFrom(taskQueue);
    }

    /**
     * 出队, 只要不是 WAKEUP_TASK 类型, 则返回
     */
    protected static Runnable pollTaskFrom(Queue<Runnable> taskQueue) {
        for (;;) {
            Runnable task = taskQueue.poll();
            if (task != WAKEUP_TASK) {
                return task;
            }
        }
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     * <p>
     * Be aware that this method will throw an {@link UnsupportedOperationException} if the task queue, which was
     * created via {@link #newTaskQueue()}, does not implement {@link BlockingQueue}.
     * </p>
     *
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    protected Runnable takeTask() {
        assert inEventLoop();
        if (!(taskQueue instanceof BlockingQueue)) {
            throw new UnsupportedOperationException();
        }

        BlockingQueue<Runnable> taskQueue = (BlockingQueue<Runnable>) this.taskQueue;
        for (;;) {
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                    if (task == WAKEUP_TASK) {
                        task = null;
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                long delayNanos = scheduledTask.delayNanos();
                Runnable task = null;
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                }
                if (task == null) {
                    // We need to fetch the scheduled tasks now as otherwise there may be a chance that
                    // scheduled tasks are never executed if there is always one task in the taskQueue.
                    // This is for example true for the read task of OIO Transport
                    // See https://github.com/netty/netty/issues/1614
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 将所有 scheduledTaskQueue 的任务移动 taskQueue
     * @return 所有都转移成功, 则返回 true
     */
    private boolean fetchFromScheduledTaskQueue() {
        //定时任务队列为空则返回
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return true;
        }
        //获取当前时间
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        //自旋
        for (;;) {
            //出队定时任务
            Runnable scheduledTask = pollScheduledTask(nanoTime);
            //如果定时任务是 null, 则返回 true
            if (scheduledTask == null) {
                return true;
            }
            //将定时任务添加到 taskQueue
            if (!taskQueue.offer(scheduledTask)) {
                //如果不成功, 重新放回定时任务队列
                // No space left in the task queue add it back to the scheduledTaskQueue so we pick it up again.
                scheduledTaskQueue.add((ScheduledFutureTask<?>) scheduledTask);
                //不成功, 则返回 false
                return false;
            }
        }
    }

    /**
     * @return {@code true} if at least one scheduled task was executed.
     */
    private boolean executeExpiredScheduledTasks() {
        if (scheduledTaskQueue == null || scheduledTaskQueue.isEmpty()) {
            return false;
        }
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        if (scheduledTask == null) {
            return false;
        }
        do {
            safeExecute(scheduledTask);
        } while ((scheduledTask = pollScheduledTask(nanoTime)) != null);
        return true;
    }

    /**
     * 返回队头的任务
     * @see Queue#peek()
     */
    protected Runnable peekTask() {
        // 仅允许在 EventLoop 线程中执行
        assert inEventLoop();
        return taskQueue.peek();
    }

    /**
     * 队列中是否有任务
     * @see Queue#isEmpty()
     */
    protected boolean hasTasks() {
        // 仅允许在 EventLoop 线程中执行
        assert inEventLoop();
        return !taskQueue.isEmpty();
    }

    /**
     * 获得队列中的任务数
     * Return the number of tasks that are pending for processing.
     *
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it with care!</strong>
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    protected void addTask(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        //在 #offerTask(Runnable task) 的方法的基础上，若添加任务到队列中失败，则进行拒绝任务
        if (!offerTask(task)) {
            reject(task);
        }
    }

    final boolean offerTask(Runnable task) {
        // 关闭时，拒绝任务
        if (isShutdown()) {
            reject();
        }
        // 添加任务到队列
        return taskQueue.offer(task);
    }

    /**
     * @see Queue#remove(Object)
     */
    protected boolean removeTask(Runnable task) {
        //移除指定任务
        return taskQueue.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    /**
     * 执行队列所有任务. 返回 true 代表至少成功执行了一个任务
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.
     *
     * @return {@code true} if and only if at least one task was run
     */
    protected boolean runAllTasks() {
        assert inEventLoop();
        //是否所有定时任务都转移成功
        boolean fetchedAll;
        //至少执行了一个任务
        boolean ranAtLeastOne = false;

        do {
            //相当于将所有定时任务转移到 taskQueue
            fetchedAll = fetchFromScheduledTaskQueue();
            //执行任务队列的任务
            if (runAllTasksFrom(taskQueue)) {
                //标记有执行任务
                ranAtLeastOne = true;
            }
            //如果没全部转移到 taskQueue , 则一直循环
        } while (!fetchedAll); // keep on processing until we fetched all scheduled tasks.

        //如果有执行任务
        if (ranAtLeastOne) {
            //记录最后执行时间
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        //回调函数
        afterRunningAllTasks();
        //返回是否有成功执行任务
        return ranAtLeastOne;
    }

    /**
     * Execute all expired scheduled tasks and all current tasks in the executor queue until both queues are empty,
     * or {@code maxDrainAttempts} has been exceeded.
     * @param maxDrainAttempts The maximum amount of times this method attempts to drain from queues. This is to prevent
     *                         continuous task execution and scheduling from preventing the EventExecutor thread to
     *                         make progress and return to the selector mechanism to process inbound I/O events.
     * @return {@code true} if at least one task was run.
     */
    protected final boolean runScheduledAndExecutorTasks(final int maxDrainAttempts) {
        assert inEventLoop();
        boolean ranAtLeastOneTask;
        int drainAttempt = 0;
        do {
            // We must run the taskQueue tasks first, because the scheduled tasks from outside the EventLoop are queued
            // here because the taskQueue is thread safe and the scheduledTaskQueue is not thread safe.
            ranAtLeastOneTask = runExistingTasksFrom(taskQueue) | executeExpiredScheduledTasks();
        } while (ranAtLeastOneTask && ++drainAttempt < maxDrainAttempts);

        if (drainAttempt > 0) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }
        afterRunningAllTasks();

        return drainAttempt > 0;
    }

    /**
     * 运行队列中所有的 task. 返回 true 代表有执行. 返回 false 代表一个都没执行
     * Runs all tasks from the passed {@code taskQueue}.
     *
     * @param taskQueue To poll and execute all tasks.
     *
     * @return {@code true} if at least one task was executed.
     */
    protected final boolean runAllTasksFrom(Queue<Runnable> taskQueue) {
        //获取队列的 task
        Runnable task = pollTaskFrom(taskQueue);
        //如果没有任务, 则返回 false
        if (task == null) {
            //返回 false 表示没有任务执行
            return false;
        }

        //自旋
        for (;;) {
            //执行任务
            safeExecute(task);
            //继续出队任务
            task = pollTaskFrom(taskQueue);
            if (task == null) {
                //返回 true 表示有执行任务
                return true;
            }
        }
    }

    /**
     * What ever tasks are present in {@code taskQueue} when this method is invoked will be {@link Runnable#run()}.
     * @param taskQueue the task queue to drain.
     * @return {@code true} if at least {@link Runnable#run()} was called.
     */
    private boolean runExistingTasksFrom(Queue<Runnable> taskQueue) {
        Runnable task = pollTaskFrom(taskQueue);
        if (task == null) {
            return false;
        }
        int remaining = Math.min(maxPendingTasks, taskQueue.size());
        safeExecute(task);
        // Use taskQueue.poll() directly rather than pollTaskFrom() since the latter may
        // silently consume more than one item from the queue (skips over WAKEUP_TASK instances)
        while (remaining-- > 0 && (task = taskQueue.poll()) != null) {
            safeExecute(task);
        }
        return true;
    }

    /**
     * 运行所有任务, 在给定超时时间情况下
     * Poll all tasks from the task queue and run them via {@link Runnable#run()} method.  This method stops running
     * the tasks in the task queue and returns if it ran longer than {@code timeoutNanos}.
     */
    protected boolean runAllTasks(long timeoutNanos) {
        //转移定时任务到 taskQueue
        fetchFromScheduledTaskQueue();
        // 获得队头的任务
        Runnable task = pollTask();
        //为 null, 则表示没有任务执行, 返回 false
        if (task == null) {
            afterRunningAllTasks();
            return false;
        }

        //计算到期时间
        final long deadline = timeoutNanos > 0 ? ScheduledFutureTask.nanoTime() + timeoutNanos : 0;
        //执行任务数
        long runTasks = 0;
        //最后执行时间
        long lastExecutionTime;
        //自旋
        for (;;) {
            //执行任务
            safeExecute(task);

            //统计已经执行的任务
            runTasks ++;

            // Check timeout every 64 tasks because nanoTime() is relatively expensive.
            // XXX: Hard-coded value - will make it configurable if it is really a problem.
            // 0x3F 相当于 ( 1 << 5 ) - 1 = 63
            // 每隔 64 个任务检查一次时间，因为 nanoTime() 是相对费时的操作
            // 64 这个值当前是硬编码的，无法配置，可能会成为一个问题。
            if ((runTasks & 0x3F) == 0) {
                //获取最后执行时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                //如果最后执行时间超过给定超时时间, 则表示超时了, 退出
                if (lastExecutionTime >= deadline) {
                    break;
                }
            }

            //没超时, 继续获取任务
            task = pollTask();
            //如果没有任务了
            if (task == null) {
                //更新最后任务执行时间
                lastExecutionTime = ScheduledFutureTask.nanoTime();
                //退出
                break;
            }
        }

        //处理回调
        afterRunningAllTasks();
        //缓存最后执行时间
        this.lastExecutionTime = lastExecutionTime;
        //至少执行了一个任务, 返回 true
        return true;
    }

    /**
     * Invoked before returning from {@link #runAllTasks()} and {@link #runAllTasks(long)}.
     */
    @UnstableApi
    protected void afterRunningAllTasks() { }

    /**
     * Returns the amount of time left until the scheduled task with the closest dead line is executed.
     */
    protected long delayNanos(long currentTimeNanos) {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return SCHEDULE_PURGE_INTERVAL;
        }

        return scheduledTask.delayNanos(currentTimeNanos);
    }

    /**
     * Returns the absolute point in time (relative to {@link #nanoTime()}) at which the the next
     * closest scheduled task should run.
     */
    @UnstableApi
    protected long deadlineNanos() {
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        if (scheduledTask == null) {
            return nanoTime() + SCHEDULE_PURGE_INTERVAL;
        }
        return scheduledTask.deadlineNanos();
    }

    /**
     * Updates the internal timestamp that tells when a submitted task was executed most recently.
     * {@link #runAllTasks()} and {@link #runAllTasks(long)} updates this timestamp automatically, and thus there's
     * usually no need to call this method.  However, if you take the tasks manually using {@link #takeTask()} or
     * {@link #pollTask()}, you have to call this method at the end of task execution loop for accurate quiet period
     * checks.
     */
    protected void updateLastExecutionTime() {
        //记录最后执行时间
        lastExecutionTime = ScheduledFutureTask.nanoTime();
    }

    /**
     * 抽象方法, 子类实现
     */
    protected abstract void run();

    /**
     * 清理释放资源
     * Do nothing, sub-classes may override
     */
    protected void cleanup() {
        // NOOP
    }

    protected void wakeup(boolean inEventLoop) {
        //!inEventLoop 代码段，判断不在 EventLoop 的线程中。因为，如果在 EventLoop 线程中，意味着线程就在执行中，不必要唤醒。
        if (!inEventLoop) {
            // Use offer as we actually only need this to unblock the thread and if offer fails we do not care as there
            // is already something in the queue.
            //添加 WAKEUP_TASK 任务
            taskQueue.offer(WAKEUP_TASK);
        }
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        //判断指定线程是否是 EventLoop 线程
        return thread == this.thread;
    }

    /**
     * Add a {@link Runnable} which will be executed on shutdown of this instance
     */
    public void addShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.add(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.add(task);
                }
            });
        }
    }

    /**
     * Remove a previous added {@link Runnable} as a shutdown hook
     */
    public void removeShutdownHook(final Runnable task) {
        if (inEventLoop()) {
            shutdownHooks.remove(task);
        } else {
            execute(new Runnable() {
                @Override
                public void run() {
                    shutdownHooks.remove(task);
                }
            });
        }
    }

    private boolean runShutdownHooks() {
        boolean ran = false;
        //钩子列表不为空
        // Note shutdown hooks can add / remove shutdown hooks.
        while (!shutdownHooks.isEmpty()) {
            // 使用copy是因为shutdwonHook任务中可以添加或删除shutdwonHook任务
            List<Runnable> copy = new ArrayList<Runnable>(shutdownHooks);
            //清空原来的
            shutdownHooks.clear();
            //遍历执行
            for (Runnable task: copy) {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.warn("Shutdown hook raised an exception.", t);
                } finally {
                    //有执行成功
                    ran = true;
                }
            }
        }

        //设置最后执行时间
        if (ran) {
            lastExecutionTime = ScheduledFutureTask.nanoTime();
        }

        //返回有执行
        return ran;
    }

    /**
     * 外面线程调用关闭, 只设置状态为 ST_SHUTTING_DOWN
     * Netty 默认的shutdownGracefully()机制为：在2秒的静默时间内如果没有任务，则关闭；否则15秒截止时间到达时关闭。换句话说，在15秒时间段内，
     * 如果有超过2秒的时间段没有任务则关闭。至此，我们明白了从EvnetLoop循环中跳出的机制，最后，我们抵达终点站：线程结束机制
     * @param quietPeriod 静默时间
     * @param timeout     截止时间
     * @param unit        时间单位
     *
     */
    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        ObjectUtil.checkPositiveOrZero(quietPeriod, "quietPeriod");
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        ObjectUtil.checkNotNull(unit, "unit");

        //如果已经调用了 shutdownGracefully() 正在半闭中
        if (isShuttingDown()) {
            // 正在关闭阻止其他线程
            return terminationFuture();
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            //如果正在半闭
            if (isShuttingDown()) {
                return terminationFuture();
            }
            int newState;
            wakeup = true;
            //获取状态
            oldState = state;
            //如果当前线程是 EventLoop 线程
            if (inEventLoop) {
                //新的状态
                newState = ST_SHUTTING_DOWN;
            } else {
                switch (oldState) {
                    //线程未开始, 谈何关闭
                    case ST_NOT_STARTED:
                        //旧状态为未关闭
                    case ST_STARTED:
                        //设置新状态为 ST_SHUTTING_DOWN
                        newState = ST_SHUTTING_DOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            //cas 修改状态
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }
        //设置优雅关闭相关属性
        gracefulShutdownQuietPeriod = unit.toNanos(quietPeriod);
        gracefulShutdownTimeout = unit.toNanos(timeout);

        //确定线程已经启动
        if (ensureThreadStarted(oldState)) {
            return terminationFuture;
        }

        //如果需要唤醒
        if (wakeup) {
            //唤醒 线程
            taskQueue.offer(WAKEUP_TASK);
            //唤醒 Selector
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }

        //返回关闭的异步结果
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        if (isShutdown()) {
            return;
        }

        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (;;) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state;
            if (inEventLoop) {
                newState = ST_SHUTDOWN;
            } else {
                switch (oldState) {
                    case ST_NOT_STARTED:
                    case ST_STARTED:
                    case ST_SHUTTING_DOWN:
                        newState = ST_SHUTDOWN;
                        break;
                    default:
                        newState = oldState;
                        wakeup = false;
                }
            }
            if (STATE_UPDATER.compareAndSet(this, oldState, newState)) {
                break;
            }
        }

        if (ensureThreadStarted(oldState)) {
            return;
        }

        if (wakeup) {
            taskQueue.offer(WAKEUP_TASK);
            if (!addTaskWakesUp) {
                wakeup(inEventLoop);
            }
        }
    }

    @Override
    public boolean isShuttingDown() {
        return state >= ST_SHUTTING_DOWN;
    }

    @Override
    public boolean isShutdown() {
        return state >= ST_SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state == ST_TERMINATED;
    }

    /**
     * 确定是否可以关闭或者说是否可以从EventLoop循环中跳出
     *
     * 调用shutdown()方法从循环跳出的条件有：
     *    (1).执行完普通任务
     *    (2).没有普通任务，执行完shutdownHook任务
     *    (3).既没有普通任务也没有shutdownHook任务
     *
     * 调用shutdownGracefully()方法从循环跳出的条件有：
     *    (1).执行完普通任务且静默时间为0
     *    (2).没有普通任务，执行完shutdownHook任务且静默时间为0
     *    (3).静默期间没有任务提交
     *    (4).优雅关闭截止时间已到
     * 注意上面所列的条件之间是或的关系，也就是说满足任意一条就会从EventLoop循环中跳出。我们可以将静默时间看为一段观察期，
     * 在此期间如果没有任务执行，说明可以跳出循环；如果此期间有任务执行，执行完后立即进入下一个观察期继续观察；如果连续多个观察期一直有任务执行，那么截止时间到则跳出循环
     *
     * Confirm that the shutdown if the instance should be done now!
     */
    protected boolean confirmShutdown() {
        // 没有调用shutdown相关的方法直接返回
        if (!isShuttingDown()) {
            return false;
        }

        //当前线程不是 EventLoop 线程, 抛异常
        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        //取消所有的调度任务
        cancelScheduledTasks();

        //如果没有设置优雅关闭开始时间, 则设置
        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ScheduledFutureTask.nanoTime();
        }

        // 执行完普通任务或者没有普通任务时执行完shutdownHook任务
        if (runAllTasks() || runShutdownHooks()) {
            //能跑下来, 则表示有执行

            // 调用了 shutdown() 方法
            if (isShutdown()) {
                // 直接退出
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            // 优雅关闭静默时间为 0 也直接退出
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            // 优雅关闭但有未执行任务，唤醒线程执行
            taskQueue.offer(WAKEUP_TASK);
            return false;
        }

        final long nanoTime = ScheduledFutureTask.nanoTime();

        // shutdown()方法调用直接返回，优雅关闭截止时间到也返回
        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        // 在静默期间每100ms唤醒线程执行期间提交的任务
        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            // Check if any tasks were added to the queue every 100ms.
            // TODO: Change the behavior of takeTask() so that it returns on timeout.
            taskQueue.offer(WAKEUP_TASK);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // 静默时间内没有任务提交，可以优雅关闭，此时若用户又提交任务则不会被执行
        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        ObjectUtil.checkNotNull(unit, "unit");
        if (inEventLoop()) {
            throw new IllegalStateException("cannot await termination of the current thread");
        }

        //线程等待
        threadLock.await(timeout, unit);

        //返回关闭状态
        return isTerminated();
    }

    /**
     * 执行 Runnable 任务, 执行前时会唤醒 Selector, 也就是会立即执行任务
     */
    @Override
    public void execute(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        execute(task, !(task instanceof LazyRunnable) && wakesUpForTask(task));
    }

    /**
     * 延后执行, 代表不需要立即唤醒 Selector , 等下一个周期执行
     */
    @Override
    public void lazyExecute(Runnable task) {
        execute(ObjectUtil.checkNotNull(task, "task"), false);
    }

    /**
     * 执行任务的方法
     * @param task 任务
     * @param immediate 是否立即唤醒 Selector
     */
    private void execute(Runnable task, boolean immediate) {
        // 获得当前是否在 EventLoop 的线程中
        boolean inEventLoop = inEventLoop();
        // 添加到任务队列
        addTask(task);
        //当前线程不是 EventLoop 线程
        if (!inEventLoop) {
            // 创建线程
            startThread();
            // 若已经关闭，移除任务，并进行拒绝
            if (isShutdown()) {
                //是否拒绝
                boolean reject = false;
                try {
                    //删除任务
                    if (removeTask(task)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }

                if (reject) {
                    //拒绝
                    reject();
                }
            }
        }

        //!addTaskWakesUp 有点奇怪，不是说好的 addTaskWakesUp 表示“添加任务时，是否唤醒线程”？！但是，怎么使用 ! 取反了。
        // 这样反倒变成了，“添加任务时，是否【不】唤醒线程”。具体的原因是为什么呢？
        // 笔者 Google、Github Netty Issue、和基佬讨论，都未找到解答。
        // 目前笔者的理解是：addTaskWakesUp 真正的意思是，“添加任务后，任务是否会自动导致线程唤醒”。为什么呢？
        //对于 Nio 使用的 NioEventLoop ，它的线程执行任务是基于 Selector 监听感兴趣的事件，所以当任务添加到 taskQueue 队列中时，
        // 线程是无感知的，所以需要调用 #wakeup(boolean inEventLoop) 方法，进行主动的唤醒。
        //对于 Oio 使用的 ThreadPerChannelEventLoop ，它的线程执行是基于 taskQueue 队列监听( 阻塞拉取 )事件和任务，
        // 所以当任务添加到 taskQueue 队列中时，线程是可感知的，相当于说，进行被动的唤醒。

        // 如果要唤醒线程
        if (!addTaskWakesUp && immediate) {
            //唤醒
            wakeup(inEventLoop);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    /**
     * 判断若在 EventLoop 的线程中调用该方法，抛出 RejectedExecutionException 异常
     */
    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException("Calling " + method + " from within the EventLoop is not allowed");
        }
    }

    /**
     * Returns the {@link ThreadProperties} of the {@link Thread} that powers the {@link SingleThreadEventExecutor}.
     * If the {@link SingleThreadEventExecutor} is not started yet, this operation will start it and block until
     * it is fully started.
     */
    public final ThreadProperties threadProperties() {
        ThreadProperties threadProperties = this.threadProperties;
        //没初始化
        if (threadProperties == null) {
            //获取线程
            Thread thread = this.thread;
            //如果线程没初始化
            if (thread == null) {
                assert !inEventLoop();
                // 提交空任务，促使 execute 方法执行
                submit(NOOP_TASK).syncUninterruptibly();
                //重新获取
                thread = this.thread;
                assert thread != null;
            }

            //创建
            threadProperties = new DefaultThreadProperties(thread);
            // CAS 修改 threadProperties 属性
            if (!PROPERTIES_UPDATER.compareAndSet(this, null, threadProperties)) {
                threadProperties = this.threadProperties;
            }
        }

        //返回
        return threadProperties;
    }

    /**
     * @deprecated use {@link AbstractEventExecutor.LazyRunnable}
     */
    @Deprecated
    protected interface NonWakeupRunnable extends LazyRunnable { }

    /**
     * 判断该任务是否需要唤醒线程
     * Can be overridden to control which tasks require waking the {@link EventExecutor} thread
     * if it is waiting so that they can be run immediately.
     */
    protected boolean wakesUpForTask(Runnable task) {
        return true;
    }

    protected static void reject() {
        throw new RejectedExecutionException("event executor terminated");
    }

    /**
     * 拒绝任务
     * Offers the task to the associated {@link RejectedExecutionHandler}.
     *
     * @param task to reject.
     */
    protected final void reject(Runnable task) {
        //调用 RejectedExecutionHandler 方法，拒绝该任务。
        rejectedExecutionHandler.rejected(task, this);
    }

    // ScheduledExecutorService implementation

    private static final long SCHEDULE_PURGE_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    private void startThread() {
        //如当前 EventExecutor 是未启动
        if (state == ST_NOT_STARTED) {
            //改成启动状态
            if (STATE_UPDATER.compareAndSet(this, ST_NOT_STARTED, ST_STARTED)) {
                boolean success = false;
                try {
                    //启动 EventLoop 独占的线程
                    doStartThread();
                    success = true;
                } finally {
                    //如果不成功, 则重置状态
                    if (!success) {
                        STATE_UPDATER.compareAndSet(this, ST_STARTED, ST_NOT_STARTED);
                    }
                }
            }
        }
    }

    /**
     * 确保线程已经启动
     */
    private boolean ensureThreadStarted(int oldState) {
        //如果线程没启动
        if (oldState == ST_NOT_STARTED) {
            try {
                //启动线程
                doStartThread();
            } catch (Throwable cause) {
                //有异常则设置已经关闭
                STATE_UPDATER.set(this, ST_TERMINATED);
                terminationFuture.tryFailure(cause);

                if (!(cause instanceof Exception)) {
                    // Also rethrow as it may be an OOME for example
                    PlatformDependent.throwException(cause);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 启动 EventLoop 独占的线程
     */
    private void doStartThread() {
        //确保线程为 null
        assert thread == null;
        //用执行器执行
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 记录线程到属性中
                thread = Thread.currentThread();
                // 如果当前线程已经被标记打断，则进行打断操作。
                if (interrupted) {
                    thread.interrupt();
                }

                // 是否执行成功
                boolean success = false;
                // 更新最后执行时间
                updateLastExecutionTime();
                try {
                    // 执行任务
                    SingleThreadEventExecutor.this.run();
                    // 标记执行成功
                    success = true;
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from an event executor: ", t);
                } finally {

                    //自旋
                    for (;;) {
                        //旧状态
                        int oldState = state;
                        //如果不是正在关闭中或已经半闭, 则尝试 cas 改变状态为 ST_SHUTTING_DOWN , 成功则退出
                        if (oldState >= ST_SHUTTING_DOWN || STATE_UPDATER.compareAndSet(
                                SingleThreadEventExecutor.this, oldState, ST_SHUTTING_DOWN)) {
                            break;
                        }
                    }

                    // gracefulShutdownStartTime=0，说明 confirmShutdown() 方法没有调用，记录日志
                    // Check if confirmShutdown() was called at the end of the loop.
                    if (success && gracefulShutdownStartTime == 0) {
                        if (logger.isErrorEnabled()) {
                            logger.error("Buggy " + EventExecutor.class.getSimpleName() + " implementation; " +
                                    SingleThreadEventExecutor.class.getSimpleName() + ".confirmShutdown() must " +
                                    "be called before run() implementation terminates.");
                        }
                    }

                    try {
                        // Run all remaining tasks and shutdown hooks. At this point the event loop
                        // is in ST_SHUTTING_DOWN state still accepting tasks which is needed for
                        // graceful shutdown with quietPeriod.
                        for (;;) {
                            // 抛出异常时，将普通任务和shutdownHook任务执行完毕
                            // 正常关闭时，结合前述的循环跳出条件
                            if (confirmShutdown()) {
                                break;
                            }
                        }

                        // Now we want to make sure no more tasks can be added from this point. This is
                        // achieved by switching the state. Any new tasks beyond this point will be rejected.
                        for (;;) {
                            int oldState = state;
                            //尝试更新为 ST_SHUTDOWN 状态
                            if (oldState >= ST_SHUTDOWN || STATE_UPDATER.compareAndSet(
                                    SingleThreadEventExecutor.this, oldState, ST_SHUTDOWN)) {
                                break;
                            }
                        }

                        // We have the final set of tasks in the queue now, no more can be added, run all remaining.
                        // No need to loop here, this is the final pass.
                        confirmShutdown();
                    } finally {
                        try {
                            // 清理，释放资源
                            cleanup();
                        } finally {
                            // Lets remove all FastThreadLocals for the Thread as we are about to terminate and notify
                            // the future. The user may block on the future and once it unblocks the JVM may terminate
                            // and start unloading classes.
                            // See https://github.com/netty/netty/issues/6596.
                            //删除线程本地缓存的所有值
                            FastThreadLocal.removeAll();

                            //设置终止状态
                            STATE_UPDATER.set(SingleThreadEventExecutor.this, ST_TERMINATED);
                            //已经终止, 将等待终止的线程放行
                            threadLock.countDown();
                            //未执行的任务
                            int numUserTasks = drainTasks();
                            //如果有未执行的任务, 打警告日志
                            if (numUserTasks > 0 && logger.isWarnEnabled()) {
                                logger.warn("An event executor terminated with " +
                                        "non-empty task queue (" + numUserTasks + ')');
                            }
                            //设置终止异步结果为完成
                            terminationFuture.setSuccess(null);
                        }
                    }
                }
            }
        });
    }

    /**
     * 统计未执行的任务
     */
    final int drainTasks() {
        int numTasks = 0;
        for (;;) {
            Runnable runnable = taskQueue.poll();
            if (runnable == null) {
                break;
            }
            // WAKEUP_TASK should be just discarded as these are added internally.
            // The important bit is that we not have any user tasks left.
            if (WAKEUP_TASK != runnable) {
                numTasks++;
            }
        }
        return numTasks;
    }

    /**
     * ThreadProperties 接口的默认实现, 其实就是读取 Thread 的属性,
     *  - 我们可以看到，每个实现方法，实际上就是对被包装的线程 t 的方法的封装。
     *  - 那为什么 #threadProperties() 方法不直接返回 thread 呢？因为如果直接返回 thread ，调用方可以调用到该变量的其他方法，这个是我们不希望看到的。
     */
    private static final class DefaultThreadProperties implements ThreadProperties {
        private final Thread t;

        DefaultThreadProperties(Thread t) {
            this.t = t;
        }

        @Override
        public State state() {
            return t.getState();
        }

        @Override
        public int priority() {
            return t.getPriority();
        }

        @Override
        public boolean isInterrupted() {
            return t.isInterrupted();
        }

        @Override
        public boolean isDaemon() {
            return t.isDaemon();
        }

        @Override
        public String name() {
            return t.getName();
        }

        @Override
        public long id() {
            return t.getId();
        }

        @Override
        public StackTraceElement[] stackTrace() {
            return t.getStackTrace();
        }

        @Override
        public boolean isAlive() {
            return t.isAlive();
        }
    }
}
