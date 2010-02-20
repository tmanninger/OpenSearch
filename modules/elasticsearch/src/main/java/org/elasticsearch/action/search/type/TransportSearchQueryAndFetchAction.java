/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.action.search.type;

import com.google.inject.Inject;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.node.Node;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.action.SearchServiceListener;
import org.elasticsearch.search.action.SearchServiceTransportAction;
import org.elasticsearch.search.controller.SearchPhaseController;
import org.elasticsearch.search.fetch.QueryFetchSearchResult;
import org.elasticsearch.search.internal.InternalSearchRequest;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.util.settings.Settings;

import java.util.Map;

import static org.elasticsearch.action.search.type.TransportSearchHelper.*;

/**
 * @author kimchy (Shay Banon)
 */
public class TransportSearchQueryAndFetchAction extends TransportSearchTypeAction {

    @Inject public TransportSearchQueryAndFetchAction(Settings settings, ThreadPool threadPool, ClusterService clusterService, IndicesService indicesService,
                                                      TransportSearchCache transportSearchCache, SearchServiceTransportAction searchService, SearchPhaseController searchPhaseController) {
        super(settings, threadPool, clusterService, indicesService, transportSearchCache, searchService, searchPhaseController);
    }

    @Override protected void doExecute(SearchRequest searchRequest, ActionListener<SearchResponse> listener) {
        new AsyncAction(searchRequest, listener).start();
    }

    private class AsyncAction extends BaseAsyncAction<QueryFetchSearchResult> {

        private final Map<SearchShardTarget, QueryFetchSearchResult> queryFetchResults = searchCache.obtainQueryFetchResults();


        private AsyncAction(SearchRequest request, ActionListener<SearchResponse> listener) {
            super(request, listener);
        }

        @Override protected void sendExecuteFirstPhase(Node node, InternalSearchRequest request, SearchServiceListener<QueryFetchSearchResult> listener) {
            searchService.sendExecuteFetch(node, request, listener);
        }

        @Override protected void processFirstPhaseResult(ShardRouting shard, QueryFetchSearchResult result) {
            queryFetchResults.put(result.shardTarget(), result);
        }

        @Override protected void moveToSecondPhase() {
            sortedShardList = searchPhaseController.sortDocs(queryFetchResults.values());
            final InternalSearchResponse internalResponse = searchPhaseController.merge(sortedShardList, queryFetchResults, queryFetchResults);
            String scrollIdX = null;
            if (request.scroll() != null) {
                scrollIdX = buildScrollId(request.searchType(), queryFetchResults.values());
            }
            final String scrollId = scrollIdX;
            searchCache.releaseQueryFetchResults(queryFetchResults);
            if (request.listenerThreaded()) {
                threadPool.execute(new Runnable() {
                    @Override public void run() {
                        listener.onResponse(new SearchResponse(internalResponse, scrollId, expectedSuccessfulOps, successulOps.get(), buildShardFailures()));
                    }
                });
            } else {
                listener.onResponse(new SearchResponse(internalResponse, scrollId, expectedSuccessfulOps, successulOps.get(), buildShardFailures()));
            }
        }
    }
}