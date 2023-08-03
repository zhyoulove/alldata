/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.iceberg.sink.multiple;

import org.apache.inlong.sort.iceberg.FlinkActions;
import org.apache.inlong.sort.iceberg.FlinkDynamicTableFactory;
import org.apache.inlong.sort.iceberg.sink.DeltaManifests;
import org.apache.inlong.sort.iceberg.sink.DeltaManifestsSerializer;
import org.apache.inlong.sort.iceberg.sink.FlinkManifestUtil;
import org.apache.inlong.sort.iceberg.sink.ManifestOutputFileFactory;

import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.core.io.SimpleVersionedSerialization;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.table.runtime.typeutils.SortedMapTypeInfo;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotUpdate;
import org.apache.iceberg.Table;
import org.apache.iceberg.actions.ActionsProvider;
import org.apache.iceberg.actions.RewriteDataFiles;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.flink.TableLoader;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Strings;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Comparators;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.stream.Collectors;

public class IcebergSingleFileCommiter extends IcebergProcessFunction<WriteResult, Void>
        implements
            CheckpointedFunction,
            CheckpointListener {

    private static final long serialVersionUID = 1L;
    private static final long INITIAL_CHECKPOINT_ID = -1L;

    private static final long INVALID_SNAPSHOT_ID = -1L;
    private static final byte[] EMPTY_MANIFEST_DATA = new byte[0];

    private static final Logger LOG = LoggerFactory.getLogger(IcebergSingleFileCommiter.class);
    private static final String FLINK_JOB_ID = "flink.job-id";

    // The max checkpoint id we've committed to iceberg table. As the flink's checkpoint is always increasing, so we
    // could correctly commit all the data files whose checkpoint id is greater than the max committed one to iceberg
    // table, for avoiding committing the same data files twice. This id will be attached to iceberg's meta when
    // committing the iceberg transaction.
    private static final String MAX_COMMITTED_CHECKPOINT_ID = "flink.max-committed-checkpoint-id";
    static final String MAX_CONTINUOUS_EMPTY_COMMITS = "flink.max-continuous-empty-commits";

    // TableLoader to load iceberg table lazily.
    private final TableLoader tableLoader;
    private final boolean replacePartitions;

    // A sorted map to maintain the completed data files for each pending checkpointId (which have not been committed
    // to iceberg table). We need a sorted map here because there's possible that few checkpoints snapshot failed, for
    // example: the 1st checkpoint have 2 data files <1, <file0, file1>>, the 2st checkpoint have 1 data files
    // <2, <file3>>. Snapshot for checkpoint#1 interrupted because of network/disk failure etc, while we don't expect
    // any data loss in iceberg table. So we keep the finished files <1, <file0, file1>> in memory and retry to commit
    // iceberg table when the next checkpoint happen.
    private final NavigableMap<Long, byte[]> dataFilesPerCheckpoint = Maps.newTreeMap();

    // The completed files cache for current checkpoint. Once the snapshot barrier received, it will be flushed to the
    // 'dataFilesPerCheckpoint'.
    private final List<WriteResult> writeResultsOfCurrentCkpt = Lists.newArrayList();

    // It will have an unique identifier for one job.
    private transient String flinkJobId;
    private transient Table table;
    private transient ManifestOutputFileFactory manifestOutputFileFactory;
    private transient long maxCommittedCheckpointId;
    private transient int continuousEmptyCheckpoints;
    private transient int maxContinuousEmptyCommits;
    // There're two cases that we restore from flink checkpoints: the first case is restoring from snapshot created by
    // the same flink job; another case is restoring from snapshot created by another different job. For the second
    // case, we need to maintain the old flink job's id in flink state backend to find the max-committed-checkpoint-id
    // when traversing iceberg table's snapshots.
    private final ListStateDescriptor<String> jobIdDescriptor;
    private transient ListState<String> jobIdState;
    // All pending checkpoints states for this function.
    private final ListStateDescriptor<SortedMap<Long, byte[]>> stateDescriptor;
    private transient ListState<SortedMap<Long, byte[]>> checkpointsState;

    // compact file action
    private ActionsProvider flinkActions;
    private transient RewriteDataFiles compactAction;
    private ReadableConfig tableOptions;

    public IcebergSingleFileCommiter(
            TableIdentifier tableId,
            TableLoader tableLoader,
            boolean replacePartitions,
            ActionsProvider actionProvider,
            ReadableConfig tableOptions) {
        // Here must distinguish state descriptor with tableId, because all icebergSingleFileCommiter state in
        // one IcebergMultipleFilesCommiter use same StateStore.
        this.tableLoader = tableLoader;
        this.replacePartitions = replacePartitions;
        this.flinkActions = actionProvider;
        this.tableOptions = tableOptions;
        this.jobIdDescriptor = new ListStateDescriptor<>(
                String.format("iceberg(%s)-flink-job-id", tableId.toString()), BasicTypeInfo.STRING_TYPE_INFO);
        this.stateDescriptor = buildStateDescriptor(tableId);
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        this.flinkJobId = getRuntimeContext().getJobId().toString();

        // Open the table loader and load the table.
        this.tableLoader.open();
        this.table = tableLoader.loadTable();

        boolean writeCompactEnabelFromTO = this.tableOptions == null
                ? false
                : this.tableOptions.get(FlinkDynamicTableFactory.WRITE_COMPACT_ENABLE);
        // compact file
        if (flinkActions != null && (PropertyUtil.propertyAsBoolean(
                table.properties(), FlinkActions.COMPACT_ENABLED, FlinkActions.COMPACT_ENABLED_DEFAULT)
                || writeCompactEnabelFromTO)) {
            compactAction = flinkActions.rewriteDataFiles(table);
        }
        maxContinuousEmptyCommits = PropertyUtil.propertyAsInt(table.properties(), MAX_CONTINUOUS_EMPTY_COMMITS, 10);
        Preconditions.checkArgument(maxContinuousEmptyCommits > 0,
                MAX_CONTINUOUS_EMPTY_COMMITS + " must be positive");

        int subTaskId = getRuntimeContext().getIndexOfThisSubtask();
        int attemptId = getRuntimeContext().getAttemptNumber();
        this.manifestOutputFileFactory = FlinkManifestUtil
                .createOutputFileFactory(table, flinkJobId, subTaskId, attemptId);
        this.maxCommittedCheckpointId = INITIAL_CHECKPOINT_ID;

        this.checkpointsState = context.getOperatorStateStore().getListState(stateDescriptor);
        this.jobIdState = context.getOperatorStateStore().getListState(jobIdDescriptor);
        // New table doesn't have state, so it doesn't need to do restore operation.
        if (context.isRestored()) {
            if (!jobIdState.get().iterator().hasNext()) {
                LOG.error("JobId is null, Skip restore process");
                return;
            }
            String restoredFlinkJobId = jobIdState.get().iterator().next();
            this.dataFilesPerCheckpoint.putAll(checkpointsState.get().iterator().next());
            // every datafiles will be added into state, so there must be data and nullpoint exception will not happen
            Long restoredCheckpointId = dataFilesPerCheckpoint.keySet().stream().max(Long::compareTo).get();
            Preconditions.checkState(!Strings.isNullOrEmpty(restoredFlinkJobId),
                    "Flink job id parsed from checkpoint snapshot shouldn't be null or empty");

            // ------------------------------
            // ↓ ↑
            // a --> a+1 --> a+2 --> ... --> a+n
            // max checkpoint id = m
            // a >= m: supplementary commit snapshot between checkpoint (`m`, `a`]
            // a < m: rollback to snapshot associated with checkpoint `a`
            rollbackAndRecover(restoredFlinkJobId, restoredCheckpointId);
        }
    }

    private void rollback(long snapshotId) {
        table.manageSnapshots().rollbackTo(snapshotId).commit();
    }

    private void recover(String restoredFlinkJobId, NavigableMap<Long, byte[]> uncommittedManifests) throws Exception {
        if (!uncommittedManifests.isEmpty()) {
            // Committed all uncommitted data files from the old flink job to iceberg table.
            long maxUncommittedCheckpointId = uncommittedManifests.lastKey();
            commitUpToCheckpoint(uncommittedManifests, restoredFlinkJobId, maxUncommittedCheckpointId);
        }
    }

    private void rollbackAndRecover(String restoredFlinkJobId, Long restoredCheckpointId) throws Exception {
        // Since flink's checkpoint id will start from the max-committed-checkpoint-id + 1 in the new flink job even
        // if it's restored from a snapshot created by another different flink job, so it's safe to assign the max
        // committed checkpoint id from restored flink job to the current flink job.
        this.maxCommittedCheckpointId = getMaxCommittedCheckpointId(table, restoredFlinkJobId);
        // Find snapshot associated with restoredCheckpointId
        long snapshotId = getSnapshotIdAssociatedWithChkId(table, restoredFlinkJobId, restoredCheckpointId);

        // Once maxCommitted CheckpointId is greater than restoredCheckpointId, it means more data added, it need
        // rollback
        if (restoredCheckpointId < maxCommittedCheckpointId) {
            if (snapshotId != INVALID_SNAPSHOT_ID) {
                LOG.info("Rollback committed snapshot to {}", snapshotId);
                rollback(snapshotId); // TODO: what if rollback throw Exception
            } else {
                long minUncommittedCheckpointId = dataFilesPerCheckpoint.keySet().stream().min(Long::compareTo).get();
                if (maxCommittedCheckpointId >= minUncommittedCheckpointId) {
                    LOG.warn("It maybe has some repeat data between chk[{}, {}]", minUncommittedCheckpointId,
                            maxCommittedCheckpointId);
                }

                // should recover all manifest that has not been deleted. Not deleted mean it may not be committed.
                long uncommittedChkId = findEarliestUnCommittedManifest(
                        dataFilesPerCheckpoint.headMap(maxCommittedCheckpointId, true), table.io());
                LOG.info("Snapshot has been expired. Recover all uncommitted snapshot between chk[{}, {}]. "
                        + "maxCommittedCheckpointId is {}, minUncommittedCheckpointId is {}.",
                        uncommittedChkId, restoredCheckpointId,
                        maxCommittedCheckpointId, minUncommittedCheckpointId);
                if (uncommittedChkId != INITIAL_CHECKPOINT_ID) {
                    recover(restoredFlinkJobId, dataFilesPerCheckpoint.tailMap(uncommittedChkId, false));
                } else {
                    recover(restoredFlinkJobId, dataFilesPerCheckpoint);
                }
            }
        } else {
            LOG.info("Recover uncommitted snapshot between chk({}, {}]. ", maxCommittedCheckpointId,
                    restoredCheckpointId);
            recover(restoredFlinkJobId, dataFilesPerCheckpoint.tailMap(maxCommittedCheckpointId, false));
        }
        dataFilesPerCheckpoint.clear();
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        long checkpointId = context.getCheckpointId();
        LOG.info("Start to flush snapshot state to state backend, table: {}, checkpointId: {}", table, checkpointId);

        // Update the checkpoint state.
        dataFilesPerCheckpoint.put(checkpointId, writeToManifest(checkpointId));
        // Reset the snapshot state to the latest state.
        checkpointsState.clear();
        checkpointsState.add(dataFilesPerCheckpoint);

        jobIdState.clear();
        jobIdState.add(flinkJobId);

        // Clear the local buffer for current checkpoint.
        writeResultsOfCurrentCkpt.clear();
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        // It's possible that we have the following events:
        // 1. snapshotState(ckpId);
        // 2. snapshotState(ckpId+1);
        // 3. notifyCheckpointComplete(ckpId+1);
        // 4. notifyCheckpointComplete(ckpId);
        // For step#4, we don't need to commit iceberg table again because in step#3 we've committed all the files,
        // Besides, we need to maintain the max-committed-checkpoint-id to be increasing.
        if (checkpointId > maxCommittedCheckpointId) {
            commitUpToCheckpoint(dataFilesPerCheckpoint, flinkJobId, checkpointId);
            this.maxCommittedCheckpointId = checkpointId;
            // every interval checkpoint do a small file compact
            if (compactAction != null) {
                compactAction.execute();
            }
        }
    }

    private void commitUpToCheckpoint(NavigableMap<Long, byte[]> deltaManifestsMap,
            String newFlinkJobId,
            long checkpointId) throws IOException {
        NavigableMap<Long, byte[]> pendingMap = deltaManifestsMap.headMap(checkpointId, true);
        List<ManifestFile> manifests = Lists.newArrayList();
        NavigableMap<Long, WriteResult> pendingResults = Maps.newTreeMap();
        for (Map.Entry<Long, byte[]> e : pendingMap.entrySet()) {
            if (Arrays.equals(EMPTY_MANIFEST_DATA, e.getValue())) {
                // Skip the empty flink manifest.
                continue;
            }

            DeltaManifests deltaManifests = SimpleVersionedSerialization
                    .readVersionAndDeSerialize(DeltaManifestsSerializer.INSTANCE, e.getValue());
            pendingResults.put(e.getKey(), FlinkManifestUtil.readCompletedFiles(deltaManifests, table.io()));
            manifests.addAll(deltaManifests.manifests());
        }

        int totalFiles = pendingResults.values().stream()
                .mapToInt(r -> r.dataFiles().length + r.deleteFiles().length).sum();
        continuousEmptyCheckpoints = totalFiles == 0 ? continuousEmptyCheckpoints + 1 : 0;
        if (totalFiles != 0 || continuousEmptyCheckpoints % maxContinuousEmptyCommits == 0) {
            if (replacePartitions) {
                replacePartitions(pendingResults, newFlinkJobId, checkpointId);
            } else {
                commitDeltaTxn(pendingResults, newFlinkJobId, checkpointId);
            }
            continuousEmptyCheckpoints = 0;
        }
        // remove already committed snapshot manifest info
        pendingMap.keySet().forEach(deltaManifestsMap::remove);
        pendingMap.clear();

        // Delete the committed manifests.
        for (ManifestFile manifest : manifests) {
            try {
                table.io().deleteFile(manifest.path());
            } catch (Exception e) {
                // The flink manifests cleaning failure shouldn't abort the completed checkpoint.
                String details = MoreObjects.toStringHelper(this)
                        .add("flinkJobId", newFlinkJobId)
                        .add("checkpointId", checkpointId)
                        .add("manifestPath", manifest.path())
                        .toString();
                LOG.warn("The iceberg transaction has been committed, but we failed to clean the temporary flink "
                        + "manifests: {}", details, e);
            }
        }
    }

    private void replacePartitions(NavigableMap<Long, WriteResult> pendingResults, String newFlinkJobId,
            long checkpointId) {
        // Partition overwrite does not support delete files.
        int deleteFilesNum = pendingResults.values().stream().mapToInt(r -> r.deleteFiles().length).sum();
        Preconditions.checkState(deleteFilesNum == 0, "Cannot overwrite partitions with delete files.");

        // Commit the overwrite transaction.
        ReplacePartitions dynamicOverwrite = table.newReplacePartitions();

        int numFiles = 0;
        for (WriteResult result : pendingResults.values()) {
            Preconditions.checkState(result.referencedDataFiles().length == 0,
                    "Should have no referenced data files.");

            numFiles += result.dataFiles().length;
            Arrays.stream(result.dataFiles()).forEach(dynamicOverwrite::addFile);
        }

        commitOperation(dynamicOverwrite, numFiles, 0, "dynamic partition overwrite", newFlinkJobId, checkpointId);
    }

    private void commitDeltaTxn(
            NavigableMap<Long, WriteResult> pendingResults, String newFlinkJobId, long checkpointId) {
        int deleteFilesNum = pendingResults.values().stream().mapToInt(r -> r.deleteFiles().length).sum();

        if (deleteFilesNum == 0) {
            // To be compatible with iceberg format V1.
            AppendFiles appendFiles = table.newAppend();

            int numFiles = 0;
            for (WriteResult result : pendingResults.values()) {
                Preconditions.checkState(result.referencedDataFiles().length == 0,
                        "Should have no referenced data files.");

                numFiles += result.dataFiles().length;
                Arrays.stream(result.dataFiles()).forEach(appendFiles::appendFile);
            }

            commitOperation(appendFiles, numFiles, 0, "append", newFlinkJobId, checkpointId);
        } else {
            // To be compatible with iceberg format V2.
            for (Map.Entry<Long, WriteResult> e : pendingResults.entrySet()) {
                // We don't commit the merged result into a single transaction because for the sequential transaction
                // txn1 and txn2, the equality-delete files of txn2 are required to be applied to data files from txn1.
                // Committing the merged one will lead to the incorrect delete semantic.
                WriteResult result = e.getValue();

                // Row delta validations are not needed for streaming changes that write equality deletes. Equality
                // deletes are applied to data in all previous sequence numbers, so retries may push deletes further in
                // the future, but do not affect correctness. Position deletes committed to the table in this path are
                // used only to delete rows from data files that are being added in this commit. There is no way for
                // data files added along with the delete files to be concurrently removed, so there is no need to
                // validate the files referenced by the position delete files that are being committed.
                RowDelta rowDelta = table.newRowDelta();

                int numDataFiles = result.dataFiles().length;
                Arrays.stream(result.dataFiles()).forEach(rowDelta::addRows);

                int numDeleteFiles = result.deleteFiles().length;
                Arrays.stream(result.deleteFiles()).forEach(rowDelta::addDeletes);

                commitOperation(rowDelta, numDataFiles, numDeleteFiles, "rowDelta", newFlinkJobId, e.getKey());
            }
        }
    }

    private void commitOperation(SnapshotUpdate<?> operation, int numDataFiles, int numDeleteFiles, String description,
            String newFlinkJobId, long checkpointId) {
        LOG.info(
                "Committing {} with {} data files and {} delete files to table {} with max committed checkpoint id {}.",
                description, numDataFiles, numDeleteFiles, table, checkpointId);
        operation.set(MAX_COMMITTED_CHECKPOINT_ID, Long.toString(checkpointId));
        operation.set(FLINK_JOB_ID, newFlinkJobId);

        long start = System.currentTimeMillis();
        operation.commit(); // abort is automatically called if this fails.
        long duration = System.currentTimeMillis() - start;
        LOG.info("Committed in {} ms", duration);
    }

    @Override
    public void processElement(WriteResult value)
            throws Exception {
        this.writeResultsOfCurrentCkpt.add(value);
    }

    @Override
    public void endInput() throws IOException {
        // Flush the buffered data files into 'dataFilesPerCheckpoint' firstly.
        long currentCheckpointId = Long.MAX_VALUE;
        dataFilesPerCheckpoint.put(currentCheckpointId, writeToManifest(currentCheckpointId));
        writeResultsOfCurrentCkpt.clear();

        commitUpToCheckpoint(dataFilesPerCheckpoint, flinkJobId, currentCheckpointId);
    }

    /**
     * Write all the complete data files to a newly created manifest file and return the manifest's avro serialized
     * bytes.
     */
    private byte[] writeToManifest(long checkpointId) throws IOException {
        if (writeResultsOfCurrentCkpt.isEmpty()) {
            return EMPTY_MANIFEST_DATA;
        }

        WriteResult result = WriteResult.builder().addAll(writeResultsOfCurrentCkpt).build();
        DeltaManifests deltaManifests = FlinkManifestUtil.writeCompletedFiles(result,
                () -> manifestOutputFileFactory.create(checkpointId), table.spec());

        return SimpleVersionedSerialization.writeVersionAndSerialize(DeltaManifestsSerializer.INSTANCE, deltaManifests);
    }

    @Override
    public void dispose() throws Exception {
        if (tableLoader != null) {
            tableLoader.close();
        }
    }

    private static ListStateDescriptor<SortedMap<Long, byte[]>> buildStateDescriptor(TableIdentifier tableId) {
        Comparator<Long> longComparator = Comparators.forType(Types.LongType.get());
        // Construct a SortedMapTypeInfo.
        SortedMapTypeInfo<Long, byte[]> sortedMapTypeInfo = new SortedMapTypeInfo<>(
                BasicTypeInfo.LONG_TYPE_INFO, PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO, longComparator);
        return new ListStateDescriptor<>(
                String.format("iceberg(%s)-files-committer-state", tableId.toString()), sortedMapTypeInfo);
    }

    public static long getMaxCommittedCheckpointId(Table table, String flinkJobId) {
        Snapshot snapshot = table.currentSnapshot();
        long lastCommittedCheckpointId = INITIAL_CHECKPOINT_ID;

        while (snapshot != null) {
            Map<String, String> summary = snapshot.summary();
            String snapshotFlinkJobId = summary.get(FLINK_JOB_ID);
            if (flinkJobId.equals(snapshotFlinkJobId)) {
                String value = summary.get(MAX_COMMITTED_CHECKPOINT_ID);
                if (value != null) {
                    lastCommittedCheckpointId = Long.parseLong(value);
                    break;
                }
            }
            Long parentSnapshotId = snapshot.parentId();
            snapshot = parentSnapshotId != null ? table.snapshot(parentSnapshotId) : null;
        }

        return lastCommittedCheckpointId;
    }

    static long getSnapshotIdAssociatedWithChkId(Table table, String flinkJobId, Long checkpointId) {
        Snapshot snapshot = table.currentSnapshot();
        long associatedSnapshotId = INVALID_SNAPSHOT_ID;

        while (snapshot != null) {
            Map<String, String> summary = snapshot.summary();
            String snapshotFlinkJobId = summary.get(FLINK_JOB_ID);
            if (flinkJobId.equals(snapshotFlinkJobId)) {
                String value = summary.get(MAX_COMMITTED_CHECKPOINT_ID);
                if (value != null && checkpointId.equals(Long.parseLong(value))) {
                    associatedSnapshotId = snapshot.snapshotId();
                    break;
                }
            }
            Long parentSnapshotId = snapshot.parentId();
            snapshot = parentSnapshotId != null ? table.snapshot(parentSnapshotId) : null;
        }
        return associatedSnapshotId;
    }

    /**
     * Find  last uncommitted manifest files in a list of manifest files.
     * 
     * Assume one manifest commit, all the previous manifests have been submitted (compared with the size according to the checkpoint) 
     * Assume flink manifest file has not been deleted, this manifest file 
     * 
     * @param deltaManifestsMap: all manifest files maybe haven not been not committed 
     * @param io: file access tool
     * @return
     * @throws IOException
     */
    static long findEarliestUnCommittedManifest(NavigableMap<Long, byte[]> deltaManifestsMap, FileIO io)
            throws IOException {
        List<Long> uncommittedChkList = deltaManifestsMap.keySet()
                .stream()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        long uncommittedChkId = INITIAL_CHECKPOINT_ID;
        for (long chkId : uncommittedChkList) {
            byte[] e = deltaManifestsMap.get(chkId);
            if (Arrays.equals(EMPTY_MANIFEST_DATA, e)) {
                // Skip the empty flink manifest.
                continue;
            }

            DeltaManifests deltaManifests = SimpleVersionedSerialization
                    .readVersionAndDeSerialize(DeltaManifestsSerializer.INSTANCE, e);
            if (deltaManifests.manifests()
                    .stream()
                    .anyMatch(manifest -> !io.newInputFile(manifest.path()).exists())) {
                // manifest file not exist means `chkId` is committed
                uncommittedChkId = chkId;
                break;
            }
        }
        return uncommittedChkId;
    }
}
