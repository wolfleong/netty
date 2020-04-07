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
package io.netty.util.concurrent;

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PriorityQueue;

import static io.netty.util.concurrent.ScheduledFutureTask.deadlineNanos;

import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 继承 AbstractEventExecutor 抽象类，支持定时任务的 EventExecutor 的抽象类。
 * Abstract base class for {@link EventExecutor}s that want to support scheduling.
 */
public abstract class AbstractScheduledEventExecutor extends AbstractEventExecutor {
    /**
     * 定时任务排序器
     */
    private static final Comparator<ScheduledFutureTask<?>> SCHEDULED_FUTURE_TASK_COMPARATOR =
            new Comparator<ScheduledFutureTask<?>>() {
                @Override
                public int compare(ScheduledFutureTask<?> o1, ScheduledFutureTask<?> o2) {
                    return o1.compareTo(o2);
                }
            };

    /**
     * 这是一个空的 Runnable 实现类。仅仅用于唤醒基于 taskQueue 阻塞拉取的 EventLoop 实现类
     * 唤醒有两种情况:
     *  - Selector 上面的阻塞需要唤醒
     *  - 队列为空时的阻塞, 需要唤醒, DefaultEventExecutor 才用阻塞的方法, NioEventLoop 都是非阻塞的
     */
   static final Runnable WAKEUP_TASK = new Runnable() {
       @Override
       public void run() { } // Do nothing
    };

    /**
     * 定时任务队列
     */
    PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue;

    /**
     * 任务id增长器
     */
    long nextTaskId;

    protected AbstractScheduledEventExecutor() {
    }

    protected AbstractScheduledEventExecutor(EventExecutorGroup parent) {
        super(parent);
    }

    protected static long nanoTime() {
        //获得当前时间。
        return ScheduledFutureTask.nanoTime();
    }

    /**
     * Given an arbitrary deadline {@code deadlineNanos}, calculate the number of nano seconds from now
     * {@code deadlineNanos} would expire.
     * @param deadlineNanos An arbitrary deadline in nano seconds.
     * @return the number of nano seconds from now {@code deadlineNanos} would expire.
     */
    protected static long deadlineToDelayNanos(long deadlineNanos) {
        return ScheduledFutureTask.deadlineToDelayNanos(deadlineNanos);
    }

    /**
     * The initial value used for delay and computations based upon a monatomic time source.
     * @return initial value used for delay and computations based upon a monatomic time source.
     */
    protected static long initialNanoTime() {
        return ScheduledFutureTask.initialNanoTime();
    }

    /**
     * 获取任务队列
     */
    PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue() {
        //如果定时任务队列为 null
        if (scheduledTaskQueue == null) {
            //初始化
            scheduledTaskQueue = new DefaultPriorityQueue<ScheduledFutureTask<?>>(
                    SCHEDULED_FUTURE_TASK_COMPARATOR,
                    // Use same initial capacity as java.util.PriorityQueue
                    11);
        }
        //返回
        return scheduledTaskQueue;
    }

    private static boolean isNullOrEmpty(Queue<ScheduledFutureTask<?>> queue) {
        return queue == null || queue.isEmpty();
    }

    /**
     * 取消所有定时任务, 只改状态, 不删除队列任务
     * Cancel all scheduled tasks.
     *
     * This method MUST be called only when {@link #inEventLoop()} is {@code true}.
     */
    protected void cancelScheduledTasks() {
        assert inEventLoop();
        // 若队列为空，直接返回
        PriorityQueue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        if (isNullOrEmpty(scheduledTaskQueue)) {
            return;
        }

        final ScheduledFutureTask<?>[] scheduledTasks =
                scheduledTaskQueue.toArray(new ScheduledFutureTask<?>[0]);

        // 循环，取消所有任务, 没有删除队列里的元素
        // 因为删除队列元素要做自平衡, 都关闭了, 没必要, 直接改状态就好
        for (ScheduledFutureTask<?> task: scheduledTasks) {
            task.cancelWithoutRemove(false);
        }

        //清空队列数量
        scheduledTaskQueue.clearIgnoringIndexes();
    }

