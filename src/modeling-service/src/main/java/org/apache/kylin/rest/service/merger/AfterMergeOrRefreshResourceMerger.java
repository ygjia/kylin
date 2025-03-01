/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.rest.service.merger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.engine.spark.ExecutableUtils;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.job.execution.JobTypeEnum;
import org.apache.kylin.job.execution.MergerInfo.TaskMergeInfo;
import org.apache.kylin.metadata.cube.model.NDataLayout;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NDataflowUpdate;
import org.apache.kylin.metadata.cube.model.PartitionStatusEnum;
import org.apache.kylin.metadata.model.SegmentStatusEnum;

import lombok.val;

public class AfterMergeOrRefreshResourceMerger extends MetadataMerger {

    public AfterMergeOrRefreshResourceMerger(KylinConfig config, String project) {
        super(config, project);
    }

    public NDataLayout[] mergeMultiPartitionModel(String dataflowId, Set<String> segmentIds, Set<Long> layoutIds,
            ResourceStore remoteResourceStore, JobTypeEnum jobType, Set<Long> partitions) {
        NDataflowManager mgr = NDataflowManager.getInstance(getConfig(), getProject());
        NDataflowUpdate update = new NDataflowUpdate(dataflowId);

        val localDataflowManager = NDataflowManager.getInstance(getConfig(), getProject());
        val localDataflow = localDataflowManager.getDataflow(dataflowId);

        NDataflowManager distMgr = NDataflowManager.getInstance(remoteResourceStore.getConfig(), getProject());
        NDataflow distDataflow = distMgr.getDataflow(update.getDataflowId());

        List<NDataSegment> toUpdateSegments = Lists.newArrayList();
        List<NDataLayout> toUpdateCuboids = Lists.newArrayList();

        NDataSegment mergedSegment;
        NDataSegment remoteSegment = distDataflow.getSegment(segmentIds.iterator().next()).copy();
        NDataSegment localSegment = localDataflow.getSegment(segmentIds.iterator().next()).copy();
        val availableLayoutIds = getAvailableLayoutIds(localDataflow, layoutIds);

        // only add layouts which still in segments, others maybe deleted by user
        List<NDataSegment> toRemoveSegments = Lists.newArrayList();
        if (JobTypeEnum.SUB_PARTITION_REFRESH != jobType) {
            toRemoveSegments = distMgr.getToRemoveSegs(distDataflow, remoteSegment);
        }

        if (JobTypeEnum.INDEX_MERGE == jobType) {
            mergedSegment = remoteSegment;
            final long lastBuildTime = System.currentTimeMillis();
            mergedSegment.getMultiPartitions().forEach(partition -> {
                partition.setStatus(PartitionStatusEnum.READY);
                partition.setLastBuildTime(lastBuildTime);
            });
            mergedSegment.setLastBuildTime(lastBuildTime);
            toUpdateCuboids.addAll(new ArrayList<>(mergedSegment.getSegDetails().getEffectiveLayouts()));
        } else {
            mergedSegment = upsertSegmentPartition(localSegment, remoteSegment, partitions);
            for (val segId : segmentIds) {
                val remoteSeg = distDataflow.getSegment(segId).copy();
                val localSeg = localDataflow.getSegment(segId).copy();
                for (long layoutId : availableLayoutIds) {
                    NDataLayout remoteLayout = remoteSeg.getLayout(layoutId);
                    NDataLayout localLayout = localSeg.getLayout(layoutId);
                    NDataLayout upsertLayout = upsertLayoutPartition(localLayout, remoteLayout, partitions);
                    toUpdateCuboids.add(upsertLayout);
                }
            }
        }

        if (mergedSegment.getStatus() == SegmentStatusEnum.NEW)
            mergedSegment.setStatus(SegmentStatusEnum.READY);
        mergedSegment.setDictReady(false);
        mergedSegment.setMvcc(localSegment.getMvcc());
        toUpdateSegments.add(mergedSegment);

        if (JobTypeEnum.INDEX_MERGE == jobType) {
            Optional<Long> reduce = toRemoveSegments.stream().map(NDataSegment::getSourceBytesSize)
                    .filter(size -> size != -1).reduce(Long::sum);
            if (reduce.isPresent()) {
                long totalSourceSize = reduce.get();
                mergedSegment.setSourceBytesSize(totalSourceSize);
                mergedSegment.setLastBuildTime(System.currentTimeMillis());
            }

            if (toRemoveSegments.stream().anyMatch(seg -> seg.getStatus() == SegmentStatusEnum.WARNING)) {
                mergedSegment.setStatus(SegmentStatusEnum.WARNING);
            }
        }
        update.setToAddOrUpdateLayouts(toUpdateCuboids.toArray(new NDataLayout[0]));
        update.setToRemoveSegs(toRemoveSegments.toArray(new NDataSegment[0]));
        update.setToUpdateSegs(toUpdateSegments.toArray(new NDataSegment[0]));

        mgr.updateDataflow(update);

        updateIndexPlan(dataflowId, remoteResourceStore);
        return update.getToAddOrUpdateLayouts();
    }

