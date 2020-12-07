/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport;

import org.elasticsearch.Version;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.bytes.CompositeBytesReference;
import org.elasticsearch.common.bytes.ReleasableBytesReference;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.PageCacheRecycler;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class InboundPipeline implements Releasable {

    private static final ThreadLocal<ArrayList<Object>> fragmentList = ThreadLocal.withInitial(ArrayList::new);
    private static final InboundMessage PING_MESSAGE = new InboundMessage(null, true);

    private final LongSupplier relativeTimeInMillis;
    private final StatsTracker statsTracker;
    private final InboundDecoder decoder;
    private final InboundAggregator aggregator;
    private final BiConsumer<TcpChannel, InboundMessage> messageHandler; /**{@link TcpTransport#inboundMessage} {@link InboundHandler} **/
    private Exception uncaughtException;
    private final ArrayDeque<ReleasableBytesReference> pending = new ArrayDeque<>(2);
    private boolean isClosed = false;

    public InboundPipeline(Version version, StatsTracker statsTracker, PageCacheRecycler recycler, LongSupplier relativeTimeInMillis,
                           Supplier<CircuitBreaker> circuitBreaker,
                           Function<String, RequestHandlerRegistry<TransportRequest>> registryFunction,
                           BiConsumer<TcpChannel, InboundMessage> messageHandler) {
        this(statsTracker, relativeTimeInMillis, new InboundDecoder(version, recycler),
            new InboundAggregator(circuitBreaker, registryFunction), messageHandler);
    }

    public InboundPipeline(StatsTracker statsTracker, LongSupplier relativeTimeInMillis, InboundDecoder decoder,
                           InboundAggregator aggregator, BiConsumer<TcpChannel, InboundMessage> messageHandler) {
        this.relativeTimeInMillis = relativeTimeInMillis;
        this.statsTracker = statsTracker;
        this.decoder = decoder;
        this.aggregator = aggregator;
        this.messageHandler = messageHandler;
    }

    @Override
    public void close() {
        isClosed = true;
        Releasables.closeWhileHandlingException(decoder, aggregator);
        Releasables.closeWhileHandlingException(pending);
        pending.clear();
    }

    public void handleBytes(TcpChannel channel, ReleasableBytesReference reference) throws IOException {
        if (uncaughtException != null) {
            throw new IllegalStateException("Pipeline state corrupted by uncaught exception", uncaughtException);
        }
        try {
            doHandleBytes(channel, reference);
        } catch (Exception e) {
            uncaughtException = e;
            throw e;
        }
    }

    public void doHandleBytes(TcpChannel channel, ReleasableBytesReference reference) throws IOException {
        channel.getChannelStats().markAccessed(relativeTimeInMillis.getAsLong());
        statsTracker.markBytesRead(reference.length());
        pending.add(reference.retain());    //reference调用引用数加1

        final ArrayList<Object> fragments = fragmentList.get();
        boolean continueHandling = true;

        while (continueHandling && isClosed == false) {
            boolean continueDecoding = true;
            while (continueDecoding && pending.isEmpty() == false) {
                try (ReleasableBytesReference toDecode = getPendingBytes()) {
                    final int bytesDecoded = decoder.decode(toDecode, fragments::add);
                    if (bytesDecoded != 0) {
                        releasePendingBytes(bytesDecoded);  //释放已经decoded的字节
                        if (fragments.isEmpty() == false && endOfMessage(fragments.get(fragments.size() - 1))) {
                            continueDecoding = false;
                        }
                    } else {
                        continueDecoding = false;       //bytesDecoded == 0
                    }
                }
            }

            if (fragments.isEmpty()) {
                continueHandling = false;      //一次ChannelRead完成，等待下一次
            } else {
                try {
                    forwardFragments(channel, fragments);
                } finally {
                    for (Object fragment : fragments) {
                        if (fragment instanceof ReleasableBytesReference) {
                            ((ReleasableBytesReference) fragment).close();//forwardFragments有增加引用，此处减少一次引用
                        }
                    }
                    fragments.clear();
                }
            }
        }
    }

    private void forwardFragments(TcpChannel channel, ArrayList<Object> fragments) throws IOException {
        for (Object fragment : fragments) {
            if (fragment instanceof Header) {
                assert aggregator.isAggregating() == false;
                aggregator.headerReceived((Header) fragment);   //已经收到header
            } else if (fragment == InboundDecoder.PING) {
                assert aggregator.isAggregating() == false;
                messageHandler.accept(channel, PING_MESSAGE);
            } else if (fragment == InboundDecoder.END_CONTENT) {
                assert aggregator.isAggregating();  //表示结束编译
                try (InboundMessage aggregated = aggregator.finishAggregation()) {
                    statsTracker.markMessageReceived();
                    messageHandler.accept(channel, aggregated); //返回聚合后的InboundMessage
                }
            } else {
                assert aggregator.isAggregating();
                assert fragment instanceof ReleasableBytesReference;
                aggregator.aggregate((ReleasableBytesReference) fragment);  //将部分ReleasableBytesReference 聚合起来
            }
        }
    }

    private boolean endOfMessage(Object fragment) {
        return fragment == InboundDecoder.PING || fragment == InboundDecoder.END_CONTENT || fragment instanceof Exception;
    }

    private ReleasableBytesReference getPendingBytes() {
        if (pending.size() == 1) {
            return pending.peekFirst().retain();
        } else {
            final ReleasableBytesReference[] bytesReferences = new ReleasableBytesReference[pending.size()];
            int index = 0;
            for (ReleasableBytesReference pendingReference : pending) {
                bytesReferences[index] = pendingReference.retain();
                ++index;
            }
            final Releasable releasable = () -> Releasables.closeWhileHandlingException(bytesReferences);
            return new ReleasableBytesReference(CompositeBytesReference.of(bytesReferences), releasable);
        }
    }

    private void releasePendingBytes(int bytesConsumed) {
        int bytesToRelease = bytesConsumed;
        while (bytesToRelease != 0) {
            try (ReleasableBytesReference reference = pending.pollFirst()) {    //从pending中弹出
                assert reference != null;
                if (bytesToRelease < reference.length()) {
                    pending.addFirst(reference.retainedSlice(bytesToRelease, reference.length() - bytesToRelease));
                    bytesToRelease -= bytesToRelease;       //释放pending中reference中的部分字节
                } else {
                    bytesToRelease -= reference.length();   //释放pending中的reference
                }
            }
        }
    }
}
