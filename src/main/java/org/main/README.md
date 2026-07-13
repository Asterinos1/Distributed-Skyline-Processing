## Core Java Components

The following files contain the primary business logic for the distributed Skyline computation, handling the stream processing topology, partitioning strategies, and the Block-Nested Loop (BNL) algorithm.

* **`FlinkSkyline.java`**: The main execution entry point for the Flink Job.
    * **Configuration Parameters**:

      The job accepts the following CLI arguments via `ParameterTool`:
      | Parameter | Default | Description |
      | :--- | :--- | :--- |
      | `--parallelism` | `4` | Sets the job parallelism. Note that the partition count is explicitly set to `2 * parallelism` to ensure load distribution. |
      | `--algo` | `mr-angle` | Selects the partitioning strategy. Options: `mr-dim`, `mr-grid`, `mr-angle`. |
      | `--input-topic` | `input-tuples` | The Kafka topic source for raw data tuples. |
      | `--query-topic` | `queries` | The Kafka topic source for control/trigger signals. |
      | `--output-topic` | `output-skyline`| The Kafka topic sink for the final JSON results. |
      | `--domain` | `1000.0` | The maximum value domain for the data (critical for `mr-dim` and `mr-grid` calculations). |
      | `--dims` | `2` | The dimensionality of the input vectors (critical for `mr-angle` and `mr-grid`). |

    * **Inner Classes**:
        * **`PartitioningLogic`**: Encapsulates the three specific strategies for distributing data among workers:
            * *MR-Dim*: Partitions based on the first dimension's range.
            * *MR-Grid*: Maps points to hypercube grid cells using bitmasks.
            * *MR-Angle*: Converts coordinates to hyperspherical angles to efficiently group anti-correlated data.
        * **`SkylineLocalProcessor`**: A `CoProcessFunction` that executes the Block-Nested Loop (BNL) algorithm locally. It uses a barrier mechanism based on Record IDs to synchronize data ingestion with query triggers.
        * **`GlobalSkylineAggregator`**: The final reducer. It collects partial skylines, performs the global merge, calculates the "Optimality" metric, and emits the final JSON result.

* **`ServiceTuple.java`**: The experimental data model for the experiment. It represents a single multi-dimensional data point (e.g. A Service and its attributes like Latency, Cost).
    * **Key Features**:
        * Implements `Serializable` for safe transport across Flink network partitions.
        * Contains the `dominates(other)` logic, which enforces a **minimization** strategy (lower values are better).
        * Includes a `fromString` factory method to parse raw CSV strings from Kafka.
