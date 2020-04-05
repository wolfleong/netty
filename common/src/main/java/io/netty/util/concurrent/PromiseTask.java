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

import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;

/**
 * PromiseTask 类是 DefaultPromise 类的 RunnableFuture 接口实现, 相当于适配器
 *  - 也就是将 DefaultPromise 适配成 RunnableFuture 的一种实现
 *  - 任务未完成前, task 是要执行的任务, 等任务处理完成后, task 是一种表示状态的占位对象
 * @param <V>
 */
class PromiseTask<V> extends DefaultPromise<V> implements RunnableFuture<V> {

    /**
     * 将 Runnable 适配成 Callable 的适配器
     */
    private static final class RunnableAdapter<T> implements Callable<T> {
        final Runnable task;
        final T result;

        RunnableAdapter(Runnable task, T result) {
            this.task = task;
            this.result = result;
        }

        @Override
        public T call() {
            task.run();
            return result;
        }

        @Override
        public String toString() {
            return "Callable(task: " + task + ", result: " + result + ')';
        }
    }

    //下面三个 Runnable 用于标记 task 的状态
    /**
     * 已经完成
     */
    private static final Runnable COMPLETED = new SentinelRunnable("COMPLETED");
    /**
     * 已经取消
     */
    private static final Runnable CANCELLED = new SentinelRunnable("CANCELLED");
    /**
     * 已经失败
     */
    private static final Runnable FAILED = new SentinelRunnable("FAILED");

    /**
     * 占位用的 Runnable , 没有逻辑
     */
    private static class SentinelRunnable implements Runnable {
        /**
         * 名称
         */
        private final String name;

        SentinelRunnable(String name) {
            this.name = name;
        }

        @Override
        public void run() { } // no-op

        @Override
        public String toString() {
            return name;
        }
    }

    //真正要执行的任务对象, 有结果后, 存对应的结果状态占位符
    // Strictly of type Callable<V> or Runnable
    private Object task;

    PromiseTask(EventExecutor executor, Runnable runnable, V result) {
        super(executor);
        task = result == null ? runnable : new RunnableAdapter<V>(runnable, result);
    }

    PromiseTask(EventExecutor executor, Runnable runnable) {
        super(executor);
        task = runnable;
    }

    PromiseTask(EventExecutor executor, Callable<V> callable) {
        super(executor);
        task = callable;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public final boolean equals(Object obj) {
        return this == obj;
    }

    /**
     * 执行真正的 task
     */
    @SuppressWarnings("unchecked")
    final V runTask() throws Exception {
        final Object task = this.task;
        if (task instanceof Callable) {
            return ((Callable<V>) task).call();
        }
        ((Runnable) task).run();
        return null;
    }

    @Override
    public void run() {
        try {
            //设置不可取消
            if (setUncancellableInternal()) {
                //执行任务
                V result = runTask();
                //设置成功结果
                setSuccessInternal(result);
            }
        } catch (Throwable e) {
            //设置失败
            setFailureInternal(e);
        }
    }

    /**
     * 用占位 Runnable 替换原来的 task
     */
    private boolean clearTaskAfterCompletion(boolean done, Runnable result) {
        if (done) {
            // The only time where it might be possible for the sentinel task
            // to be called is in the case of a periodic ScheduledFutureTask,
            // in which case it's a benign race with cancellation and the (null)
            // return value is not used.
            task = result;
        }
        return done;
    }

    @Override
    public final Promise<V> setFailure(Throwable cause) {
        throw new IllegalStateException();
    }

    protected final Promise<V> setFailureInternal(Throwable cause) {
        //设置失败
        super.setFailure(cause);
        //清空 task 任务
        clearTaskAfterCompletion(true, FAILED);
        return this;
    }

    @Override
    public final boolean tryFailure(Throwable cause) {
        return false;
    }

    protected final boolean tryFailureInternal(Throwable cause) {
        return clearTaskAfterCompletion(super.tryFailure(cause), FAILED);
    }

    @Override
    public final Promise<V> setSuccess(V result) {
        throw new IllegalStateException();
    }

    protected final Promise<V> setSuccessInternal(V result) {
        //设置结果
        super.setSuccess(result);
        //替换 task 为 COMPLETED
        clearTaskAfterCompletion(true, COMPLETED);
        return this;
    }

    @Override
    public final boolean trySuccess(V result) {
        return false;
    }

    protected final boolean trySuccessInternal(V result) {
        return clearTaskAfterCompletion(super.trySuccess(result), COMPLETED);
    }

    @Override
    public final boolean setUncancellable() {
        throw new IllegalStateException();
    }

    protected final boolean setUncancellableInternal() {
        return super.setUncancellable();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        //取消任务, 并设置 task 为 CANCELLED
        return clearTaskAfterCompletion(super.cancel(mayInterruptIfRunning), CANCELLED);
    }

    @Override
    public final boolean isCancelled() {
        return task == CANCELLED || super.isCancelled();
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" task: ")
                  .append(task)
                  .append(')');
    }
}
