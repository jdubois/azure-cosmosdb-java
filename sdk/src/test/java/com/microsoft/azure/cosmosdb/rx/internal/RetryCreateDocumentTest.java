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

import com.google.common.collect.ImmutableMap;
import com.microsoft.azure.cosmosdb.Database;
import com.microsoft.azure.cosmosdb.Document;
import com.microsoft.azure.cosmosdb.DocumentClientException;
import com.microsoft.azure.cosmosdb.DocumentCollection;
import com.microsoft.azure.cosmosdb.Error;
import com.microsoft.azure.cosmosdb.ResourceResponse;
import com.microsoft.azure.cosmosdb.internal.HttpConstants;
import com.microsoft.azure.cosmosdb.internal.OperationType;
import com.microsoft.azure.cosmosdb.internal.ResourceType;
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient;
import com.microsoft.azure.cosmosdb.rx.FailureValidator;
import com.microsoft.azure.cosmosdb.rx.ResourceResponseValidator;
import com.microsoft.azure.cosmosdb.rx.TestSuiteBase;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;
import rx.Observable;

import javax.net.ssl.SSLException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doAnswer;

public class RetryCreateDocumentTest extends TestSuiteBase {

    private SpyClientUnderTestFactory.ClientWithGatewaySpy client;

    private Database database;
    private DocumentCollection collection;

    @Factory(dataProvider = "clientBuilders")
    public RetryCreateDocumentTest(AsyncDocumentClient.Builder clientBuilder) {
        super(clientBuilder);
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void retryDocumentCreate() throws Exception {
        // create a document to ensure collection is cached
        client.createDocument(collection.getSelfLink(),  getDocumentDefinition(), null, false).toBlocking().single();

        Document docDefinition = getDocumentDefinition();

        Observable<ResourceResponse<Document>> createObservable = client
                .createDocument(collection.getSelfLink(), docDefinition, null, false);
        AtomicInteger count = new AtomicInteger();

        doAnswer(new Answer< Observable<RxDocumentServiceResponse>>() {
            @Override
            public Observable<RxDocumentServiceResponse> answer(InvocationOnMock invocation) throws Throwable {
                RxDocumentServiceRequest req = (RxDocumentServiceRequest) invocation.getArguments()[0];
                if (req.getOperationType() != OperationType.Create) {
                    return client.getOrigGatewayStoreModel().processMessage(req);
                }

                int currentAttempt = count.getAndIncrement();
                if (currentAttempt == 0) {
                    Map<String, String> header = ImmutableMap.of(
                            HttpConstants.HttpHeaders.SUB_STATUS,
                            Integer.toString(HttpConstants.SubStatusCodes.PARTITION_KEY_MISMATCH));          

                    return Observable.error(new DocumentClientException(HttpConstants.StatusCodes.BADREQUEST, new Error() , header));
                } else {
                    return client.getOrigGatewayStoreModel().processMessage(req);
                }
            }
        }).when(client.getSpyGatewayStoreModel()).processMessage(anyObject());

        // validate
        ResourceResponseValidator<Document> validator = new ResourceResponseValidator.Builder<Document>()
                .withId(docDefinition.getId()).build();
        validateSuccess(createObservable, validator);
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void createDocument_noRetryOnNonRetriableFailure() throws Exception {

        AtomicInteger count = new AtomicInteger();
        doAnswer(new Answer< Observable<RxDocumentServiceResponse>>() {
            @Override
            public Observable<RxDocumentServiceResponse> answer(InvocationOnMock invocation) throws Throwable {
                RxDocumentServiceRequest req = (RxDocumentServiceRequest) invocation.getArguments()[0];

                if (req.getResourceType() != ResourceType.Document) {
                    return client.getOrigGatewayStoreModel().processMessage(req);
                }

                int currentAttempt = count.getAndIncrement();
                if (currentAttempt == 0) {
                    return client.getOrigGatewayStoreModel().processMessage(req);
                } else {
                    Map<String, String> header = ImmutableMap.of(
                            HttpConstants.HttpHeaders.SUB_STATUS,
                            Integer.toString(2));          

                    return Observable.error(new DocumentClientException(1, new Error() , header));
                }
            }
        }).when(client.getSpyGatewayStoreModel()).processMessage(anyObject());

        // create a document to ensure collection is cached
        client.createDocument(collection.getSelfLink(),  getDocumentDefinition(), null, false)
                .toBlocking()
                .single();

        Document docDefinition = getDocumentDefinition();

        Observable<ResourceResponse<Document>> createObservable = client
                .createDocument(collection.getSelfLink(), docDefinition, null, false);

        // validate
        FailureValidator validator = new FailureValidator.Builder().statusCode(1).subStatusCode(2).build();
        validateFailure(createObservable, validator, TIMEOUT);
    }

    @Test(groups = { "simple" }, timeOut = TIMEOUT)
    public void createDocument_failImmediatelyOnNonRetriable() throws Exception {
        // create a document to ensure collection is cached
        client.createDocument(collection.getSelfLink(),  getDocumentDefinition(), null, false).toBlocking().single();
        AtomicInteger count = new AtomicInteger();

        doAnswer(new Answer< Observable<RxDocumentServiceResponse>>() {
            @Override
            public Observable<RxDocumentServiceResponse> answer(InvocationOnMock invocation) throws Throwable {
                RxDocumentServiceRequest req = (RxDocumentServiceRequest) invocation.getArguments()[0];
                if (req.getOperationType() != OperationType.Create) {
                    return client.getOrigGatewayStoreModel().processMessage(req);
                }
                int currentAttempt = count.getAndIncrement();
                if (currentAttempt == 0) {
                    Map<String, String> header = ImmutableMap.of(
                            HttpConstants.HttpHeaders.SUB_STATUS,
                            Integer.toString(2));          

                    return Observable.error(new DocumentClientException(1, new Error() , header));
                } else {
                    return client.getOrigGatewayStoreModel().processMessage(req);
                }
            }
        }).when(client.getSpyGatewayStoreModel()).processMessage(anyObject());

        Document docDefinition = getDocumentDefinition();

        Observable<ResourceResponse<Document>> createObservable = client
                .createDocument(collection.getSelfLink(), docDefinition, null, false);
        // validate

        FailureValidator validator = new FailureValidator.Builder().statusCode(1).subStatusCode(2).build();
        validateFailure(createObservable.timeout(100, TimeUnit.MILLISECONDS), validator);
    }
    
    @BeforeMethod(groups = { "simple" })
    public void beforeMethod(Method method) {
        Mockito.reset(client.getSpyGatewayStoreModel());
    }

    @BeforeClass(groups = { "simple" }, timeOut = SETUP_TIMEOUT)
    public void beforeClass() {
        // set up the client        
        client = SpyClientUnderTestFactory.createClientWithGatewaySpy(clientBuilder());

        database = SHARED_DATABASE;
        collection = SHARED_SINGLE_PARTITION_COLLECTION;
    }

    private Document getDocumentDefinition() {
        String uuid = UUID.randomUUID().toString();
        Document doc = new Document(String.format("{ "
                + "\"id\": \"%s\", "
                + "\"mypk\": \"%s\", "
                + "\"sgmts\": [[6519456, 1471916863], [2498434, 1455671440]]"
                + "}"
                , uuid, uuid));
        return doc;
    }

    @AfterClass(groups = { "simple" }, timeOut = SHUTDOWN_TIMEOUT, alwaysRun = true)
    public void afterClass() {
        safeClose(client);
    }
}
