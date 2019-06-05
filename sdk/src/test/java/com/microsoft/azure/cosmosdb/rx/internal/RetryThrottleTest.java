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
import com.microsoft.azure.cosmosdb.Database;
import com.microsoft.azure.cosmosdb.Document;
import com.microsoft.azure.cosmosdb.DocumentClientException;
import com.microsoft.azure.cosmosdb.DocumentCollection;
import com.microsoft.azure.cosmosdb.ResourceResponse;
import com.microsoft.azure.cosmosdb.RetryOptions;
import com.microsoft.azure.cosmosdb.internal.HttpConstants;
import com.microsoft.azure.cosmosdb.internal.OperationType;
import com.microsoft.azure.cosmosdb.internal.ResourceType;
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient;
import com.microsoft.azure.cosmosdb.rx.ResourceResponseValidator;
import com.microsoft.azure.cosmosdb.rx.TestConfigurations;
import org.mockito.stubbing.Answer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.doAnswer;

public class RetryThrottleTest extends TestSuiteBase {
    private final static int TIMEOUT = 10000;
    private final static int TOTAL_DOCS = 500;
    private final static int LARGE_TIMEOUT = 30000;

    private SpyClientUnderTestFactory.ClientWithGatewaySpy client;
    private Database database;
    private DocumentCollection collection;

    @Test(groups = { "long" }, timeOut = LARGE_TIMEOUT, enabled = false)
    public void retryCreateDocumentsOnSpike() throws Exception {
        ConnectionPolicy policy = new ConnectionPolicy();
        RetryOptions retryOptions = new RetryOptions();
        retryOptions.setMaxRetryAttemptsOnThrottledRequests(Integer.MAX_VALUE);
        retryOptions.setMaxRetryWaitTimeInSeconds(LARGE_TIMEOUT);
        policy.setRetryOptions(retryOptions);

        AsyncDocumentClient.Builder builder = new AsyncDocumentClient.Builder()
                .withServiceEndpoint(TestConfigurations.HOST)
                .withMasterKeyOrResourceToken(TestConfigurations.MASTER_KEY)
                .withConnectionPolicy(policy)
                .withConsistencyLevel(ConsistencyLevel.Eventual);

        client = SpyClientUnderTestFactory.createClientWithGatewaySpy(builder);

        // create a document to ensure collection is cached
        client.createDocument(getCollectionLink(collection), getDocumentDefinition(), null, false).blockFirst();

        List<Flux<ResourceResponse<Document>>> list = new ArrayList<>();
        for(int i = 0; i < TOTAL_DOCS; i++) {
            Flux<ResourceResponse<Document>> obs = client.createDocument(getCollectionLink(collection), getDocumentDefinition(), null, false);
            list.add(obs);
        }

        // registers a spy to count number of invocation
        AtomicInteger totalCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();

        doAnswer((Answer<Flux<RxDocumentServiceResponse>>) invocation -> {
            RxDocumentServiceRequest req = (RxDocumentServiceRequest) invocation.getArguments()[0];
            if (req.getResourceType() ==  ResourceType.Document && req.getOperationType() == OperationType.Create) {
                // increment the counter per Document Create operations
                totalCount.incrementAndGet();
            }
            return client.getOrigGatewayStoreModel().processMessage(req).doOnNext(rsp -> successCount.incrementAndGet());
        }).when(client.getSpyGatewayStoreModel()).processMessage(anyObject());

        List<ResourceResponse<Document>> rsps = Flux.merge(Flux.fromIterable(list), 100).collectList().single().block();
        System.out.println("total: " + totalCount.get());
        assertThat(rsps).hasSize(TOTAL_DOCS);
        assertThat(successCount.get()).isEqualTo(TOTAL_DOCS);
        System.out.println("total count is " + totalCount.get());
    }
    
    @Test(groups = { "long" }, timeOut = TIMEOUT, enabled = false)
    public void retryDocumentCreate() throws Exception {
        client = SpyClientUnderTestFactory.createClientWithGatewaySpy(createGatewayRxDocumentClient());

        // create a document to ensure collection is cached
        client.createDocument(getCollectionLink(collection),  getDocumentDefinition(), null, false).blockFirst();

        Document docDefinition = getDocumentDefinition();

        Flux<ResourceResponse<Document>> createObservable = client
                .createDocument(collection.getSelfLink(), docDefinition, null, false);
        AtomicInteger count = new AtomicInteger();

        doAnswer((Answer<Flux<RxDocumentServiceResponse>>) invocation -> {
            RxDocumentServiceRequest req = (RxDocumentServiceRequest) invocation.getArguments()[0];
            if (req.getOperationType() != OperationType.Create) {
                return client.getOrigGatewayStoreModel().processMessage(req);
            }
            int currentAttempt = count.getAndIncrement();
            if (currentAttempt == 0) {
                return Flux.error(new DocumentClientException(HttpConstants.StatusCodes.TOO_MANY_REQUESTS));
            } else {
                return client.getOrigGatewayStoreModel().processMessage(req);
            }
        }).when(client.getSpyGatewayStoreModel()).processMessage(anyObject());

        // validate
        ResourceResponseValidator<Document> validator = new ResourceResponseValidator.Builder<Document>()
                .withId(docDefinition.getId()).build();
        validateSuccess(createObservable, validator, TIMEOUT);
    }

    @AfterMethod(groups = { "long" }, enabled = false)
    private void afterMethod() {
        safeClose(client);
    }
    
    @BeforeClass(groups = { "long" }, timeOut = SETUP_TIMEOUT, enabled = false)
    public void beforeClass() {
        // set up the client
        database = SHARED_DATABASE;
        collection = SHARED_SINGLE_PARTITION_COLLECTION_WITHOUT_PARTITION_KEY;
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

    @AfterClass(groups = { "long" }, timeOut = SHUTDOWN_TIMEOUT, enabled = false)
    public void afterClass() {        
    }
}
