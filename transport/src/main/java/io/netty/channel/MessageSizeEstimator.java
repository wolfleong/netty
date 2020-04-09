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
package io.netty.channel;

/**
 * 获取消息大小计算处理器的接口
 * Responsible to estimate the size of a message. The size represents approximately how much memory the message will
 * reserve in memory.
 */
public interface MessageSizeEstimator {

    /**
     * 创建 Handler 实例
     * Creates a new handle. The handle provides the actual operations.
     */
    Handle newHandle();

    /**
     * 消息大小计算处理器
     */
    interface Handle {

        /**
         * 计算消息的字节大小
         * Calculate the size of the given message.
         *
         * @param msg       The message for which the size should be calculated
         * @return size     The size in bytes. The returned size must be >= 0
         */
        int size(Object msg);
    }
}
