/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.indexlifecycle.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.indexlifecycle.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.indexlifecycle.LifecyclePolicyMetadata;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleSettings;
import org.elasticsearch.xpack.core.indexlifecycle.action.DeleteLifecycleAction;
import org.elasticsearch.xpack.core.indexlifecycle.action.DeleteLifecycleAction.Request;
import org.elasticsearch.xpack.core.indexlifecycle.action.DeleteLifecycleAction.Response;

import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;

public class TransportDeleteLifecycleAction extends TransportMasterNodeAction<Request, Response> {

    @Inject
    public TransportDeleteLifecycleAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                          ThreadPool threadPool, ActionFilters actionFilters,
                                          IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, DeleteLifecycleAction.NAME, transportService, clusterService, threadPool, actionFilters,
                indexNameExpressionResolver, Request::new);
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected Response newResponse() {
        return new Response();
    }

    @Override
    protected void masterOperation(Request request, ClusterState state, ActionListener<Response> listener) throws Exception {
        clusterService.submitStateUpdateTask("delete-lifecycle-" + request.getPolicyName(),
                new AckedClusterStateUpdateTask<Response>(request, listener) {
                    @Override
                    protected Response newResponse(boolean acknowledged) {
                        return new Response(acknowledged);
                    }

                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        Iterator<IndexMetaData> indicesIt = currentState.metaData().indices().valuesIt();
                        while(indicesIt.hasNext()) {
                            IndexMetaData idxMeta = indicesIt.next();
                            String indexPolicy = LifecycleSettings.LIFECYCLE_NAME_SETTING.get(idxMeta.getSettings());
                            if (request.getPolicyName().equals(indexPolicy)) {
                                throw new IllegalArgumentException("Cannot delete policy [" + request.getPolicyName()
                                    + "]. It is being used by at least one index [" + idxMeta.getIndex().getName() + "]");
                            }

                        }
                        ClusterState.Builder newState = ClusterState.builder(currentState);
                        IndexLifecycleMetadata currentMetadata = currentState.metaData().custom(IndexLifecycleMetadata.TYPE);
                        if (currentMetadata == null
                                || currentMetadata.getPolicyMetadatas().containsKey(request.getPolicyName()) == false) {
                            throw new ResourceNotFoundException("Lifecycle policy not found: {}", request.getPolicyName());
                        }
                        SortedMap<String, LifecyclePolicyMetadata> newPolicies = new TreeMap<>(currentMetadata.getPolicyMetadatas());
                        newPolicies.remove(request.getPolicyName());
                        IndexLifecycleMetadata newMetadata = new IndexLifecycleMetadata(newPolicies, currentMetadata.getOperationMode());
                        newState.metaData(MetaData.builder(currentState.getMetaData())
                                .putCustom(IndexLifecycleMetadata.TYPE, newMetadata).build());
                        return newState.build();
                    }
                });
    }

    @Override
    protected ClusterBlockException checkBlock(Request request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
    }
}
