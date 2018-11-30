/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.gateway;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elassandra.NoPersistedMetaDataException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.Index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Handles writing and loading both {@link MetaData} and {@link IndexMetaData}
 */
public class MetaStateService extends AbstractComponent {

    private final NodeEnvironment nodeEnv;
    private final NamedXContentRegistry namedXContentRegistry;
    private final ClusterService clusterService;

    public MetaStateService(Settings settings, NodeEnvironment nodeEnv, NamedXContentRegistry namedXContentRegistry) {
        this(settings, nodeEnv, namedXContentRegistry, null);
    }

    public MetaStateService(Settings settings, NodeEnvironment nodeEnv, NamedXContentRegistry namedXContentRegistry, ClusterService clusterService) {
        super(settings);
        this.nodeEnv = nodeEnv;
        this.namedXContentRegistry = namedXContentRegistry;
        this.clusterService = clusterService;
    }

    /**
     * Loads the full state, which includes both the global state and all the indices
     * meta state.
     */
    MetaData loadFullState() throws IOException {
        MetaData globalMetaData = loadGlobalState();
        MetaData.Builder metaDataBuilder;
        if (globalMetaData != null) {
            metaDataBuilder = MetaData.builder(globalMetaData);
        } else {
            metaDataBuilder = MetaData.builder();
        }
        for (String indexFolderName : nodeEnv.availableIndexFolders()) {
            IndexMetaData indexMetaData = IndexMetaData.FORMAT.loadLatestState(logger, namedXContentRegistry,
                nodeEnv.resolveIndexFolder(indexFolderName));
            if (indexMetaData != null) {
                metaDataBuilder.put(indexMetaData, false);
            } else {
                logger.debug("[{}] failed to find metadata for existing index location", indexFolderName);
            }
        }
        return metaDataBuilder.build();
    }

    /**
     * Loads the index state for the provided index name, returning null if doesn't exists.
     */
    @Nullable
    public IndexMetaData loadIndexState(Index index) throws IOException {
        return IndexMetaData.FORMAT.loadLatestState(logger, namedXContentRegistry, nodeEnv.indexPaths(index));
    }

    /**
     * Loads all indices states available on disk
     */
    List<IndexMetaData> loadIndicesStates(Predicate<String> excludeIndexPathIdsPredicate) throws IOException {
        List<IndexMetaData> indexMetaDataList = new ArrayList<>();
        for (String indexFolderName : nodeEnv.availableIndexFolders()) {
            if (excludeIndexPathIdsPredicate.test(indexFolderName)) {
                continue;
            }
            IndexMetaData indexMetaData = IndexMetaData.FORMAT.loadLatestState(logger, namedXContentRegistry,
                nodeEnv.resolveIndexFolder(indexFolderName));
            if (indexMetaData != null) {
                final String indexPathId = indexMetaData.getIndex().getUUID();
                if (indexFolderName.equals(indexPathId)) {
                    indexMetaDataList.add(indexMetaData);
                } else {
                    throw new IllegalStateException("[" + indexFolderName+ "] invalid index folder name, rename to [" + indexPathId + "]");
                }
            } else {
                logger.debug("[{}] failed to find metadata for existing index location", indexFolderName);
            }
        }
        return indexMetaDataList;
    }

    /**
     * Loads the global state, *without* index state, see {@link #loadFullState()} for that.
     */
    MetaData loadGlobalState() throws IOException {
        try {
            return clusterService.loadGlobalState();
        } catch (NoPersistedMetaDataException e) {
            return MetaData.EMPTY_META_DATA;
        }
    }


    /**
     * Decode global state from a string.
     * @param stringMetaData
     * @return
     * @throws Exception
     */
    public MetaData loadGlobalState(String stringMetaData) throws IOException {
        return MetaData.CASSANDRA_FORMAT.loadLatestState(logger, namedXContentRegistry, stringMetaData);
    }

    public MetaData loadGlobalState(byte[] metadata) throws IOException {
        return MetaData.CQL_FORMAT.loadLatestState(logger, namedXContentRegistry, metadata);
    }

    /**
     * Writes the index state.
     *
     * This method is public for testing purposes.
     */
    public void writeIndex(String reason, IndexMetaData indexMetaData) throws IOException {
        final Index index = indexMetaData.getIndex();
        logger.trace("[{}] writing state, reason [{}]", index, reason);
        try {
            IndexMetaData.FORMAT.write(indexMetaData,
                nodeEnv.indexPaths(indexMetaData.getIndex()));
        } catch (Exception ex) {
            logger.warn((Supplier<?>) () -> new ParameterizedMessage("[{}]: failed to write index state", index), ex);
            throw new IOException("failed to write state for [" + index + "]", ex);
        }
    }

    /**
     * Writes the global state, *without* the indices states.
     */
    void writeGlobalState(String reason, MetaData metaData) throws IOException {
        logger.trace("[_global] writing state, reason [{}]",  reason);
        try {
            MetaData.FORMAT.write(metaData, nodeEnv.nodeDataPaths());
        } catch (Exception ex) {
            logger.warn("[_global]: failed to write global state", ex);
            throw new IOException("failed to write global state", ex);
        }
    }
}
