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

import com.google.common.collect.ImmutableList;
import com.microsoft.azure.cosmosdb.internal.OperationType;
import com.microsoft.azure.cosmosdb.rx.internal.RxDocumentServiceRequest;
import org.apache.commons.collections4.map.HashedMap;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

abstract public class EndpointMock {

    TransportClientWrapper transportClientWrapper;
    AddressSelectorWrapper addressSelectorWrapper;

    public EndpointMock(AddressSelectorWrapper addressSelectorWrapper, TransportClientWrapper transportClientWrapper) {
        this.addressSelectorWrapper = addressSelectorWrapper;
        this.transportClientWrapper = transportClientWrapper;
    }

    public static class EndpointMockVerificationBuilder {
        public static EndpointMockVerificationBuilder builder() {
            return new EndpointMockVerificationBuilder();
        }

        private AddressSelectorWrapper.InOrderVerificationBuilder addressSelectorVerificationBuilder;
        private TransportClientWrapper.TransportClientWrapperVerificationBuilder transportClientValidation;

        public EndpointMockVerificationBuilder withAddressSelectorValidation(AddressSelectorWrapper.InOrderVerificationBuilder addressSelectorBuilder) {
            addressSelectorVerificationBuilder = addressSelectorBuilder;
            return this;
        }

        public EndpointMockVerificationBuilder withTransportClientValidation(TransportClientWrapper.TransportClientWrapperVerificationBuilder transportClientValidation) {
            this.transportClientValidation = transportClientValidation;
            return this;
        }

        public void execute(EndpointMock endpointMock) {
            this.addressSelectorVerificationBuilder.execute(endpointMock.addressSelectorWrapper);
            this.transportClientValidation.execute(endpointMock.transportClientWrapper);
        }
    }


    public void validate(EndpointMockVerificationBuilder verificationBuilder) {
        this.addressSelectorWrapper.validate();
        this.transportClientWrapper.validate();
        if (verificationBuilder != null) {
            verificationBuilder.execute(this);
        }
    }

    public static Builder.NoSecondaryReplica noSecondaryReplicaBuilder() {
        return new Builder.NoSecondaryReplica();
    }

    abstract static class Builder {

        class ReplicasWithSameSpeed extends Builder {

            URI primary;
            List<URI> secondaries = new ArrayList<>();
            StoreResponse headStoreResponse;
            StoreResponse readStoreResponse;

            ReplicasWithSameSpeed addPrimary(URI replicaAddress) {
                primary = replicaAddress;
                return this;
            }

            ReplicasWithSameSpeed addSecondary(URI replicaAddress) {
                secondaries.add(replicaAddress);
                return this;
            }

            ReplicasWithSameSpeed storeResponseOnRead(StoreResponse storeResponse) {
                this.readStoreResponse = storeResponse;
                return this;
            }

            ReplicasWithSameSpeed storeResponseOnHead(StoreResponse storeResponse) {
                this.headStoreResponse = storeResponse;
                return this;
            }

            public EndpointMock build() {
                TransportClientWrapper.Builder.ReplicaResponseBuilder transportClientWrapperBuilder = TransportClientWrapper.Builder.replicaResponseBuilder();

                ImmutableList<URI> replicas = ImmutableList.<URI>builder().add(primary).addAll(secondaries).build();

                for(URI replica: replicas) {
                    transportClientWrapperBuilder.addReplica(replica, (i, request) -> {
                        if (request.getOperationType() == OperationType.Head || request.getOperationType() == OperationType.HeadFeed) {
                            return headStoreResponse;
                        } else {
                            return readStoreResponse;
                        }
                    });
                }

                AddressSelectorWrapper addressSelectorWrapper = AddressSelectorWrapper.Builder.Simple.create().withPrimary(primary)
                        .withSecondary(secondaries).build();

                return new EndpointMock(addressSelectorWrapper, transportClientWrapperBuilder.build()) {};
            }
        }

        class QuorumNotMetSecondaryReplicasDisappear {
            URI primary;
            Map<URI, Function2WithCheckedException<Integer, RxDocumentServiceRequest, Boolean>> disappearDictionary = new HashedMap<>();
            public QuorumNotMetSecondaryReplicasDisappear primaryReplica(URI primaryReplica) {
                this.primary = primaryReplica;
                return this;
            }

            public QuorumNotMetSecondaryReplicasDisappear secondaryReplicasDisappearWhen(URI secondary,
                                                               Function2WithCheckedException<Integer, RxDocumentServiceRequest, Boolean> disappearPredicate) {
                disappearDictionary.put(secondary, disappearPredicate);
                return this;
            }

            public QuorumNotMetSecondaryReplicasDisappear secondaryReplicasDisappearAfter(URI secondary, int attempt) {
                disappearDictionary.put(secondary, (i, r) -> i >= attempt);
                return this;
            }
        }

        static public class NoSecondaryReplica extends Builder {
            private long LOCAL_LSN = 19;
            private long LSN = 52;
            private URI defaultPrimaryURI = URI.create("primary");
            private URI primary = defaultPrimaryURI;
            private StoreResponse defaultResponse = StoreResponseBuilder.create()
                    .withLSN(LSN)
                    .withLocalLSN(LOCAL_LSN)
                    .withHeader(WFConstants.BackendHeaders.CURRENT_REPLICA_SET_SIZE, "1")
                    .withHeader(WFConstants.BackendHeaders.QUORUM_ACKED_LSN, Long.toString(LSN))
                    .withHeader(WFConstants.BackendHeaders.QUORUM_ACKED_LOCAL_LSN, Long.toString(LOCAL_LSN))
                    .withRequestCharge(0)
                    .build();

