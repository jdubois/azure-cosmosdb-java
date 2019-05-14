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
 */
package com.microsoft.azure.cosmosdb.rx.internal;

import com.microsoft.azure.cosmosdb.ConnectionPolicy;
import com.microsoft.azure.cosmosdb.ConsistencyLevel;
import com.microsoft.azure.cosmosdb.ISessionContainer;
import com.microsoft.azure.cosmosdb.internal.QueryCompatibilityMode;
import com.microsoft.azure.cosmosdb.internal.UserAgentContainer;
import com.microsoft.azure.cosmosdb.rx.internal.http.HttpRequest;
import com.microsoft.azure.cosmosdb.rx.internal.http.HttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.doAnswer;

/**
 * This class in conjunction with {@link com.microsoft.azure.cosmosdb.rx.ClientUnderTestBuilder}
 * provides the functionality for spying the client behavior and the http requests sent.
 */
public class RxDocumentClientUnderTest extends RxDocumentClientImpl {

    public com.microsoft.azure.cosmosdb.rx.internal.http.HttpClient spyHttpClient;
    public com.microsoft.azure.cosmosdb.rx.internal.http.HttpClient origHttpClient;

    public List<HttpRequest> httpRequests = Collections.synchronizedList(new ArrayList<>());

    public RxDocumentClientUnderTest(URI serviceEndpoint,
                                     String masterKey,
                                     ConnectionPolicy connectionPolicy,
                                     ConsistencyLevel consistencyLevel,
                                     Configs configs) {
        super(serviceEndpoint, masterKey, connectionPolicy, consistencyLevel, configs);
        init();
    }

    RxGatewayStoreModel createRxGatewayProxy(
            ISessionContainer sessionContainer,
            ConsistencyLevel consistencyLevel,
            QueryCompatibilityMode queryCompatibilityMode,
            UserAgentContainer userAgentContainer,
            GlobalEndpointManager globalEndpointManager,
            com.microsoft.azure.cosmosdb.rx.internal.http.HttpClient rxOrigClient) {

        origHttpClient = rxOrigClient;
        spyHttpClient = Mockito.spy(rxOrigClient);

        doAnswer((Answer<Mono<HttpResponse>>) invocationOnMock -> {

            HttpMethod httpMethod =
                    invocationOnMock.getArgumentAt(0, HttpMethod.class);
            URL url = invocationOnMock.getArgumentAt(1, URL.class);

            HttpRequest httpRequest = new HttpRequest(httpMethod, url);

            httpRequests.add(httpRequest);

            return origHttpClient.send(httpRequest);
        }).when(spyHttpClient).send(Mockito.any(HttpRequest.class));

        return super.createRxGatewayProxy(sessionContainer,
                consistencyLevel,
                queryCompatibilityMode,
                userAgentContainer,
                globalEndpointManager,
                spyHttpClient);
    }
}