    /**
     * @see #pollScheduledTask(long)
     */
    protected final Runnable pollScheduledTask() {
        return pollScheduledTask(nanoTime());
    }

    /**
     * Return the {@link Runnable} which is ready to be executed with the given {@code nanoTime}.
     * You should use {@link #nanoTime()} to retrieve the correct {@code nanoTime}.
     */
    protected final Runnable pollScheduledTask(long nanoTime) {
        assert inEventLoop();

        // 获得队列首个定时任务。不会从队列中，移除该任务
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        // 直接返回，若获取不到
        if (scheduledTask == null || scheduledTask.deadlineNanos() - nanoTime > 0) {
            return null;
        }
        // 在指定时间内，则返回该任务
        scheduledTaskQueue.remove();
        scheduledTask.setConsumed();
        return scheduledTask;
    }

    /**
     * Return the nanoseconds until the next scheduled task is ready to be run or {@code -1} if no task is scheduled.
     */
    protected final long nextScheduledTaskNano() {
        // 获得队列首个定时任务。不会从队列中，移除该任务
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        // 距离当前时间，还要多久可执行。若为负数，直接返回 0 。实际等价，ScheduledFutureTask#delayNanos() 方法。
        return scheduledTask != null ? scheduledTask.delayNanos() : -1;
    }

    /**
     * 获取下一个调度任务的执行时间
     * Return the deadline (in nanoseconds) when the next scheduled task is ready to be run or {@code -1}
     * if no task is scheduled.
     */
    protected final long nextScheduledTaskDeadlineNanos() {
        //取定时任务队列头
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        //获取运行时间, 如果没有默认给 -1
        return scheduledTask != null ? scheduledTask.deadlineNanos() : -1;
    }

    /**
     * 获取第一个定时任务
     */
    final ScheduledFutureTask<?> peekScheduledTask() {
        //定时任务队列
        Queue<ScheduledFutureTask<?>> scheduledTaskQueue = this.scheduledTaskQueue;
        //取队列头部
        return scheduledTaskQueue != null ? scheduledTaskQueue.peek() : null;
    }

    /**
     * Returns {@code true} if a scheduled task is ready for processing.
     */
    protected final boolean hasScheduledTasks() {
        // 获得队列首个定时任务。不会从队列中，移除该任务
        ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
        // 判断该任务是否到达可执行的时间
        return scheduledTask != null && scheduledTask.deadlineNanos() <= nanoTime();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        validateScheduled0(delay, unit);

        //创建定时任务, 并且提交
        return schedule(new ScheduledFutureTask<Void>(
                this,
                command,
                deadlineNanos(unit.toNanos(delay))));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(callable, "callable");
        ObjectUtil.checkNotNull(unit, "unit");
        if (delay < 0) {
            delay = 0;
        }
        validateScheduled0(delay, unit);

        //创建定时任务, 并且提交
        return schedule(new ScheduledFutureTask<V>(this, callable, deadlineNanos(unit.toNanos(delay))));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (period <= 0) {
            throw new IllegalArgumentException(
                    String.format("period: %d (expected: > 0)", period));
        }
        validateScheduled0(initialDelay, unit);
        validateScheduled0(period, unit);

        //创建定时任务, 并且提交
        return schedule(new ScheduledFutureTask<Void>(
                this, command, deadlineNanos(unit.toNanos(initialDelay)), unit.toNanos(period)));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(unit, "unit");
        if (initialDelay < 0) {
            throw new IllegalArgumentException(
                    String.format("initialDelay: %d (expected: >= 0)", initialDelay));
        }
        if (delay <= 0) {
            throw new IllegalArgumentException(
                    String.format("delay: %d (expected: > 0)", delay));
        }

        validateScheduled0(initialDelay, unit);
        validateScheduled0(delay, unit);

        //创建定时任务, 并且提交
        return schedule(new ScheduledFutureTask<Void>(
                this, command, deadlineNanos(unit.toNanos(initialDelay)), -unit.toNanos(delay)));
    }

