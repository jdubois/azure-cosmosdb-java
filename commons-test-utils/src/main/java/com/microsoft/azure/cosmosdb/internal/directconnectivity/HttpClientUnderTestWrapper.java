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

package com.microsoft.azure.cosmosdb.internal.directconnectivity;

import com.microsoft.azure.cosmosdb.rx.internal.http.HttpClient;
import com.microsoft.azure.cosmosdb.rx.internal.http.HttpRequest;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.doAnswer;

/**
 * This is a helper class for capturing requests sent over a httpClient.
 */
public class HttpClientUnderTestWrapper {
    final private HttpClient origHttpClient;
    final private HttpClient spyHttpClient;

    public final List<HttpRequest> capturedRequests = Collections.synchronizedList(new ArrayList<>());

    public HttpClientUnderTestWrapper(HttpClient origHttpClient) {
        this.origHttpClient = origHttpClient;
        this.spyHttpClient = Mockito.spy(origHttpClient);

        initRequestCapture(spyHttpClient);
    }

    public HttpClient getSpyHttpClient() {
        return spyHttpClient;
    }

    private void initRequestCapture(HttpClient spyClient) {
        doAnswer(invocationOnMock -> {
            HttpRequest httpRequest = invocationOnMock.getArgumentAt(0, HttpRequest.class);
            capturedRequests.add(httpRequest);
            return origHttpClient.send(httpRequest);
        }).when(spyClient).send(Mockito.any(HttpRequest.class));
    }
}