    public NDataLayout[] mergeNormalModel(String dataflowId, Set<String> segmentIds, Set<Long> layoutIds,
            ResourceStore remoteResourceStore, JobTypeEnum jobType) {
        NDataflowManager mgr = NDataflowManager.getInstance(getConfig(), getProject());
        NDataflowUpdate update = new NDataflowUpdate(dataflowId);

        NDataflowManager distMgr = NDataflowManager.getInstance(remoteResourceStore.getConfig(), getProject());
        NDataflow distDataflow = distMgr.getDataflow(update.getDataflowId());

        List<NDataSegment> toUpdateSegments = Lists.newArrayList();
        List<NDataLayout> toUpdateCuboids = Lists.newArrayList();
        NDataSegment mergedSegment = distDataflow.getSegment(segmentIds.iterator().next()).copy();

        if (mergedSegment.getStatus() == SegmentStatusEnum.NEW)
            mergedSegment.setStatus(SegmentStatusEnum.READY);
        mergedSegment.setLastBuildTime(mergedSegment.getSegDetails().getLastModified());
        mergedSegment.setDictReady(false);
        mergedSegment.setMvcc(mgr.getDataflow(dataflowId).getSegment(mergedSegment.getUuid()).getMvcc());
        toUpdateSegments.add(mergedSegment);

        // only add layouts which still in segments, others maybe deleted by user
        List<NDataSegment> toRemoveSegments = distMgr.getToRemoveSegs(distDataflow, mergedSegment);
        if (JobTypeEnum.INDEX_MERGE == jobType) {
            Optional<Long> reduce = toRemoveSegments.stream().map(NDataSegment::getSourceBytesSize)
                    .filter(size -> size != -1).reduce(Long::sum);
            if (reduce.isPresent()) {
                long totalSourceSize = reduce.get();
                mergedSegment.setSourceBytesSize(totalSourceSize);
                mergedSegment.setLastBuildTime(System.currentTimeMillis());
            }

            if (toRemoveSegments.stream().anyMatch(seg -> seg.getStatus() == SegmentStatusEnum.WARNING)) {
                mergedSegment.setStatus(SegmentStatusEnum.WARNING);
            }
        }
        toUpdateCuboids.addAll(new ArrayList<>(mergedSegment.getSegDetails().getEffectiveLayouts()));

        update.setToAddOrUpdateLayouts(toUpdateCuboids.toArray(new NDataLayout[0]));
        update.setToRemoveSegs(toRemoveSegments.toArray(new NDataSegment[0]));
        update.setToUpdateSegs(toUpdateSegments.toArray(new NDataSegment[0]));
        if (distDataflow.getModel().getStorageType().isV3Storage()) {
            mergeLayoutDetails(getProject(), distDataflow.getModel().getId(), layoutIds, mergedSegment,
                    toRemoveSegments, remoteResourceStore.getConfig());
        }

        mgr.updateDataflow(update);

        updateIndexPlan(dataflowId, remoteResourceStore);
        return update.getToAddOrUpdateLayouts();
    }

    public NDataLayout[] merge(String dataflowId, Set<String> segmentIds, Set<Long> layoutIds,
            ResourceStore remoteResourceStore, JobTypeEnum jobType, Set<Long> partitions) {
        if (CollectionUtils.isNotEmpty(partitions)) {
            return mergeMultiPartitionModel(dataflowId, segmentIds, layoutIds, remoteResourceStore, jobType,
                    partitions);
        } else {
            return mergeNormalModel(dataflowId, segmentIds, layoutIds, remoteResourceStore, jobType);
        }
    }

    @Override
    public NDataLayout[] merge(TaskMergeInfo info) {
        try (val buildResourceStore = ExecutableUtils.getRemoteStore(this.getConfig(), info.getOutputMetaUrl())) {
            val dataFlowId = info.getDataFlowId();
            val segmentIds = info.getSegmentIds();
            val layoutIds = info.getLayoutIds();
            val partitionIds = info.getPartitionIds();
            val jobType = info.getJobType();
            val needBuildSnapshots = info.isNeedBuildSnapshots();
            NDataLayout[] nDataLayouts = merge(dataFlowId, segmentIds, layoutIds, buildResourceStore, jobType,
                    partitionIds);
            NDataflow dataflow = NDataflowManager.getInstance(getConfig(), getProject()).getDataflow(dataFlowId);
            if (needBuildSnapshots) {
                mergeSnapshotMeta(dataflow, buildResourceStore);
            }
            mergeTableExtMeta(dataflow, buildResourceStore);
            return nDataLayouts;
        }
    }

}
