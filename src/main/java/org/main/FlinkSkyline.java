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
import org.apache.flink.formats.avro.registry.confluent.ConfluentRegistryAvroDeserializationSchema;
import org.apache.flink.formats.avro.registry.confluent.ConfluentRegistryAvroSerializationSchema;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.co.CoProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.main.avro.ServiceTupleAvro;
import org.main.avro.QueryTriggerAvro;
import org.main.avro.SkylineResultAvro;


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
        final String dlqTopic = params.get("dlq-topic", "input-tuples-dlq");
        final double domainMax = params.getDouble("domain", 1000.0);
        final int dims = params.getInt("dims", 2);
        final long windowSizeMs = params.getLong("window-size-ms", 5000L);
        final long idlenessSec = params.getLong("watermark.idleness-sec", 5L);
        final String bootstrapServers = params.get("bootstrap-servers", "localhost:9092");
        final String schemaRegistryUrl = params.get("schema-registry-url", "http://localhost:8082");

        // Empirically(Based on the paper) partitions set to 2x number of nodes to ensure decent load distribution
        // even if data is skewed.
        final int numPartitions = 2 * parallelism;

        // Initialize the StreamExecutionEnvironment. This is the context in which the program is executed.
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(parallelism);
        env.getConfig().setGlobalJobParameters(params);

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
        KafkaSource<ServiceTupleAvro> tupleSrc = KafkaSource.<ServiceTupleAvro>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(inputTopic)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(
                        ConfluentRegistryAvroDeserializationSchema.forSpecific(
                                ServiceTupleAvro.class,
                                schemaRegistryUrl
                        )
                )
                .build();

        // Query Source: Read from latest. Acts as a control stream to trigger computation.
        KafkaSource<QueryTriggerAvro> querySrc = KafkaSource.<QueryTriggerAvro>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics(queryTopic)
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(
                        ConfluentRegistryAvroDeserializationSchema.forSpecific(
                                QueryTriggerAvro.class,
                                schemaRegistryUrl
                        )
                )
                .build();

        // Ingest the Data and validate/route to DLQ
        // We configure Flink Event Time and Watermark Strategy with Idleness support.
        org.apache.flink.api.common.eventtime.WatermarkStrategy<ServiceTupleAvro> tupleWatermarkStrategy = 
                org.apache.flink.api.common.eventtime.WatermarkStrategy
                        .<ServiceTupleAvro>forMonotonousTimestamps()
                        .withTimestampAssigner((event, timestamp) -> event.getTimestamp())
                        .withIdleness(java.time.Duration.ofSeconds(idlenessSec));

        org.apache.flink.api.common.eventtime.WatermarkStrategy<QueryTriggerAvro> queryWatermarkStrategy = 
                org.apache.flink.api.common.eventtime.WatermarkStrategy
                        .<QueryTriggerAvro>forMonotonousTimestamps()
                        .withTimestampAssigner((event, timestamp) -> event.getTimestamp())
                        .withIdleness(java.time.Duration.ofSeconds(idlenessSec));

        final OutputTag<ServiceTupleAvro> dlqTag = new OutputTag<ServiceTupleAvro>("input-tuples-dlq-tag") {};

        SingleOutputStreamOperator<ServiceTuple> validatedData = env
                .fromSource(tupleSrc, tupleWatermarkStrategy, "Data")
                .process(new TupleValidator(dlqTag, dims))
                .name("TupleValidator");

        DataStream<ServiceTupleAvro> dlqStream = validatedData.getSideOutput(dlqTag);

        // Sink DLQ records to Kafka DLQ Topic
        dlqStream.sinkTo(KafkaSink.<ServiceTupleAvro>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.<ServiceTupleAvro>builder()
                        .setTopic(dlqTopic)
                        .setValueSerializationSchema(
                                ConfluentRegistryAvroSerializationSchema.forSpecific(
                                        ServiceTupleAvro.class,
                                        dlqTopic + "-value",
                                        schemaRegistryUrl
                                )
                        ).build())
                .build())
                .name("DLQSink");

        // Apply Partitioning Strategy
        PartitioningLogic.SkylinePartitioner partitioner;
        switch (algo) {
            case "mr-dim":
                partitioner = new PartitioningLogic.DimPartitioner(numPartitions, domainMax);
                break;
            case "mr-grid":
                partitioner = new PartitioningLogic.GridPartitioner(numPartitions, domainMax, dims);
                break;
            default:
                partitioner = new PartitioningLogic.AnglePartitioner(numPartitions, dims);
                break;
        }

        // Apply GridDominanceFilter if the algorithm is MR-Grid (Re-enabled & mathematically sound)
        DataStream<ServiceTuple> processedData = validatedData;
        if ("mr-grid".equals(algo)) {
            processedData = validatedData.filter(new PartitioningLogic.GridDominanceFilter()).name("GridDominanceFilter");
        }

        // Map tuples to PartitionEvent using KeySelector
        DataStream<PartitionEvent> partitionedTuples = processedData.map(new MapFunction<ServiceTuple, PartitionEvent>() {
            @Override
            public PartitionEvent map(ServiceTuple value) throws Exception {
                int key = partitioner.getKey(value);
                return PartitionEvent.fromTuple(value, key);
            }
        }).name("TupleToPartitionEventMapper");

        // Read query triggers and broadcast to all partitions as PartitionEvent
        DataStream<QueryTriggerAvro> queryStream = env.fromSource(querySrc, queryWatermarkStrategy, "Queries");
        
        DataStream<PartitionEvent> partitionedQueries = queryStream.flatMap(new FlatMapFunction<QueryTriggerAvro, PartitionEvent>() {
            @Override
            public void flatMap(QueryTriggerAvro trigger, Collector<PartitionEvent> out) {
                for (int i = 0; i < numPartitions; i++) {
                    out.collect(PartitionEvent.fromQuery(
                            trigger.getQueryId().toString(),
                            trigger.getRequiredCount(),
                            i,
                            trigger.getTimestamp()
                    ));
                }
            }
        }).name("QueryToPartitionEventMapper");

        // Union both streams and key by partition ID
        KeyedStream<PartitionEvent, Integer> keyedUnionStream = partitionedTuples
                .union(partitionedQueries)
                .keyBy(e -> e.partitionId);

        // Local Processing Phase (Tumbling Window on Event Time)
        DataStream<Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>> localSkylines = keyedUnionStream
                .window(TumblingEventTimeWindows.of(Time.milliseconds(windowSizeMs)))
                .process(new LocalSkylineWindowProcessor(dims))
                .name("LocalSkylineWindowProcessor");

        // Global Aggregation Phase
        // Group by the QUERY STRING (f1) so all partial results for a specific query land on the same reducer.
        // We key by the Query String (f1) so that all partial results for the exact same query
        // end up at the same Reducer instance.
        DataStream<SkylineResultAvro> finalResults = localSkylines
                .keyBy(t -> t.f1) // Use f1 (Query String) as key
                .process(new GlobalSkylineAggregator(numPartitions))
                .name("GlobalReducer");

        // Sink Results to Kafka
        finalResults.sinkTo(KafkaSink.<SkylineResultAvro>builder()
                .setBootstrapServers(bootstrapServers)
                .setProperty("max.request.size", "10485760") // Increase max request size for large skyline payloads
                .setRecordSerializer(KafkaRecordSerializationSchema.<SkylineResultAvro>builder()
                        .setTopic(outputTopic)
                        .setValueSerializationSchema(
                                ConfluentRegistryAvroSerializationSchema.forSpecific(
                                        SkylineResultAvro.class,
                                        outputTopic + "-value",
                                        schemaRegistryUrl
                                )
                        ).build())
                .build());

        env.execute("Flink Skyline: " + algo);
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
    public static class GlobalSkylineAggregator extends KeyedProcessFunction<String, Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>, SkylineResultAvro> {

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
            org.apache.flink.api.common.ExecutionConfig executionConfig = 
                    getRuntimeContext().getExecutionConfig();
            org.apache.flink.api.common.ExecutionConfig.GlobalJobParameters globalParams = 
                    executionConfig.getGlobalJobParameters();
            
            int ttlHours = 2;
            if (globalParams instanceof org.apache.flink.api.java.utils.ParameterTool) {
                ttlHours = ((org.apache.flink.api.java.utils.ParameterTool) globalParams).getInt("state.ttl-hours", 2);
            }

            StateTtlConfig ttlConfig = StateTtlConfig
                    .newBuilder(org.apache.flink.api.common.time.Time.hours(ttlHours))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                    .build();

            ValueStateDescriptor<List<ServiceTuple>> gBufferDesc = new ValueStateDescriptor<>("gBuffer", TypeInformation.of(new TypeHint<List<ServiceTuple>>() {}));
            gBufferDesc.enableTimeToLive(ttlConfig);
            globalBuffer = getRuntimeContext().getState(gBufferDesc);

            ValueStateDescriptor<Integer> cntDesc = new ValueStateDescriptor<>("cnt", Integer.class);
            cntDesc.enableTimeToLive(ttlConfig);
            arrivedCount = getRuntimeContext().getState(cntDesc);

            ValueStateDescriptor<Long> minStartDesc = new ValueStateDescriptor<>("minStart", Long.class);
            minStartDesc.enableTimeToLive(ttlConfig);
            minStartTimeState = getRuntimeContext().getState(minStartDesc);

            ValueStateDescriptor<Long> lastArrDesc = new ValueStateDescriptor<>("lastArr", Long.class);
            lastArrDesc.enableTimeToLive(ttlConfig);
            lastArrivalState = getRuntimeContext().getState(lastArrDesc);

            ValueStateDescriptor<Long> maxCpuDesc = new ValueStateDescriptor<>("maxCpu", Long.class);
            maxCpuDesc.enableTimeToLive(ttlConfig);
            maxLocalCpuState = getRuntimeContext().getState(maxCpuDesc);

            MapStateDescriptor<Integer, Integer> localSizesDesc = new MapStateDescriptor<>("localSizes", Integer.class, Integer.class);
            localSizesDesc.enableTimeToLive(ttlConfig);
            localSkylineSizes = getRuntimeContext().getMapState(localSizesDesc);
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
        public void processElement(Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long> input, Context ctx, Collector<SkylineResultAvro> out) throws Exception {
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

                // --- C. Visualization Data (Objective 1) ---
                // Capped visualization points to 500 to prevent overhead
                int visualPointsCap = Math.min(currentGlobal.size(), 500);
                StringBuilder pointsJson = new StringBuilder("[");
                for (int i = 0; i < visualPointsCap; i++) {
                    ServiceTuple s = currentGlobal.get(i);
                    pointsJson.append("[");
                    for(int j=0; j<s.values.length; j++) {
                        pointsJson.append(s.values[j]);
                        if(j < s.values.length - 1) pointsJson.append(",");
                    }
                    pointsJson.append("]");
                    if(i < visualPointsCap - 1) pointsJson.append(", ");
                }
                pointsJson.append("]");

                // --- Build Avro Result ---
                String payload = ctx.getCurrentKey();   // "QueryID,RecordCount"
                String[] parts = payload.split(",");
                String qId = parts[0];
                String recCount = (parts.length > 1) ? parts[1] : "unknown";

                long recordCountVal = -1L;
                try {
                    recordCountVal = Long.parseLong(recCount);
                } catch (NumberFormatException e) {
                    // Ignore, keep -1L
                }

                SkylineResultAvro resultRecord = new SkylineResultAvro();
                resultRecord.setQueryId(qId);
                resultRecord.setRecordCount(recordCountVal);
                resultRecord.setSkylineSize(currentGlobal.size());
                resultRecord.setOptimality(optimality);
                resultRecord.setIngestionTimeMs(ingestionTime);
                resultRecord.setLocalProcessingTimeMs(localProcessingTime);
                resultRecord.setGlobalProcessingTimeMs(globalProcessingTime);
                resultRecord.setTotalProcessingTimeMs(totalProcessingTime);
                resultRecord.setLatencyMs(queryLatency);
                resultRecord.setPointsJson(pointsJson.toString());

                out.collect(resultRecord);

                // Reset state for next query on this key
                globalBuffer.clear();
                arrivedCount.clear();
                lastArrivalState.clear();
                maxLocalCpuState.clear();
                localSkylineSizes.clear();
            }
        }
    }

    /**
     * Tuple Validator & DLQ Router.
     *
     * Validates incoming ServiceTupleAvro elements:
     * - Ensures the record is not null.
     * - Ensures ID is present and not empty.
     * - Ensures values are present and not empty.
     * - Ensures no value within dimensions is null.
     *
     * Valid records are mapped to ServiceTuple and emitted to the main stream.
     * Invalid/malformed records are directed to a Dead Letter Queue (DLQ) side output.
     */
    public static class TupleValidator extends ProcessFunction<ServiceTupleAvro, ServiceTuple> {
        private final OutputTag<ServiceTupleAvro> dlqTag;
        private final int expectedDims;

        public TupleValidator(OutputTag<ServiceTupleAvro> dlqTag, int expectedDims) {
            this.dlqTag = dlqTag;
            this.expectedDims = expectedDims;
        }

        @Override
        public void processElement(ServiceTupleAvro value, Context ctx, Collector<ServiceTuple> out) throws Exception {
            if (value == null) {
                return;
            }

            boolean isValid = true;
            try {
                if (value.getId() == null || value.getId().toString().trim().isEmpty()) {
                    isValid = false;
                }
                
                if (value.getValues() == null || value.getValues().size() != expectedDims) {
                    isValid = false;
                } else {
                    for (Double val : value.getValues()) {
                        if (val == null) {
                            isValid = false;
                            break;
                        }
                    }
                }

                if (isValid) {
                    ServiceTuple parsed = ServiceTuple.fromAvro(value);
                    if (parsed != null) {
                        out.collect(parsed);
                    } else {
                        ctx.output(dlqTag, value);
                    }
                } else {
                    ctx.output(dlqTag, value);
                }
            } catch (Exception e) {
                ctx.output(dlqTag, value);
            }
        }
    }

    /**
     * Common event class representing either a Tuple or a Query trigger for window processing.
     */
    public static class PartitionEvent implements Serializable {
        public int partitionId;
        public boolean isQuery;
        public ServiceTuple tuple;
        public String queryId;
        public long requiredCount;
        public long timestamp;

        public PartitionEvent() {}

        public static PartitionEvent fromTuple(ServiceTuple tuple, int partitionId) {
            PartitionEvent e = new PartitionEvent();
            e.partitionId = partitionId;
            e.isQuery = false;
            e.tuple = tuple;
            e.timestamp = tuple.timestamp;
            return e;
        }

        public static PartitionEvent fromQuery(String queryId, long requiredCount, int partitionId, long timestamp) {
            PartitionEvent e = new PartitionEvent();
            e.partitionId = partitionId;
            e.isQuery = true;
            e.queryId = queryId;
            e.requiredCount = requiredCount;
            e.timestamp = timestamp;
            return e;
        }
    }

    /**
     * In-memory K-Dimensional Tree (KD-Tree) for fast multi-dimensional skyline dominance checking.
     */
    public static class KDTree implements Serializable {
        public static class Node implements Serializable {
            public ServiceTuple point;
            public Node left;
            public Node right;
            public int splitDim;

            public Node(ServiceTuple point, int splitDim) {
                this.point = point;
                this.splitDim = splitDim;
            }
        }

        private Node root;
        private final int dims;

        public KDTree(int dims) {
            this.dims = dims;
        }

        public void insert(ServiceTuple point) {
            root = insertRec(root, point, 0);
        }

        private Node insertRec(Node root, ServiceTuple point, int depth) {
            if (root == null) {
                return new Node(point, depth % dims);
            }

            int cd = depth % dims;
            if (point.values[cd] < root.point.values[cd]) {
                root.left = insertRec(root.left, point, depth + 1);
            } else {
                root.right = insertRec(root.right, point, depth + 1);
            }
            return root;
        }

        public boolean isDominated(ServiceTuple candidate) {
            return isDominatedRec(root, candidate);
        }

        private boolean isDominatedRec(Node node, ServiceTuple candidate) {
            if (node == null) return false;

            if (node.point.dominates(candidate)) {
                return true;
            }

            int cd = node.splitDim;
            if (candidate.values[cd] < node.point.values[cd]) {
                if (isDominatedRec(node.left, candidate)) return true;
            } else {
                if (isDominatedRec(node.left, candidate)) return true;
                if (isDominatedRec(node.right, candidate)) return true;
            }
            return false;
        }

        public void rebuild(List<ServiceTuple> points) {
            root = buildTree(points, 0);
        }

        private Node buildTree(List<ServiceTuple> points, int depth) {
            if (points.isEmpty()) return null;

            int cd = depth % dims;
            points.sort((a, b) -> Double.compare(a.values[cd], b.values[cd]));
            int medianIdx = points.size() / 2;

            Node node = new Node(points.get(medianIdx), cd);
            node.left = buildTree(new ArrayList<>(points.subList(0, medianIdx)), depth + 1);
            node.right = buildTree(new ArrayList<>(points.subList(medianIdx + 1, points.size())), depth + 1);
            return node;
        }
    }

    /**
     * Flink window process function that executes the local skyline query computation
     * on partitioned streams.
     */
    public static class LocalSkylineWindowProcessor 
            extends ProcessWindowFunction<PartitionEvent, Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>, Integer, TimeWindow> {

        private transient ListState<ServiceTuple> localSkylineState;
        private transient ValueState<Long> startTimeState;
        private transient ValueState<Long> accumulatedCpuNanosState;
        private final int dims;

        public LocalSkylineWindowProcessor(int dims) {
            this.dims = dims;
        }

        @Override
        public void open(Configuration config) throws Exception {
            org.apache.flink.api.common.ExecutionConfig executionConfig = 
                    getRuntimeContext().getExecutionConfig();
            org.apache.flink.api.common.ExecutionConfig.GlobalJobParameters globalParams = 
                    executionConfig.getGlobalJobParameters();
            
            int ttlHours = 2;
            if (globalParams instanceof org.apache.flink.api.java.utils.ParameterTool) {
                ttlHours = ((org.apache.flink.api.java.utils.ParameterTool) globalParams).getInt("state.ttl-hours", 2);
            }

            StateTtlConfig ttlConfig = StateTtlConfig
                    .newBuilder(org.apache.flink.api.common.time.Time.hours(ttlHours))
                    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                    .build();

            ListStateDescriptor<ServiceTuple> desc = new ListStateDescriptor<>("localSky", ServiceTuple.class);
            desc.enableTimeToLive(ttlConfig);
            localSkylineState = getRuntimeContext().getListState(desc);

            startTimeState = getRuntimeContext().getState(new ValueStateDescriptor<>("jobStartTime", Long.class));
            accumulatedCpuNanosState = getRuntimeContext().getState(new ValueStateDescriptor<>("cpuTime", Long.class));
        }

        @Override
        public void process(
                Integer partitionId,
                Context context,
                Iterable<PartitionEvent> elements,
                Collector<Tuple6<Integer, String, Long, Long, List<ServiceTuple>, Long>> out) throws Exception {

            long startNano = System.nanoTime();

            if (startTimeState.value() == null) {
                startTimeState.update(System.currentTimeMillis());
            }

            Iterable<ServiceTuple> stateIter = localSkylineState.get();
            List<ServiceTuple> currentSkyline = new ArrayList<>();
            if (stateIter != null) {
                for (ServiceTuple s : stateIter) {
                    currentSkyline.add(s);
                }
            }

            List<ServiceTuple> incomingTuples = new ArrayList<>();
            List<PartitionEvent> queries = new ArrayList<>();
            for (PartitionEvent event : elements) {
                if (event.isQuery) {
                    queries.add(event);
                } else if (event.tuple != null) {
                    incomingTuples.add(event.tuple);
                }
            }

            if (!incomingTuples.isEmpty()) {
                KDTree tree = new KDTree(dims);
                if (!currentSkyline.isEmpty()) {
                    tree.rebuild(new ArrayList<>(currentSkyline));
                }

                List<ServiceTuple> newSurvivors = new ArrayList<>();
                for (ServiceTuple candidate : incomingTuples) {
                    if (currentSkyline.isEmpty() || !tree.isDominated(candidate)) {
                        Iterator<ServiceTuple> it = currentSkyline.iterator();
                        while (it.hasNext()) {
                            if (candidate.dominates(it.next())) {
                                it.remove();
                            }
                        }
                        boolean isDominatedByNew = false;
                        Iterator<ServiceTuple> itNew = newSurvivors.iterator();
                        while (itNew.hasNext()) {
                            ServiceTuple ns = itNew.next();
                            if (ns.dominates(candidate)) {
                                isDominatedByNew = true;
                                break;
                            }
                            if (candidate.dominates(ns)) {
                                itNew.remove();
                            }
                        }
                        if (!isDominatedByNew) {
                            newSurvivors.add(candidate);
                        }
                    }
                }
                currentSkyline.addAll(newSurvivors);
                localSkylineState.update(currentSkyline);
            }

            long duration = System.nanoTime() - startNano;
            Long currentCpu = accumulatedCpuNanosState.value();
            long totalCpuNanos = (currentCpu == null ? 0 : currentCpu) + duration;
            accumulatedCpuNanosState.update(totalCpuNanos);

            if (!queries.isEmpty()) {
                long partitionStartTime = startTimeState.value();
                long totalCpuMillis = totalCpuNanos / 1_000_000L;

                for (PartitionEvent query : queries) {
                    String queryPayload = query.queryId + "," + query.requiredCount;
                    long triggerDispatchTime = query.timestamp;

                    List<ServiceTuple> results = new ArrayList<>();
                    for (ServiceTuple s : currentSkyline) {
                        ServiceTuple copy = new ServiceTuple(s.id, s.values, s.timestamp);
                        copy.originPartition = partitionId;
                        results.add(copy);
                    }

                    out.collect(new Tuple6<>(
                            partitionId,
                            queryPayload,
                            triggerDispatchTime,
                            partitionStartTime,
                            results,
                            totalCpuMillis
                    ));
                }
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
        public static class GridDominanceFilter extends RichFilterFunction<ServiceTuple> {
            private transient ListState<ServiceTuple> pruningState;
            private final int maxPruningPoints = 50;

            @Override
            public void open(Configuration config) throws Exception {
                pruningState = getRuntimeContext().getListState(
                        new ListStateDescriptor<>("pruningPoints", ServiceTuple.class)
                );
            }

            @Override
            public boolean filter(ServiceTuple t) throws Exception {
                Iterable<ServiceTuple> stateIter = pruningState.get();
                List<ServiceTuple> pruningPoints = new ArrayList<>();
                if (stateIter != null) {
                    for (ServiceTuple p : stateIter) {
                        pruningPoints.add(p);
                    }
                }

                boolean isDominated = false;
                Iterator<ServiceTuple> it = pruningPoints.iterator();
                while (it.hasNext()) {
                    ServiceTuple p = it.next();
                    if (p.dominates(t)) {
                        isDominated = true;
                        break;
                    }
                    if (t.dominates(p)) {
                        it.remove();
                    }
                }

                if (isDominated) {
                    pruningState.update(pruningPoints);
                    return false;
                }

                if (pruningPoints.size() < maxPruningPoints) {
                    pruningPoints.add(t);
                } else {
                    int worstIdx = -1;
                    double maxSum = -1.0;
                    double tSum = 0.0;
                    for (double v : t.values) tSum += v;

                    for (int i = 0; i < pruningPoints.size(); i++) {
                        double sum = 0.0;
                        for (double v : pruningPoints.get(i).values) sum += v;
                        if (sum > maxSum) {
                            maxSum = sum;
                            worstIdx = i;
                        }
                    }

                    if (tSum < maxSum && worstIdx != -1) {
                        pruningPoints.set(worstIdx, t);
                    }
                }

                pruningState.update(pruningPoints);
                return true;
            }
        }

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
                for (int i = 0; i < Math.min(t.values.length, mids.length); i++) {
                    // If dimension i is in the upper half, set bit i to 1
                    if (t.values[i] >= mids[i]) {
                        mask |= (1 << i);
                    }
                }
                // Map the resulting cell ID (mask) to a valid partition ID in range [0, partitions - 1]
                return mask % partitions;
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
