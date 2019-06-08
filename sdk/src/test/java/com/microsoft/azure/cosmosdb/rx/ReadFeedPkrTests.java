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

import com.microsoft.azure.cosmos.CosmosClientBuilder;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

import com.microsoft.azure.cosmos.CosmosBridgeInternal;
import com.microsoft.azure.cosmos.CosmosContainer;
import com.microsoft.azure.cosmos.CosmosContainerRequestOptions;
import com.microsoft.azure.cosmos.CosmosDatabase;
import com.microsoft.azure.cosmosdb.FeedOptions;
import com.microsoft.azure.cosmosdb.FeedResponse;
import com.microsoft.azure.cosmosdb.PartitionKeyRange;
import reactor.core.publisher.Flux;


public class ReadFeedPkrTests extends TestSuiteBase {

    private CosmosDatabase createdDatabase;
    private CosmosContainer createdCollection;

    private AsyncDocumentClient client;
    
    @Factory(dataProvider = "clientBuildersWithDirect")
    public ReadFeedPkrTests(CosmosClientBuilder clientBuilder) {
        this.clientBuilder = clientBuilder;
    }

    @Test(groups = { "emulator" }, timeOut = FEED_TIMEOUT)
    public void readPartitionKeyRanges() throws Exception {

        FeedOptions options = new FeedOptions();
        options.setMaxItemCount(2);

        Flux<FeedResponse<PartitionKeyRange>> feedObservable = client.readPartitionKeyRanges(getCollectionLink(), options);

        FeedResponseListValidator<PartitionKeyRange> validator = new FeedResponseListValidator.Builder<PartitionKeyRange>()
                .totalSize(1)
                .numberOfPages(1)
                .build();
        validateQuerySuccess(feedObservable, validator, FEED_TIMEOUT);
    }

    @BeforeClass(groups = { "emulator" }, timeOut = SETUP_TIMEOUT)
    public void beforeClass() {
        client = CosmosBridgeInternal.getAsyncDocumentClient(clientBuilder.build());
        createdDatabase = getSharedCosmosDatabase(clientBuilder.build());
        createdCollection = createCollection(createdDatabase,
                                             getCollectionDefinition(),
                                             new CosmosContainerRequestOptions());
    }

    @AfterClass(groups = { "emulator" }, timeOut = SETUP_TIMEOUT, alwaysRun = true)
    public void afterClass() {
        safeDeleteCollection(createdCollection);
        client.close();
    }

    private String getCollectionLink() {
        return "dbs/" + createdDatabase.getId() + "/colls/" + createdCollection.getId();
    }
}