            private StoreResponse headStoreResponse = defaultResponse;
            private StoreResponse readStoreResponse = defaultResponse;
            private Function1WithCheckedException<RxDocumentServiceRequest, StoreResponse> storeResponseFunc;

            public NoSecondaryReplica primaryReplica(URI primaryReplica) {
                this.primary = primaryReplica;
                return this;
            }

            public NoSecondaryReplica response(StoreResponse storeResponse) {
                this.readStoreResponse = storeResponse;
                this.headStoreResponse = storeResponse;
                return this;
            }

            public NoSecondaryReplica response(Function1WithCheckedException<RxDocumentServiceRequest, StoreResponse> storeResponseFunc) {
                this.storeResponseFunc = storeResponseFunc;
                return this;
            }

            public EndpointMock build() {

                TransportClientWrapper.Builder.ReplicaResponseBuilder transportClientWrapperBuilder = TransportClientWrapper.Builder.replicaResponseBuilder();

                ImmutableList<URI> replicas = ImmutableList.<URI>builder().add(primary).build();

                for(URI replica: replicas) {
                    transportClientWrapperBuilder.addReplica(replica, (i, request) -> {

                        if (storeResponseFunc != null) {
                            return storeResponseFunc.apply(request);
                        }

                        if (request.getOperationType() == OperationType.Head || request.getOperationType() == OperationType.HeadFeed) {
                            return headStoreResponse;
                        } else {
                            return readStoreResponse;
                        }
                    });
                }

                AddressSelectorWrapper addressSelectorWrapper = AddressSelectorWrapper.Builder.Simple.create().withPrimary(primary)
                        .withSecondary(ImmutableList.of()).build();

                return new EndpointMock(addressSelectorWrapper, transportClientWrapperBuilder.build()) {};
            }
        }

        static public class NoSecondaryReplica_TwoSecondaryReplicasGoLiveAfterFirstHitOnPrimary extends Builder {
            private long LOCAL_LSN = 19;
            private long LSN = 52;
            private URI primary = URI.create("primary");
            private ImmutableList<URI> secondaryReplicas = ImmutableList.of(URI.create("secondary1"), URI.create("secondary2"));
            private StoreResponse primaryDefaultResponse = StoreResponseBuilder.create()
                    .withLSN(LSN)
                    .withLocalLSN(LOCAL_LSN)
                    .withHeader(WFConstants.BackendHeaders.CURRENT_REPLICA_SET_SIZE, "3")
                    .withHeader(WFConstants.BackendHeaders.QUORUM_ACKED_LSN, Long.toString(LSN))
                    .withHeader(WFConstants.BackendHeaders.QUORUM_ACKED_LOCAL_LSN, Long.toString(LOCAL_LSN))
                    .withRequestCharge(0)
                    .build();

            private StoreResponse secondaryDefaultResponse = StoreResponseBuilder.create()
                    .withLSN(LSN)
                    .withLocalLSN(LOCAL_LSN)
                    .withHeader(WFConstants.BackendHeaders.QUORUM_ACKED_LSN, Long.toString(LSN))
                    .withHeader(WFConstants.BackendHeaders.QUORUM_ACKED_LOCAL_LSN, Long.toString(LOCAL_LSN))
                    .withRequestCharge(0)
                    .build();
            Map<URI, Function1WithCheckedException<RxDocumentServiceRequest, StoreResponse>> secondaryResponseFunc =
                    new HashMap<>();


            public NoSecondaryReplica_TwoSecondaryReplicasGoLiveAfterFirstHitOnPrimary primaryReplica(URI primaryReplica) {
                this.primary = primaryReplica;
                return this;
            }

            public NoSecondaryReplica_TwoSecondaryReplicasGoLiveAfterFirstHitOnPrimary responseFromSecondary(
                    URI replica,
                    Function1WithCheckedException<RxDocumentServiceRequest, StoreResponse> func) {
                secondaryResponseFunc.put(replica, func);
                return this;
            }

            public EndpointMock build() {

                TransportClientWrapper.Builder.ReplicaResponseBuilder transportClientWrapperBuilder = TransportClientWrapper.Builder.replicaResponseBuilder();

                transportClientWrapperBuilder.addReplica(primary, (i, request) -> {
                    return primaryDefaultResponse;
                });

                transportClientWrapperBuilder.addReplica(secondaryReplicas.get(0), (i, request) -> {
                    return secondaryDefaultResponse;
                });

                transportClientWrapperBuilder.addReplica(secondaryReplicas.get(1), (i, request) -> {
                    return secondaryDefaultResponse;
                });

                AddressSelectorWrapper addressSelectorWrapper = AddressSelectorWrapper.Builder.Simple.create().withPrimary(primary)
                        .withSecondary(ImmutableList.of()).build();

                return new EndpointMock(addressSelectorWrapper, transportClientWrapperBuilder.build()){};
            }
        }

        public abstract EndpointMock build() ;
    }
}
