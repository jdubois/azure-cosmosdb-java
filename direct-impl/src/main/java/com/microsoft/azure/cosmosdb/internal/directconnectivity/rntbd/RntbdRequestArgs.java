/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.common.base.Stopwatch;
import com.microsoft.azure.cosmosdb.rx.internal.RxDocumentServiceRequest;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonPropertyOrder({
    "transportRequestId", "origin", "replicaPath", "activityId", "operationType", "resourceType", "birthTime",
    "lifetime"
})
public final class RntbdRequestArgs {

    private static final AtomicLong instanceCount = new AtomicLong();
    private static final String simpleClassName = RntbdRequestArgs.class.getSimpleName();

    private final UUID activityId;
    private final long birthTime;
    private final Stopwatch lifetime;
    private final String origin;
    private final URI physicalAddress;
    private final String replicaPath;
    private final RxDocumentServiceRequest serviceRequest;
    private final long transportRequestId;

    public RntbdRequestArgs(final RxDocumentServiceRequest serviceRequest, final URI physicalAddress) {
        this.activityId = UUID.fromString(serviceRequest.getActivityId());
        this.birthTime = System.nanoTime();
        this.lifetime = Stopwatch.createStarted();
        this.origin = physicalAddress.getScheme() + "://" + physicalAddress.getAuthority();
        this.physicalAddress = physicalAddress;
        this.replicaPath = StringUtils.stripEnd(physicalAddress.getPath(), "/");
        this.serviceRequest = serviceRequest;
        this.transportRequestId = instanceCount.incrementAndGet();
    }

    public UUID getActivityId() {
        return this.activityId;
    }

    public long getBirthTime() {
        return this.birthTime;
    }

    @JsonSerialize(using = ToStringSerializer.class)
    public Duration getLifetime() {
        return this.lifetime.elapsed();
    }

    public String getOrigin() {
        return this.origin;
    }

    @JsonIgnore
    public URI getPhysicalAddress() {
        return this.physicalAddress;
    }

    public String getReplicaPath() {
        return this.replicaPath;
    }

    @JsonIgnore
    public RxDocumentServiceRequest getServiceRequest() {
        return this.serviceRequest;
    }

    public long getTransportRequestId() {
        return this.transportRequestId;
    }

    @Override
    public String toString() {
        return simpleClassName + '(' + RntbdObjectMapper.toJson(this) + ')';
    }

    public void traceOperation(final Logger logger, final ChannelHandlerContext context, final String operationName, final Object... args) {

        checkNotNull(logger, "logger");

        if (logger.isTraceEnabled()) {
            final BigDecimal lifetime = BigDecimal.valueOf(this.lifetime.elapsed().toNanos(), 6);
            logger.trace("{},{},\"{}({})\",\"{}\",\"{}\"", this.birthTime, lifetime, operationName,
                Stream.of(args).map(arg ->
                    arg == null ? "null" : arg.toString()).collect(Collectors.joining(",")
                ),
                this, context
            );
        }
    }
}