    @SuppressWarnings("deprecation")
    private void validateScheduled0(long amount, TimeUnit unit) {
        validateScheduled(amount, unit);
    }

    /**
     * Sub-classes may override this to restrict the maximal amount of time someone can use to schedule a task.
     *
     * @deprecated will be removed in the future.
     */
    @Deprecated
    protected void validateScheduled(long amount, TimeUnit unit) {
        // NOOP
    }

    /**
     * 添加入任务队列, 并且重新设置任务id
     */
    final void scheduleFromEventLoop(final ScheduledFutureTask<?> task) {
        // nextTaskId a long and so there is no chance it will overflow back to 0
        scheduledTaskQueue().add(task.setId(++nextTaskId));
    }

    private <V> ScheduledFuture<V> schedule(final ScheduledFutureTask<V> task) {
        if (inEventLoop()) {
            // 添加到定时任务队列
            scheduleFromEventLoop(task);
        } else {
            //获取执行时间
            final long deadlineNanos = task.deadlineNanos();
            //调度任务提交前的回调
            // task will add itself to scheduled task queue when run if not expired
            if (beforeScheduledTaskSubmitted(deadlineNanos)) {
                //beforeScheduledTaskSubmitted() 方法在 NioEventLoop 中会判断当前任务的执行时候会不会
                // 小于目前队列中最小的执行时间, 如果小于则需要唤醒 Selector 重新设置定时时间

                //任务执行时, 会自动添加到定时队列, 可以看 ScheduledFutureTask#run 方法
                // SingleThreadEventExecutor#execute 中的实现会唤醒 Selector
                execute(task);
            } else {
                // SingleThreadEventExecutor#lazyExecute 中的实现不会唤醒 Selector, 也就是不用重新设置 Selector 阻塞的超时时间
                lazyExecute(task);
                //再次确定是否需要唤醒, 避免定时队头任务在并发情况一下被改
                // Second hook after scheduling to facilitate race-avoidance
                if (afterScheduledTaskSubmitted(deadlineNanos)) {
                    //SingleThreadEventExecutor#execute 中的实现会唤醒 Selector
                    //添加一个空任务, 主要是为了唤醒 Selector
                    execute(WAKEUP_TASK);
                }
            }
        }

        return task;
    }

    final void removeScheduled(final ScheduledFutureTask<?> task) {
        assert task.isCancelled();
        if (inEventLoop()) {
            // 移除出定时任务队列
            scheduledTaskQueue().removeTyped(task);
        } else {
            //执行任务的时候, 如果发现任务已经取消, 则会先删除任务队列的任务
            // task will remove itself from scheduled task queue when it runs
            lazyExecute(task);
        }
    }

    /**
     * Called from arbitrary non-{@link EventExecutor} threads prior to scheduled task submission.
     * Returns {@code true} if the {@link EventExecutor} thread should be woken immediately to
     * process the scheduled task (if not already awake).
     * <p>
     * If {@code false} is returned, {@link #afterScheduledTaskSubmitted(long)} will be called with
     * the same value <i>after</i> the scheduled task is enqueued, providing another opportunity
     * to wake the {@link EventExecutor} thread if required.
     *
     * @param deadlineNanos deadline of the to-be-scheduled task
     *     relative to {@link AbstractScheduledEventExecutor#nanoTime()}
     * @return {@code true} if the {@link EventExecutor} thread should be woken, {@code false} otherwise
     */
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }

    /**
     * See {@link #beforeScheduledTaskSubmitted(long)}. Called only after that method returns false.
     *
     * @param deadlineNanos relative to {@link AbstractScheduledEventExecutor#nanoTime()}
     * @return  {@code true} if the {@link EventExecutor} thread should be woken, {@code false} otherwise
     */
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        return true;
    }
}
