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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    /**
     * 原生的 Channel
     */
    private final SelectableChannel ch;
    /**
     * 感兴趣的事件
     */
    protected final int readInterestOp;
    /**
     * 原生的 SelectionKey
     */
    volatile SelectionKey selectionKey;
    /**
     * 表示正在等待读
     */
    boolean readPending;
    private final Runnable clearReadPendingRunnable = new Runnable() {
        @Override
        public void run() {
            clearReadPending0();
        }
    };

    /**
     * 目前正在连接远程地址的 ChannelPromise 对象。
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     */
    private ChannelPromise connectPromise;
    /**
     * 连接超时关闭的定时任务异步结果
     */
    private ScheduledFuture<?> connectTimeoutFuture;
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent);
        //保存 java.nio.ServerSocketChannel
        this.ch = ch;
        //感兴趣的事件
        this.readInterestOp = readInterestOp;
        try {
            //设置原生 Channel 非阻塞
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
                //如果发生异常则关闭 ch
                ch.close();
            } catch (IOException e2) {
                logger.warn(
                            "Failed to close a partially initialized socket.", e2);
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        setReadPending0(readPending);
                    }
                });
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * Set read pending to {@code false}.
     */
    protected final void clearReadPending() {
        //如果已经注册
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            //当前线程为 EventLoop 线程
            if (eventLoop.inEventLoop()) {
                clearReadPending0();
            } else {
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    private void clearReadPending0() {
        readPending = false;
        // 移除对“读”事件的感兴趣。
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        /**
         * 移除对“读”事件的感兴趣
         */
        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            // 忽略，如果 SelectionKey 不合法，例如已经取消
            if (!key.isValid()) {
                return;
            }
            // 移除对“读”事件的感兴趣。
            int interestOps = key.interestOps();
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }

        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                //目前有正在连接远程地址的 ChannelPromise ，则直接抛出异常，禁止同时发起多个连接。
                if (connectPromise != null) {
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                //记录 Channel 是否激活
                boolean wasActive = isActive();
                //执行连接远程地址
                if (doConnect(remoteAddress, localAddress)) {
                    //若连接成功
                    fulfillConnectPromise(promise, wasActive);
                    //连接不成功, 则执行 else
                } else {
                    //记录 connectPromise
                    connectPromise = promise;
                    //记录 requestedRemoteAddress
                    requestedRemoteAddress = remoteAddress;

                    //使用 EventLoop 发起定时任务，监听连接远程地址超时。若连接超时，则回调通知 connectPromise 超时异常。
                    // Schedule connect timeout.
                    //默认 30000 毫秒
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    //如果有超时时间
                    if (connectTimeoutMillis > 0) {
                        //添加定时调度任务
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                                //获取正在连接的异步结果
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                //创建一个异常
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                //如果尝试设置异常失败
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    //关闭
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    //添加监听器
                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            //如果监听远程地址取消
                            if (future.isCancelled()) {
                                //取消定时任务
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false);
                                }
                                //置空 connectPromise
                                connectPromise = null;
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                //回调通知 promise 发生异常
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        /**
         * 通知 Promise 连接成功完成
         */
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            //如果 promise 为 null , 直接返回
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            //记录 Channel 是否 Active
            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive();

            //尝试设置异步结果为成功
            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess();

            //如果是首次激活, 则触发 channelActive
            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
                pipeline().fireChannelActive();
            }

            //如果不成功, 则关闭
            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        /**
         * 通知 Promise 连接失败
         */
        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            //如果 promise 为 null, 则不处理
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            //设置异步结果回调异常
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            //关闭
            closeIfClosed();
        }

        /**
         * 完成连接
         */
        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            //判断当前线程在 EventLoop 中
            assert eventLoop().inEventLoop();

            try {
                //记录当前 Channel 是否激活
                boolean wasActive = isActive();
                //执行完成连接
                doFinishConnect();
                //通知 connectPromise 连接成功
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
                //通知 Promise 连接异常
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                //如果定时任务的异步结果不为 null
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                if (connectTimeoutFuture != null) {
                    //取消执行
                    connectTimeoutFuture.cancel(false);
                }
                //重置为 null
                connectPromise = null;
            }
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            //是否已经处于 flush 准备中, 也就是对 OP_WRITE 不感兴趣的时候就是可以 flush
            if (!isFlushPending()) {
                super.flush0();
            }
        }

        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        /**
         * 判断，是否已经处于 flush 准备中.
         *
         * 所以在 Netty 的实现中，默认 Channel 是可写的，当写入失败的时候，再去注册 SelectionKey.OP_WRITE 事件。这意味着什么呢？
         * 在 #flush() 方法中，如果写入数据到 Channel 失败，会通过注册 SelectionKey.OP_WRITE 事件，然后在轮询到 Channel 可写 时，再“回调” #forceFlush() 方法
         *
         * 这就是这段代码的目的，如果处于对 SelectionKey.OP_WRITE 事件感兴趣，说明 Channel 此时是不可写的，
         * 那么调用父类 AbstractUnsafe 的 #flush0() 方法，也没有意义，所以就不调用。
         */
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            // selectionKey.isValid() 合法
            // 对 SelectionKey.OP_WRITE 事件感兴趣
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    @Override
    protected void doRegister() throws Exception {
        //是否执行过 select 操作
        boolean selected = false;
        for (;;) {
            try {
                //Java 原生的 Channel 注册到 Selector 中, 并返回 SelectionKey 存起来
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
                //如果有 CancelledKeyException 异常
            } catch (CancelledKeyException e) {
                //如果还没有执行过 selectNow()
                if (!selected) {
                    //这里进行强制 selectNow , 是因为这个被返回的 SelectionKey 已经被取消了, 但还缓存着没被删除,
                    // 有可能是没有执行过 select 操作, 所以执行一下 selectNow 清除缓存, 然后再次尝试注册
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    //强制执行 selectNow
                    eventLoop().selectNow();
                    //标记为已经执行 selectNow
                    selected = true;
                } else {
                    //如果已经执行过 selectNow , 则将异常直接抛出
                    //执行过 select 操作了如果还有被取消的 SelectionKey 返回, 那这个 selectionKey 依然被缓存着, 可能是jdk的bug
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    @Override
    protected void doDeregister() throws Exception {
        //执行取消注册
        eventLoop().cancel(selectionKey());
    }

    @Override
    protected void doBeginRead() throws Exception {
        //获取 SelectionKey
        // Channel.read() or ChannelHandlerContext.read() was called
        final SelectionKey selectionKey = this.selectionKey;
        //判断 SelectionKey 是否有效
        if (!selectionKey.isValid()) {
            return;
        }

        //设置正在读取中, 也就是添加了感兴趣的读事件
        readPending = true;

        //获取感兴取的事件
        final int interestOps = selectionKey.interestOps();
        //检测如果没有读事件
        if ((interestOps & readInterestOp) == 0) {
            //增加读事件
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * 执行连接, 返回是否连接成功
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    @Override
    protected void doClose() throws Exception {
        // 通知 connectPromise 异常失败
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(new ClosedChannelException());
            connectPromise = null;
        }

        // 取消 connectTimeoutFuture 等待
        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
