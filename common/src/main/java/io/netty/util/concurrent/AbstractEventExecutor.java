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

import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

/**
 * EventExecutor 的基础类
 * Abstract base class for {@link EventExecutor} implementations.
 */
public abstract class AbstractEventExecutor extends AbstractExecutorService implements EventExecutor {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractEventExecutor.class);

    /**
     * 优雅半闭的默认静默时间, 单位: 秒
     */
    static final long DEFAULT_SHUTDOWN_QUIET_PERIOD = 2;
    /**
     * 优雅关闭的超时时间, 单位: 秒
     */
    static final long DEFAULT_SHUTDOWN_TIMEOUT = 15;

    /**
     * 所属的 EventExecutorGroup
     */
    private final EventExecutorGroup parent;
    /**
     * EventExecutor 数组, 只包含自己, 用于返回迭代器
     */
    private final Collection<EventExecutor> selfCollection = Collections.<EventExecutor>singleton(this);

    protected AbstractEventExecutor() {
        this(null);
    }

    protected AbstractEventExecutor(EventExecutorGroup parent) {
        this.parent = parent;
    }

    @Override
    public EventExecutorGroup parent() {
        return parent;
    }

    @Override
    public EventExecutor next() {
        //返回当前对象
        return this;
    }

    @Override
    public boolean inEventLoop() {
        return inEventLoop(Thread.currentThread());
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        //返回迭代器
        return selfCollection.iterator();
    }

    @Override
    public Future<?> shutdownGracefully() {
        return shutdownGracefully(DEFAULT_SHUTDOWN_QUIET_PERIOD, DEFAULT_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    public abstract void shutdown();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }

    @Override
    public <V> Promise<V> newPromise() {
        //创建 DefaultPromise 返回,
        return new DefaultPromise<V>(this);
    }

    @Override
    public <V> ProgressivePromise<V> newProgressivePromise() {
        // 创建 DefaultProgressivePromise 返回
        return new DefaultProgressivePromise<V>(this);
    }

    @Override
    public <V> Future<V> newSucceededFuture(V result) {
        //创建 SucceededFuture 返回
        return new SucceededFuture<V>(this, result);
    }

    @Override
    public <V> Future<V> newFailedFuture(Throwable cause) {
        //创建 FailedFuture 返回
        return new FailedFuture<V>(this, cause);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return (Future<?>) super.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return (Future<T>) super.submit(task, result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return (Future<T>) super.submit(task);
    }

    /**
     * 覆盖父类的方法
     * - 上面 submit 的方法都是基于 newTaskFor 方法来执行的
     */
    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        //创建 PromiseTask
        return new PromiseTask<T>(this, runnable, value);
    }

    /**
     * 覆盖父类的方法
     */
    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        //创建 PromiseTask
        return new PromiseTask<T>(this, callable);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay,
                                       TimeUnit unit) {
        //EventExecutor 不调度执行
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        //EventExecutor 不调度执行
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        //EventExecutor 不调度执行
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        //EventExecutor 不调度执行
        throw new UnsupportedOperationException();
    }

    /**
     * 安全地执行 Task , 主要是 catch 异常了, 将异常当作警告日志打出来
     * Try to execute the given {@link Runnable} and just log if it throws a {@link Throwable}.
     */
    protected static void safeExecute(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            logger.warn("A task raised an exception. Task: {}", task, t);
        }
    }

    /**
     * Like {@link #execute(Runnable)} but does not guarantee the task will be run until either
     * a non-lazy task is executed or the executor is shut down.
     *
     * This is equivalent to submitting a {@link EventExecutor.LazyRunnable} to
     * {@link #execute(Runnable)} but for an arbitrary {@link Runnable}.
     *
     * The default implementation just delegates to {@link #execute(Runnable)}.
     */
    @UnstableApi
    public void lazyExecute(Runnable task) {
        execute(task);
    }

    /**
     * Marker interface for {@link Runnable} to indicate that it should be queued for execution
     * but does not need to run immediately.
     */
    @UnstableApi
    public interface LazyRunnable extends Runnable { }
}
