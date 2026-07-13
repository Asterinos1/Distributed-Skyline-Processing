package org.main;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.RichFilterFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.*;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.co.CoProcessFunction;
import org.apache.flink.util.Collector;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;


/**
 * Distributed Skyline Query Implementation on Apache Flink.
 *
 * This job implements the "MapReduce-based" Skyline algorithms (MR-Angle, MR-Dim, MR-Grid) adapted
 * for a streaming Flink topology. The architecture follows a two-phase approach:
 *
 * Architecture:
 * This class orchestrates a two-phase MapReduce-style computation for Skyline queries (finding non-dominated points).
 * Partitioning: Data is distributed to workers using specific strategies (Angle, Dim, Grid).
 * Local Phase: Partitions input stream based on the selected strategy and maintains a local skyline using BNL.
 * Global Phase: Aggregates local results and filters non-dominated points to produce the final result.
 *
 * Synchronization between data ingestion and query triggers is handled via a barrier mechanism
 * based on Record IDs.
 * This happens as a failsafe mechanism so that we do not flood the query stream with query requests and we don't
 * have the time to process(partition) any new data
 */
public class FlinkSkyline {

    /**
     * Main Execution Entry Point.
     *
     * Configures the streaming topology, connects Kafka sources/sinks, and instantiates the
     * partitioning and processing logic based on CLI arguments.
     *
     * @param args Command line arguments for configuration (parallelism, algo, topics, etc.)
     * @throws Exception Flink execution exceptions.
     */
    public static void main(String[] args) throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);
        if (params.has("config")) {
            params = ParameterTool.fromPropertiesFile(params.get("config")).mergeWith(params);
        }

        // --- Parameters ---
        // --- Configuration & Tuning ---
        final int parallelism = params.getInt("parallelism", 4);
        final String algo = params.get("algo", "mr-angle").toLowerCase();
        final String inputTopic = params.get("input-topic", "input-tuples");
        final String queryTopic = params.get("query-topic", "queries");
        final String outputTopic = params.get("output-topic", "output-skyline");
        final double domainMax = params.getDouble("domain", 1000.0);
        final int dims = params.getInt("dims", 2);
        final String bootstrapServers = params.get("bootstrap-servers", "localhost:9092");

        // Empirically(Based on the paper) partitions set to 2x number of nodes to ensure decent load distribution
        // even if data is skewed.
        final int numPartitions = 2 * parallelism;

        // Initialize the StreamExecutionEnvironment. This is the context in which the program is executed.
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(parallelism);

        // --- Fault Tolerance & Checkpointing ---
        if (params.getBoolean("checkpointing.enabled", false)) {
            long checkpointInterval = params.getLong("checkpointing.interval-ms", 60000L);
            env.enableCheckpointing(checkpointInterval);
            env.getCheckpointConfig().setCheckpointingMode(org.apache.flink.streaming.api.CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setMinPauseBetweenCheckpoints(params.getLong("checkpointing.min-pause-ms", 30000L));
            env.getCheckpointConfig().setCheckpointTimeout(params.getLong("checkpointing.timeout-ms", 600000L));
            env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(
                org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION
            );
        }

        // --- State Backend ---
        String stateBackend = params.get("state.backend", "hashmap").toLowerCase();
        if ("rocksdb".equals(stateBackend)) {
            env.setStateBackend(new org.apache.flink.contrib.streaming.state.EmbeddedRocksDBStateBackend());
        }

        // --- Restart Strategy ---
        env.setRestartStrategy(org.apache.flink.api.common.restartstrategy.RestartStrategies.failureRateRestart(
            3,
            org.apache.flink.api.common.time.Time.minutes(5),
            org.apache.flink.api.common.time.Time.seconds(10)
        ));

        // --- Kafka Sources Setup ---
        // Data Source: Read from earliest to ensure we process the full dataset for the experiment.
        KafkaSource<String> tupleSrc = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(inputTopic)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // Query Source: Read from latest. Acts as a control stream to trigger computation.
        KafkaSource<String> querySrc = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(queryTopic)
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // Ingest the Data and Parse
        // We use noWatermarks() here because this specific experiment relies on Record IDs for synchronization,
        // rather than Event Time processing.
        DataStream<ServiceTuple> rawData = env.fromSource(tupleSrc, org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(), "Data")
                .map(ServiceTuple::fromString)
                .filter(Objects::nonNull); // Null safety for malformed CSV lines

        // Apply Partitioning Strategy
        // This determines how the workload is distributed to the Local Processing nodes.
        //DataStream<ServiceTuple> processedData = rawData; //this needs to be uncommented if we uncomment the grid filter
        // This logic determines which worker node receives which data point.
        PartitioningLogic.SkylinePartitioner partitioner;

        switch (algo) {
            case "mr-dim":
                // MR-Dim: Standard dimensional partitioning
                // Slices space along the first dimension. Good for correlated data, bad for anti-correlated.
                partitioner = new PartitioningLogic.DimPartitioner(numPartitions, domainMax);
                break;
            case "mr-grid":
                // MR-Grid:
                // Prune dominated grids FIRST. This logic is a little complicated especially in >2d
                // and especially because of the synchronization we do in the partitions.
                // this logic might interfere with some other logic of our code and might cause a deadlock
                // so we comment the next line for safety
                // processedData = rawData.filter(new PartitioningLogic.GridDominanceFilter(domainMax, dims));

                // Maps points to hypercube grid cells.
                partitioner = new PartitioningLogic.GridPartitioner(numPartitions, domainMax, dims);
                break;
            default:
                // MR-Angle: Hyperspherical partitioning
                // Maps points based on angular coordinates (Hyperspherical). Best for anti-correlated/circular distributions.
                partitioner = new PartitioningLogic.AnglePartitioner(numPartitions, dims);
                break;
        }

        // Apply keyBy: This logically partitions the stream. All records with the same key
        // (determined by the partitioner) are sent to the same physical operator instance.
        KeyedStream<ServiceTuple, Integer> keyedData = rawData.keyBy(partitioner);

        // Query Trigger / Control Stream
        // We broadcast the query trigger to ALL partitions. Each partition must report back
        // its local status for the global aggregation to proceed.
        // Input: Raw JSON String from Kafka.
        // Output: Tuple3 <PartitionID (Target), QueryPayload, DispatchTime>
        KeyedStream<Tuple3<Integer, String, Long>, Integer> keyedTriggers = env
                .fromSource(querySrc, org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(), "Queries")
                .flatMap(new FlatMapFunction<String, Tuple3<Integer, String, Long>>() {
                    @Override
                    public void flatMap(String rawPayload, Collector<Tuple3<Integer, String, Long>> out) {
                        long startTime = System.currentTimeMillis();
                        // Broadcast query to all partitions
                        for (int i = 0; i < numPartitions; i++) {
                            out.collect(new Tuple3<>(i, rawPayload, startTime));
                        }
                    }
                })
                .keyBy(t -> t.f0); // Route trigger i to partition i

        // Local Processing Phase(Connect Data & Queries)
        // Connects the data stream with the control stream.(And sends them for local processing(BNL))
        // The CoProcessFunction handles two different inputs sharing the same key.
        DataStream<Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>> localSkylines = keyedData
                .connect(keyedTriggers)
                .process(new SkylineLocalProcessor())
                .name("LocalSkylineProcessor");

        // Global Aggregation Phase
        // Group by the QUERY STRING (f1) so all partial results for a specific query land on the same reducer.
        // We key by the Query String (f1) so that all partial results for the exact same query
        // end up at the same Reducer instance.
        DataStream<String> finalResults = localSkylines
                .keyBy(t -> t.f1) // Use f1 (Query String) as key
                .process(new GlobalSkylineAggregator(numPartitions))
                .name("GlobalReducer");

        // Sink Results to Kafka
        finalResults.sinkTo(KafkaSink.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setProperty("max.request.size", "10485760") // Increase max request size for large skyline payloads
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(outputTopic)
                        .setValueSerializationSchema(new SimpleStringSchema()).build())
                .build());

        env.execute("Flink Skyline: " + algo);
    }


    /**
     * Local Skyline Processor.
     *
     * A CoProcessFunction that manages two streams sharing the same Partition ID key:
     * 1. Data Stream: Incoming tuples are buffered and reduced using the BNL algorithm.
     * 2. Query Stream: Control signals that trigger the emission of the current local skyline.
     *
     * Synchronization Mechanism:
     * To prevent "empty" reads in a streaming context, the query trigger contains a barrier ID.
     * This processor will hold a query in a pending state until the maximum Record ID seen
     * in the data stream meets or exceeds the barrier requirement.
     *
     * Inputs:
     * - IN1: ServiceTuple (The data point).
     * - IN2: Tuple3<Integer, String, Long> (PartitionID, Query Payload, Trigger Timestamp).
     *
     * Output:
     * - Tuple6 containing:
     * f0: PartitionID (Integer) - The worker ID.
     * f1: QueryPayload (String) - The original query JSON/String.
     * f2: TriggerDispatchTime (Long) - When the query was injected.
     * f3: PartitionStartTime (Long) - When this worker began processing data.
     * f4: List<ServiceTuple> - The calculated local skyline points.
     * f5: LocalCPUTime (Long) - Total accumulated CPU time for BNL in milliseconds.
     */
    public static class SkylineLocalProcessor extends CoProcessFunction<ServiceTuple, Tuple3<Integer, String, Long>, Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>> {

        // --- Flink State Handles ---
        // We use 'transient' because these fields are not serialized by Java serialization.
        // Instead, they are initialized via the Flink RuntimeContext in the open() method.

        // Candidate set for the local partition
        private transient ListState<ServiceTuple> localSkylineState;
        // Buffer for incoming tuples before batch processing
        private transient List<ServiceTuple> inputBuffer;

        // --- Synchronization & Metrics State ---
        private transient ValueState<Long> maxSeenIdState;
        private transient ListState<Tuple3<Integer, String, Long>> pendingQueriesState;
        private transient ValueState<Long> startTimeState;
        // Track accumulated CPU time for the algorithm
        private transient ValueState<Long> accumulatedCpuNanosState;

        private final int BUFFER_SIZE = 5000;

        /**
         * State Initialization.
         * Sets up the handle accessors for Flink managed state (ListState, ValueState).
         * This state is fault-tolerant and persists across checkpoints.
         *
         * @param config The Flink configuration context.
         */
        @Override
        public void open(Configuration config) {
            localSkylineState = getRuntimeContext().getListState(new ListStateDescriptor<>("localSky", ServiceTuple.class));
            inputBuffer = new ArrayList<>();
            startTimeState = getRuntimeContext().getState(new ValueStateDescriptor<>("jobStartTime", Long.class));
            maxSeenIdState = getRuntimeContext().getState(new ValueStateDescriptor<>("maxId", Long.class));
            pendingQueriesState = getRuntimeContext().getListState(new ListStateDescriptor<>("pendingQs", TypeInformation.of(new TypeHint<Tuple3<Integer, String, Long>>() {})));
            accumulatedCpuNanosState = getRuntimeContext().getState(new ValueStateDescriptor<>("cpuTime", Long.class));
        }


        /**
         * Data Stream Handler (Input 1).
         *
         * 1. Updates the maximum Record ID seen (for synchronization).
         * 2. Buffers the tuple.
         * 3. Triggers BNL processing if buffer exceeds threshold.
         * 4. Checks if the new data satisfies any pending query barriers.
         *
         * @param point The incoming data tuple.
         * @param ctx   Context for timer/side-output access.
         * @param out   Collector to emit results (only used here if a pending query is unlocked).
         */
        @Override
        public void processElement1(ServiceTuple point, Context ctx, Collector<Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>> out) throws Exception {
            // Start Timer
            long startNano = System.nanoTime();

            // Initialize partition start time on first element
            if (startTimeState.value() == null) {
                startTimeState.update(System.currentTimeMillis());
            }

            // Update max ID seen (used for barrier synchronization)
            // The Query Stream uses this to know if the partition has processed enough data.
            long currentId = Long.parseLong(point.id);
            Long maxIdWrapper = maxSeenIdState.value();
            long maxId = (maxIdWrapper != null) ? maxIdWrapper : -1L;

            if (currentId > maxId) {
                maxSeenIdState.update(currentId);
                maxId = currentId;
            }

            // Buffer data and trigger local Skyline computation (BNL) if buffer is fulls
            inputBuffer.add(point);
            if (inputBuffer.size() >= BUFFER_SIZE) {
                processBuffer();
            }

            // Update CPU metrics
            long duration = System.nanoTime() - startNano;
            Long currentCpu = accumulatedCpuNanosState.value();
            accumulatedCpuNanosState.update((currentCpu == null ? 0 : currentCpu) + duration);

            // Barrier Check:
            // Check if the arrival of this new data satisfies any queries that were waiting in the queue.
            Iterable<Tuple3<Integer, String, Long>> pending = pendingQueriesState.get();
            if (pending != null) {
                List<Tuple3<Integer, String, Long>> remaining = new ArrayList<>();
                boolean processedAny = false;
                for (Tuple3<Integer, String, Long> query : pending) {
                    String[] parts = query.f1.split(",");
                    // The query payload is expected to be "QueryID,RequiredRecordCount"
                    long requiredCount = (parts.length > 1) ? Long.parseLong(parts[1]) : 0;
                    if (maxId >= requiredCount) {
                        processQuery(query, out);
                        processedAny = true;
                    } else {
                        remaining.add(query);
                    }
                }
                // Update the state with queries that are still waiting
                if (processedAny) pendingQueriesState.update(remaining);
            }
        }

        /**
         * Query Stream Handler (Input 2).
         *
         * Receives a trigger request. If the partition has processed enough data (RecordID >= Barrier),
         * it executes the query immediately. Otherwise, the query is stored in the 'pendingQueriesState'
         * queue.
         *
         * @param trigger Tuple3 containing <PartitionID, Payload ("ID,Count"), Timestamp>.
         * @param ctx     Context.
         * @param out     Collector to emit results.
         */
        @Override
        public void processElement2(Tuple3<Integer, String, Long> trigger, Context ctx, Collector<Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>> out) throws Exception {

            // Parse synchronization barrier from query payload (Format: "QueryID,RequiredCount")
            String[] parts = trigger.f1.split(",");
            long requiredCount = (parts.length > 1) ? Long.parseLong(parts[1]) : 0;
            Long maxIdWrapper = maxSeenIdState.value();
            long currentMaxId = (maxIdWrapper != null) ? maxIdWrapper : -1L;

            //FOR DEBUGGING PURPOSES
            //int pId = trigger.f0;
            //System.out.println(">>> Partition " + pId + " Query Trigger. CurrentMaxID: " + currentMaxId + " | Required: " + requiredCount);

            // If we have enough data(Or a partition is empty because of unbalanced partitioning,
            // (Usually because of the use of anti correlated data)),
            // process immediately. Otherwise, queue it.
            // Synchronization Logic:
            // If we have seen enough data (currentMaxId >= requiredCount), or if we are in an initial state (-1),
            // (a partition is empty because of unbalanced partitioning,
            //  Usually because of the use of anti correlated data)
            // we process the query immediately.
            // Otherwise, we store it in 'pendingQueriesState' to be re-evaluated when new data arrives.
            if (currentMaxId >= requiredCount || currentMaxId == -1L) {
                processQuery(trigger, out);
            } else {
                pendingQueriesState.add(trigger);
            }
        }

        /**
         * Query Executor.
         *
         * Finalizes the local skyline by flushing any remaining buffered items, tagging the results with
         * the partition ID, and emitting the result package to the Global Aggregator.
         *
         * @param trigger The query trigger object.
         * @param out     The output collector.
         */
        private void processQuery(Tuple3<Integer, String, Long> trigger, Collector<Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>> out) throws Exception {

            // Start Timer for any remaining flush
            long startNano = System.nanoTime();
            // Flush remaining items in buffer to ensure the result is consistent with the received data
            if (!inputBuffer.isEmpty()) {
                processBuffer();
            }
            long duration = System.nanoTime() - startNano;
            Long currentCpu = accumulatedCpuNanosState.value();
            long totalCpuNanos = (currentCpu == null ? 0 : currentCpu) + duration;
            accumulatedCpuNanosState.update(totalCpuNanos);

            // Prepare Output Data
            int partitionId = trigger.f0; // Identify which partition this is
            String queryPayload = trigger.f1;
            Long triggerDispatchTime = trigger.f2;
            Long partitionStartTime = startTimeState.value();
            if (partitionStartTime == null) partitionStartTime = System.currentTimeMillis();

            List<ServiceTuple> results = new ArrayList<>();
            for (ServiceTuple s : localSkylineState.get()) {
                // Tagging origin is required for Global Optimality calculation
                s.originPartition = partitionId;
                results.add(s);
            }

            long totalCpuMillis = totalCpuNanos / 1_000_000L;

            out.collect(new Tuple6<>(
                    partitionId,
                    queryPayload,
                    triggerDispatchTime,
                    partitionStartTime,
                    results,
                    totalCpuMillis
            ));
        }

        /**
         * Block Nested Loop (BNL) Algorithm.
         *
         * Compares the input buffer against the current skyline state.
         * - If a new point is dominated by an existing point, discard the new point.
         * - If a new point dominates an existing point, remove the existing point.
         * - If neither dominates, keep both.
         *
         * Input: Internal 'inputBuffer' and 'localSkylineState'.
         * Output: Updates 'localSkylineState' with the refined set of candidates.
         */
        private void processBuffer() throws Exception {
            Iterable<ServiceTuple> stateIter = localSkylineState.get();
            List<ServiceTuple> currentSkyline = new ArrayList<>();
            if (stateIter != null) {
                for (ServiceTuple s : stateIter) currentSkyline.add(s);
            }

            for (ServiceTuple candidate : inputBuffer) {
                boolean isDominated = false;
                Iterator<ServiceTuple> it = currentSkyline.iterator();
                while (it.hasNext()) {
                    ServiceTuple existing = it.next();
                    if (existing.dominates(candidate)) {
                        isDominated = true;
                        break;
                    }
                    if (candidate.dominates(existing)) {
                        // Existing point is dominated by new candidate; remove it.
                        it.remove();
                    }
                }
                if (!isDominated) {
                    currentSkyline.add(candidate);
                }
            }
            localSkylineState.update(currentSkyline);
            inputBuffer.clear();
        }
    }

    /**
     * Global Skyline Aggregator.
     *
     * A KeyedProcessFunction that collects partial skyline results from all parallel partitions.
     * It uses a countdown latch mechanism (arrivedCount) to wait until all partitions have reported
     * before performing the final reduction.
     *
     * Input:
     * - Tuple6 from LocalProcessor (PartitionID, Payload, Timestamps, SkylineList, CPU Metrics).
     *
     * Output:
     * - String: A JSON formatted string containing performance metrics and (optionally) the result points.
     */
    public static class GlobalSkylineAggregator extends KeyedProcessFunction<String, Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>, String> {

        private final int totalPartitions;

        // --- Flink State Handles ---
        // Accumulate candidates from all partitions here until we are ready to merge
        private transient ValueState<List<ServiceTuple>> globalBuffer;
        // Count how many partitions have responded for the current query
        private transient ValueState<Integer> arrivedCount;

        // Metrics State
        private transient ValueState<Long> minStartTimeState;// Earliest start time among workers
        private transient ValueState<Long> lastArrivalState; // Time the last partition reported in
        private transient ValueState<Long> maxLocalCpuState; // Straggler detection (slowest worker)

        // MapState to store the size of the local skyline for each partition ID.
        // This is specifically used to calculate the "Optimality" metric (how efficient the local pruning was).
        private transient MapState<Integer, Integer> localSkylineSizes;

        /**
         * Constructor.
         *
         * @param totalPartitions The expected number of partial results (usually equal to parallelism * 2).
         */
        public GlobalSkylineAggregator(int totalPartitions) {
            this.totalPartitions = totalPartitions;
        }

        @Override
        public void open(Configuration config) {
            globalBuffer = getRuntimeContext().getState(new ValueStateDescriptor<>("gBuffer", TypeInformation.of(new TypeHint<List<ServiceTuple>>() {})));
            arrivedCount = getRuntimeContext().getState(new ValueStateDescriptor<>("cnt", Integer.class));
            minStartTimeState = getRuntimeContext().getState(new ValueStateDescriptor<>("minStart", Long.class));
            lastArrivalState = getRuntimeContext().getState(new ValueStateDescriptor<>("lastArr", Long.class));
            maxLocalCpuState = getRuntimeContext().getState(new ValueStateDescriptor<>("maxCpu", Long.class));
            localSkylineSizes = getRuntimeContext().getMapState(new MapStateDescriptor<>("localSizes", Integer.class, Integer.class));
        }


        /**
         * Aggregation Handler.
         *
         * 1. Accumulates partial results into 'globalBuffer'.
         * 2. Prunes dominated points globally (incremental BNL).
         * 3. Increments the arrival counter.
         * 4. When counter == totalPartitions:
         * - Calculates "Optimality" (Local survivors / Total local size).
         * - Calculates Time metrics (Ingestion, Local CPU, Global Latency).
         * - Constructs and emits the JSON response.
         *
         * @param input The partial result package from a single partition.
         * @param ctx   Context.
         * @param out   Output collector for the final JSON string.
         */
        @Override
        public void processElement(Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long> input, Context ctx, Collector<String> out) throws Exception {
            List<ServiceTuple> currentGlobal = globalBuffer.value();
            if (currentGlobal == null) currentGlobal = new ArrayList<>();

            Integer count = arrivedCount.value();
            if (count == null) count = 0;

            // Update Timing Stats
            // Track the globally minimum start time to measure total job duration
            Long incomingStartTime = input.f3;
            Long currentMinStart = minStartTimeState.value();
            if (currentMinStart == null || (incomingStartTime != null && incomingStartTime < currentMinStart)) {
                minStartTimeState.update(incomingStartTime);
            }

            // Track wall-clock time
            long now = System.currentTimeMillis();
            lastArrivalState.update(now);

            // Track maximum CPU usage seen so far (straggler analysis)
            Long incomingCpu = input.f5;
            Long currentMaxCpu = maxLocalCpuState.value();
            if (currentMaxCpu == null || incomingCpu > currentMaxCpu) {
                maxLocalCpuState.update(incomingCpu);
            }

            // Track Size for Optimality (Ratio of local survivors to global survivors)
            int partitionId = input.f0;
            List<ServiceTuple> incoming = input.f4;
            localSkylineSizes.put(partitionId, incoming.size());

            // Merge Logic (Incremental BNL)
            // Merge the incoming partial skyline into the global candidate set.
            if (incoming != null && !incoming.isEmpty()) {
                for (ServiceTuple candidate : incoming) {
                    boolean isDominated = false;
                    Iterator<ServiceTuple> it = currentGlobal.iterator();
                    while (it.hasNext()) {
                        ServiceTuple existing = it.next();
                        if (existing.dominates(candidate)) {
                            isDominated = true;
                            break;
                        }
                        if (candidate.dominates(existing)) {
                            it.remove();
                        }
                    }
                    if (!isDominated) {
                        currentGlobal.add(candidate);
                    }
                }
            }

            globalBuffer.update(currentGlobal);
            arrivedCount.update(count + 1);

            // Final Emission
            // Only emit when ALL partitions have reported back.
            if (count + 1 >= totalPartitions) {
                long jobFinishTime = System.currentTimeMillis();
                Long jobStartTime = minStartTimeState.value();
                Long mapFinishTime = lastArrivalState.value();
                Long maxLocalCpu = maxLocalCpuState.value();

                // --- Timing Metrics ---
                // Calculate Latency Components for reporting
                long mapWallTime = (jobStartTime != null) ? (mapFinishTime - jobStartTime) : 0;
                long localProcessingTime = (maxLocalCpu != null) ? maxLocalCpu : 0;
                long ingestionTime = mapWallTime - localProcessingTime;
                if (ingestionTime < 0) ingestionTime = 0;

                long globalProcessingTime = jobFinishTime - mapFinishTime;
                long totalProcessingTime = (jobStartTime != null) ? (jobFinishTime - jobStartTime) : 0;
                long queryLatency = jobFinishTime - input.f2; // trigger time from input

                // Optimality Metric Calculation
                // Defined as: Average percentage of local skyline points that survived the global prune.
                // High Optimality = Local Partitions did a good job filtering points locally.
                java.util.Map<Integer, Integer> survivors = new java.util.HashMap<>();
                for(ServiceTuple s : currentGlobal) {
                    survivors.put(s.originPartition, survivors.getOrDefault(s.originPartition, 0) + 1);
                }

                double sumRatios = 0.0;
                for (int i = 0; i < totalPartitions; i++) {
                    if(localSkylineSizes.contains(i)) {
                        int localSize = localSkylineSizes.get(i);
                        int survivorCount = survivors.getOrDefault(i, 0);
                        if (localSize > 0) {
                            sumRatios += (double) survivorCount / localSize;
                        }
                    }
                }
                double optimality = sumRatios / totalPartitions;

                //remove comment if you want to visualise the points(its crashes if data>2mil)
                // --- C. Visualization Data (Objective 1) ---
//                StringBuilder pointsJson = new StringBuilder("[");
//                for (int i = 0; i < currentGlobal.size(); i++) {
//                    ServiceTuple s = currentGlobal.get(i);
//                    pointsJson.append("[");
//                    for(int j=0; j<s.values.length; j++) {
//                        pointsJson.append(s.values[j]);
//                        if(j < s.values.length - 1) pointsJson.append(",");
//                    }
//                    pointsJson.append("]");
//                    if(i < currentGlobal.size() - 1) pointsJson.append(", ");
//                }
//                pointsJson.append("]");

                // --- Build JSON Payload ---
                String payload = ctx.getCurrentKey();   // "QueryID,RecordCount"
                String[] parts = payload.split(",");
                String qId = parts[0];
                String recCount = (parts.length > 1) ? parts[1] : "unknown";

                StringBuilder sb = new StringBuilder();
                sb.append("{");
                sb.append("\"query_id\": \"").append(qId).append("\", ");
                sb.append("\"record_count\": ").append(recCount).append(", ");
                sb.append("\"skyline_size\": ").append(currentGlobal.size()).append(", ");
                sb.append("\"optimality\": ").append(String.format(java.util.Locale.US, "%.4f", optimality)).append(", ");

                sb.append("\"ingestion_time_ms\": ").append(ingestionTime).append(", ");
                sb.append("\"local_processing_time_ms\": ").append(localProcessingTime).append(", ");
                sb.append("\"global_processing_time_ms\": ").append(globalProcessingTime).append(", ");
                sb.append("\"total_processing_time_ms\": ").append(totalProcessingTime);//.append(", ");

                // NOTE: Detailed skyline points excluded from JSON to prevent OOM on large datasets (>2mil records)
                // Uncomment 'pointsJson' logic if visualization is required for small subsets.
                // Also uncomment the line below
                //sb.append("\"skyline_points\": ").append(pointsJson.toString());

                sb.append("}");

                out.collect(sb.toString());

                // Reset state for next query on this key
                globalBuffer.clear();
                arrivedCount.clear();
                lastArrivalState.clear();
                maxLocalCpuState.clear();
                localSkylineSizes.clear();
            }
        }
    }

    // ------------------------------------------------------------------------
    /**
     * Partitioning Logic Container.
     * Contains the implementations of the KeySelector interface that determine how data
     * is distributed among the worker nodes.
     */
    // ------------------------------------------------------------------------
    public static class PartitioningLogic implements Serializable {

        /**
         * Common interface for all Skyline Partitioners.
         * A Partitioner maps a ServiceTuple to an Integer (The Partition ID).
         */
        public interface SkylinePartitioner extends KeySelector<ServiceTuple, Integer> { }

        /**
         * MR-Dim Partitioner.
         *
         * Strategy: Ranges on the first dimension.
         * Partitions the data space into vertical slices based on the value of the 0-th dimension.
         *
         * Inputs: ServiceTuple
         * Output: Integer Partition ID
         */
        public static class DimPartitioner implements SkylinePartitioner {
            private final int partitions;
            private final double maxVal;

            public DimPartitioner(int partitions, double maxVal) {
                this.partitions = partitions;
                this.maxVal = maxVal;
            }

            /**
             * Dimensional Routing Logic.
             *
             * @param t The multi-dimensional service tuple to be routed.
             * @return The partition ID (0 to N-1) corresponding to the data slice.
             *
             * Logic:
             *      Calculate slice width = (MaxDomainValue / TotalPartitions).
             *      Determine index = (t.values[0] / slice_width).
             *      Clamp result to ensure it falls within valid partition bounds.
             */
            @Override
            public Integer getKey(ServiceTuple t) {
                // Determine slice width based on the maximum domain value
                // Map the tuple's first dimension value (values[0]) to a partition index.
                int p = (int) (t.values[0] / (maxVal / partitions));
                return Math.max(0, Math.min(p, partitions - 1));
            }
        }


        // --- MR-Grid Dominance Filter ---
//        public static class GridDominanceFilter extends RichFilterFunction<ServiceTuple> {
//            private final double threshold;
//            public GridDominanceFilter(double maxVal, int dims) {
//                this.threshold = maxVal / 2.0;
//            }
//            @Override
//            public boolean filter(ServiceTuple t) {
//                boolean allWorse = true;
//                for(double v : t.values) {
//                    if (v < threshold) {
//                        allWorse = false;
//                        break;
//                    }
//                }
//                return !allWorse;
//            }
//        }
//

        /**
         * MR-Grid Partitioner.
         *
         * Strategy: Hypercube Grid.
         * Divides the N-dimensional space into quadrants (or hyper-quadrants) using a center midpoint.
         * Uses bitwise operations to map a point's location relative to the center to a partition ID.
         *
         * Inputs: ServiceTuple
         * Output: Integer Partition ID (Bitmask)
         */
        public static class GridPartitioner implements SkylinePartitioner {
            private final int partitions;
            private final double[] mids;

            public GridPartitioner(int partitions, double maxVal, int dims) {
                this.partitions = partitions;
                this.mids = new double[dims];
                // Pre-calculate midpoints (e.g. 5000 if domain is 10000)
                // (thresholds) for the grid split
                for (int i = 0; i < dims; i++) {
                    this.mids[i] = maxVal / 2.0;
                }
            }

            /**
             * Grid-Based Routing Logic.
             *
             * @param t The multi-dimensional service tuple.
             * @return A bitmask Integer acting as the partition ID.
             *
             * Logic:
             *      Iterate through every dimension D[i].
             *      Compare the value of D[i] against the midpoint threshold.
             *      If value >= midpoint, set the i-th bit of the mask to 1.
             *          (e.g., In 2D: 00=BottomLeft, 01=BottomRight, 10=TopLeft, 11=TopRight).
             *      The resulting integer mask identifies the hypercube cell.
             */
            @Override
            public Integer getKey(ServiceTuple t) {
                int mask = 0;

                // Loop through all dimensions.
                // Generate a bitmask based on which "side" of the midpoint the value falls.
                // Example in 2D: 00 (bottom-left), 01 (bottom-right), 10 (top-left), 11 (top-right).
                for (int i = 0; i < t.values.length; i++) {
                    // If dimension i is in the upper half, set bit i to 1
                    if (t.values[i] >= mids[i]) {
                        mask |= (1 << i);
                    }
                }
                // Map the resulting cell ID (mask) directly to a worker partition
                // Note for self: Ensure 'partitions' >= 2^dims for this to utilize all workers effectively
                return mask;
            }
        }

        /**
         * MR-Angle Partitioner (Hyperspherical).
         *
         * Strategy: Angular Coordinates.
         * Converts the Cartesian vector into angular coordinates (theta/phi) relative to the origin.
         * This creates cone-shaped partitions which are ideal for handling anti-correlated data
         * where points cluster on the "surface" of the dominance region.
         *
         * Inputs: ServiceTuple
         * Output: Integer Partition ID
         */
        public static class AnglePartitioner implements SkylinePartitioner {
            private final int partitions;
            private final int dims;

            public AnglePartitioner(int partitions, int dims) {
                this.partitions = partitions;
                this.dims = dims;
            }


            /**
             * Hyperspherical Routing Logic.
             *
             * @param t The multi-dimensional service tuple.
             * @return The partition ID derived from the angular position of the point.
             *
             * Logic:
             *      Transform Cartesian coordinates (x, y, z...) into Hyperspherical angles using atan2.
             *          - phi_i = atan2(magnitude_of_remaining_dims, current_dim_value)
             *      Normalize these angles to the range [0, 1).
             *      Linearize the multi-dimensional angular coordinate into a single scalar "position".
             *      Map this scalar position to one of the available partitions.
             */
            @Override
            public Integer getKey(ServiceTuple t) {
                // For N dimensions, there are N-1 angles needed to describe the direction.
                int numAngles = dims - 1;

                // // 1D data edge case
                if (numAngles < 1) return 0;

                // Convert to Hyperspherical Coordinates
                // Calculate all angles phi_1 to phi_{n-1} based on Equation
                // phi_i corresponds to the angle between axis i and the rest of the vector.
                double[] angles = new double[numAngles];

                for (int i = 0; i < numAngles; i++) {
                    double v_i = t.values[i];

                    // Calculate magnitude of the remaining dimensions (v_{i+1} ... v_n)
                    double sumSqRest = 0.0;
                    for (int j = i + 1; j < dims; j++) {
                        sumSqRest += t.values[j] * t.values[j];
                    }
                    double hyp = Math.sqrt(sumSqRest);

                    // Calculate angle using atan2 (returns -pi to pi, but data is positive so 0 to pi/2)
                    angles[i] = Math.atan2(hyp, v_i);
                }

                // Linearize Angular Space
                // Map the multi-dimensional angular vector to a single linear partition ID.
                // Heuristic: Normalize angles to [0, 1) and average them to determine sectors
                double maxAngle = Math.PI / 2.0;
                long linearizedID = 0;
                // Normalize all angles to 0.0 -> 1.0 range
                double normalizedSum = 0.0;
                for(int k=0; k < numAngles; k++) {
                    // weighted position: earlier angles often separate space more significantly in HS coords
                    normalizedSum += (angles[k] / maxAngle);
                }

                // Average normalized position to find "sector" in the linear sequence
                double avgPosition = normalizedSum / numAngles;

                // Map to partition range
                // Scale to the number of partitions
                int p = (int) (avgPosition * partitions);

                // Returning the mapping based on the aggregated angular position
                // Ensure result is within bounds [0, partitions-1]
                return Math.max(0, Math.min(p, partitions - 1));
            }
        }
    }
}
