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
package io.netty.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A special {@link ChannelInboundHandler} which offers an easy way to initialize a {@link Channel} once it was
 * registered to its {@link EventLoop}.
 *
 * Implementations are most often used in the context of {@link Bootstrap#handler(ChannelHandler)} ,
 * {@link ServerBootstrap#handler(ChannelHandler)} and {@link ServerBootstrap#childHandler(ChannelHandler)} to
 * setup the {@link ChannelPipeline} of a {@link Channel}.
 *
 * <pre>
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer} {
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 * Be aware that this class is marked as {@link Sharable} and so the implementation must be safe to be re-used.
 *
 * 它是一个特殊的ChannelInboundHandler 实现类，用于 Channel 注册到 EventLoop 后，执行自定义的初始化操作。一般情况下，初始化自定义的 ChannelHandler 到 Channel 中
 *
 * @param <C>   A sub-type of {@link Channel}
 */
@Sharable
public abstract class ChannelInitializer<C extends Channel> extends ChannelInboundHandlerAdapter {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelInitializer.class);
    // We use a Set as a ChannelInitializer is usually shared between all Channels in a Bootstrap /
    // ServerBootstrap. This way we can reduce the memory usage compared to use Attributes.
    /**
     * 由于 ChannelInitializer 可以在 Bootstrap/ServerBootstrap 的所有通道中共享，所以我们用一个 ConcurrentMap 作为初始化器。
     * 这种方式，相对于使用 {@link io.netty.util.Attribute} 方式，减少了内存的使用。
     */
    private final Set<ChannelHandlerContext> initMap = Collections.newSetFromMap(
            new ConcurrentHashMap<ChannelHandlerContext, Boolean>());

    /**
     * This method will be called once the {@link Channel} was registered. After the method returns this instance
     * will be removed from the {@link ChannelPipeline} of the {@link Channel}.
     *
     * @param ch            the {@link Channel} which was registered.
     * @throws Exception    is thrown if an error occurs. In that case it will be handled by
     *                      {@link #exceptionCaught(ChannelHandlerContext, Throwable)} which will by default close
     *                      the {@link Channel}.
     */
    protected abstract void initChannel(C ch) throws Exception;

    /**
     * 在 channelRegistered 调用 initChannel
     */
    @Override
    @SuppressWarnings("unchecked")
    public final void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // Normally this method will never be called as handlerAdded(...) should call initChannel(...) and remove
        // the handler.
        //初始化 Channel
        if (initChannel(ctx)) {
            // we called initChannel(...) so we need to call now pipeline.fireChannelRegistered() to ensure we not
            // miss an event.
            //重新触发 Channel Registered 事件
            ctx.pipeline().fireChannelRegistered();

            // We are done with init the Channel, removing all the state for the Channel now.
            removeState(ctx);
        } else {
            // Called initChannel(...) before which is the expected behavior, so just forward the event.
            //继续向下一个节点的 Channel Registered 事件
            ctx.fireChannelRegistered();
        }
    }

    /**
     * Handle the {@link Throwable} by logging and closing the {@link Channel}. Sub-classes may override this.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (logger.isWarnEnabled()) {
            logger.warn("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
        }
        ctx.close();
    }

    /**
     * 为什么要在 handlerAdd 事件中调用 initChannel 呢, 因为只有在初始化时添加 ChannelHandler 到 ChannelPipeline
     * 才会在 Channel 注册时被触发 channelRegistered , 并且只触发一次, 而且在之后添加的 ChannelHandler 不会再次调
     * 用 channelRegistered 了, 但是会调用 handlerAdded().
     * todo wolfleong 其实我觉得可以不要 channelRegistered() 中调用 initChannel 也行, 因为最终 handlerAdded 会调用
     * {@inheritDoc} If override this method ensure you call super!
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // 已注册
        if (ctx.channel().isRegistered()) {
            // This should always be true with our current DefaultChannelPipeline implementation.
            // The good thing about calling initChannel(...) in handlerAdded(...) is that there will be no ordering
            // surprises if a ChannelInitializer will add another ChannelInitializer. This is as all handlers
            // will be added in the expected order.
            if (initChannel(ctx)) {

                // We are done with init the Channel, removing the initializer now.
                removeState(ctx);
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        initMap.remove(ctx);
    }

    /**
     * 执行行自定义的初始化操作
     */
    @SuppressWarnings("unchecked")
    private boolean initChannel(ChannelHandlerContext ctx) throws Exception {
        //通过 initMap 属性，解决并发问题
        if (initMap.add(ctx)) { // Guard against re-entrance.
            try {
                // 初始化通道
                initChannel((C) ctx.channel());
            } catch (Throwable cause) {
                // Explicitly call exceptionCaught(...) as we removed the handler before calling initChannel(...).
                // We do so to prevent multiple calls to initChannel(...).
                // 发生异常时，执行异常处理
                exceptionCaught(ctx, cause);
            } finally {
                // 从 pipeline 移除 ChannelInitializer
                ChannelPipeline pipeline = ctx.pipeline();
                if (pipeline.context(this) != null) {
                    pipeline.remove(this);
                }
            }
            // 初始化成功
            return true;
        }
        // 初始化失败
        return false;
    }

    /**
     *  // 从 initMap 移除
     */
    private void removeState(final ChannelHandlerContext ctx) {
        // The removal may happen in an async fashion if the EventExecutor we use does something funky.
        if (ctx.isRemoved()) {
            initMap.remove(ctx);
        } else {
            // The context is not removed yet which is most likely the case because a custom EventExecutor is used.
            // Let's schedule it on the EventExecutor to give it some more time to be completed in case it is offloaded.
            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    initMap.remove(ctx);
                }
            });
        }
    }
}
