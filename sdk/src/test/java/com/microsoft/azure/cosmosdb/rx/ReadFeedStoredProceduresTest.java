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
package com.microsoft.azure.cosmosdb.rx;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import com.microsoft.azure.cosmosdb.Database;
import com.microsoft.azure.cosmosdb.DocumentClientException;
import com.microsoft.azure.cosmosdb.DocumentCollection;
import com.microsoft.azure.cosmosdb.FeedOptions;
import com.microsoft.azure.cosmosdb.FeedResponse;
import com.microsoft.azure.cosmosdb.StoredProcedure;

import rx.Observable;

import javax.net.ssl.SSLException;

public class ReadFeedStoredProceduresTest extends TestSuiteBase {

    private Database createdDatabase;
    private DocumentCollection createdCollection;
    private List<StoredProcedure> createdStoredProcedures = new ArrayList<>();

    private AsyncDocumentClient client;

    @Factory(dataProvider = "clientBuildersWithDirect")
    public ReadFeedStoredProceduresTest(AsyncDocumentClient.Builder clientBuilder) {
        super(clientBuilder);
    }

    @Test(groups = { "simple" }, timeOut = FEED_TIMEOUT)
    public void readStoredProcedures() throws Exception {

        FeedOptions options = new FeedOptions();
        options.setMaxItemCount(2);

        Observable<FeedResponse<StoredProcedure>> feedObservable = client.readStoredProcedures(getCollectionLink(), options);

        int expectedPageSize = (createdStoredProcedures.size() + options.getMaxItemCount() - 1) / options.getMaxItemCount();

        FeedResponseListValidator<StoredProcedure> validator = new FeedResponseListValidator
                .Builder<StoredProcedure>()
                .totalSize(createdStoredProcedures.size())
                .exactlyContainsInAnyOrder(createdStoredProcedures
                        .stream()
                        .map(d -> d.getResourceId())
                        .collect(Collectors.toList()))
                .numberOfPages(expectedPageSize)
                .allPagesSatisfy(new FeedResponseValidator.Builder<StoredProcedure>()
                        .requestChargeGreaterThanOrEqualTo(1.0).build())
                .build();
        validateQuerySuccess(feedObservable, validator, FEED_TIMEOUT);
    }

    @BeforeClass(groups = { "simple" }, timeOut = SETUP_TIMEOUT)
    public void beforeClass() {
        client = this.clientBuilder().build();
        createdDatabase = SHARED_DATABASE;
        createdCollection = SHARED_SINGLE_PARTITION_COLLECTION_WITHOUT_PARTITION_KEY;
        truncateCollection(SHARED_SINGLE_PARTITION_COLLECTION_WITHOUT_PARTITION_KEY);

        for(int i = 0; i < 5; i++) {
            createdStoredProcedures.add(createStoredProcedures(client));
        }

        waitIfNeededForReplicasToCatchUp(clientBuilder());
    }

    @AfterClass(groups = { "simple" }, timeOut = SHUTDOWN_TIMEOUT, alwaysRun = true)
    public void afterClass() {
        safeClose(client);
    }

    public StoredProcedure createStoredProcedures(AsyncDocumentClient client) {
        StoredProcedure sproc = new StoredProcedure();
        sproc.setId(UUID.randomUUID().toString());
        sproc.setBody("function() {var x = 10;}");
        return client.createStoredProcedure(getCollectionLink(), sproc, null).toBlocking().single().getResource();
    }

    private String getCollectionLink() {
        return "dbs/" + getDatabaseId() + "/colls/" + getCollectionId();
    }

    private String getCollectionId() {
        return createdCollection.getId();
    }

    private String getDatabaseId() {
        return createdDatabase.getId();
    }
}
