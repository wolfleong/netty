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

import io.netty.util.internal.ObjectPool.Handle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

/**
 * 继承 AbstractReferenceCountedByteBuf 抽象类，对象池化的 ByteBuf 抽象基类，为基于对象池的 ByteBuf 实现类，提供公用的方法。
 */
abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    /**
     * Recycler 处理器，用于回收对象
     */
    private final Handle<PooledByteBuf<T>> recyclerHandle;

    /**
     * Chunk 对象
     */
    protected PoolChunk<T> chunk;
    /**
     * 从 Chunk 对象中分配的内存块所处的位置
     */
    protected long handle;
    /**
     * 内存空间。具体什么样的数据，通过子类设置泛型
     */
    protected T memory;
    /**
     * {@link #memory} 开始位置
     */
    protected int offset;
    /**
     * 容量
     */
    protected int length;
    /**
     * 占用 {@link #memory} 的大小
     */
    int maxLength;

    PoolThreadCache cache;
    /**
     * 临时 java.nio.ByteBuffer 对象
     */
    ByteBuffer tmpNioBuf;
    /**
     * ByteBuf 分配器对象
     */
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        //一般是基于 pooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    void initUnpooled(PoolChunk<T> chunk, int length) {
        //基于 unPoolooled 的 PoolChunk 对象，初始化 PooledByteBuf 对象。
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        // From PoolChunk 对象
        this.chunk = chunk;
        memory = chunk.memory;
        tmpNioBuf = nioBuffer;
        allocator = chunk.arena.parent;
        // 其他
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * 每次在重用 PooledByteBuf 对象时，需要调用该方法，重置属性
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     */
    final void reuse(int maxCapacity) {
        // 设置最大容量
        maxCapacity(maxCapacity);
        // 设置引用数量为 0
        resetRefCnt();
        // 重置读写索引为 0
        setIndex0(0, 0);
        // 重置读写标记位为 0
        discardMarks();
    }

    @Override
    public final int capacity() {
        return length;
    }

    @Override
    public int maxFastWritableBytes() {
        return Math.min(maxLength, maxCapacity()) - writerIndex;
    }

    @Override
    public final ByteBuf capacity(int newCapacity) {
        //如果新容量不变, 则不处理, 直接返回
        if (newCapacity == length) {
            ensureAccessible();
            return this;
        }
        // 校验新的容量，不能超过最大容量
        checkNewCapacity(newCapacity);

        // Chunk 内存，是池化
        if (!chunk.unpooled) {
            // If the request capacity does not require reallocation, just update the length of the memory.
            // 扩容
            if (newCapacity > length) {
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }

            // 缩容
            } else if (newCapacity > maxLength >>> 1 &&
                    (maxLength > 512 || newCapacity > maxLength - 16)) {
                //(newCapacity > maxLength >>> 1  表示 newCapacity 大于 maxLength 的一半才缩容,
                // 新容量小于当前容量，但是不到 memory 最大容量的一半，因为缩容相对释放不多，无需进行缩容

                // 如果 maxLength 大于 512
                // maxLength 小于等于 512 且 newCapacity > maxLength - 16 , 因为 Netty SubPage 最小是 16 ，如果小于等 16 ，无法缩容。

                // here newCapacity < length
                length = newCapacity;
                // 设置读写索引，避免超过最大容量
                trimIndicesToCapacity(newCapacity);
                return this;
            }
        }

        //下面是非池化的
        // 重新分配新的内存空间，并将数据复制到其中。并且，释放老的内存空间。
        // Reallocation required.
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    /**
     * 获取 tmpNioBuf, 没有初始化则初始化
     */
    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        // 为空，创建临时 ByteBuf 对象
        if (tmpNioBuf == null) {
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        } else {
            //清空
            tmpNioBuf.clear();
        }
        return tmpNioBuf;
    }

    /**
     * 创建一个 java.nio.ByteBuffer
     */
    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    /**
     * 当引用计数为 0 时，调用该方法，进行内存回收
     */
    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            // 重置属性
            final long handle = this.handle;
            this.handle = -1;
            memory = null;
            // 释放内存回 Arena 中
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
            // 回收对象
            recycle();
        }
    }

    private void recycle() {
        recyclerHandle.recycle(this);
    }

    /**
     * 获得指定位置在 memory 变量中的位置
     */
    protected final int idx(int index) {
        return offset + index;
    }

    /**
     * 获取 java.nio.Buffer
     * @param duplicate true 会复制, false 用临时共享的
     */
    final ByteBuffer _internalNioBuffer(int index, int length, boolean duplicate) {
        // memory 中的开始位置
        index = idx(index);
        //如果是复制, 则创建一个 ByteBuffer, 否则重用临时的
        ByteBuffer buffer = duplicate ? newInternalNioBuffer(memory) : internalNioBuffer();
        // position + limit 设置位置和大小限制
        buffer.limit(index + length).position(index);
        return buffer;
    }

    /**
     * 复制一个 java.nio.ByteBuffer
     */
    ByteBuffer duplicateInternalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return _internalNioBuffer(index, length, true);
    }

    @Override
    public final ByteBuffer internalNioBuffer(int index, int length) {
        checkIndex(index, length);
        return _internalNioBuffer(index, length, false);
    }

    @Override
    public final int nioBufferCount() {
        //返回 ByteBuf 包含 ByteBuffer 数量为 1
        return 1;
    }

    @Override
    public final ByteBuffer nioBuffer(int index, int length) {
        //复制一个 ByteBuffer
        return duplicateInternalNioBuffer(index, length).slice();
    }

    @Override
    public final ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    @Override
    public final boolean isContiguous() {
        return true;
    }

    @Override
    public final int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length));
    }

    @Override
    public final int readBytes(GatheringByteChannel out, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false));
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        return out.write(duplicateInternalNioBuffer(index, length), position);
    }

    @Override
    public final int readBytes(FileChannel out, long position, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = out.write(_internalNioBuffer(readerIndex, length, false), position);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public final int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        try {
            return in.read(internalNioBuffer(index, length));
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public final int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        try {
            return in.read(internalNioBuffer(index, length), position);
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }
}
