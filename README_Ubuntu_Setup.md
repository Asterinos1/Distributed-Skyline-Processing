# Distributed Skyline Query Processing (Flink & Kafka)

This project implements a distributed Skyline Query (Pareto Frontier) algorithm using **Apache Flink** for stream processing and **Apache Kafka** for data ingestion and result collection. The system includes a Python-based data generator, metrics collector, and visualization tools.

## Prerequisites

Ensure the following are installed and configured in your `~/big_data` directory:
* **Apache Kafka** (v3.7.2)
* **Apache Flink** (v1.20)
* **Python 3.8+** (with `venv` support)
* **Java 11** (for Flink/Kafka)

---

## Setup & Execution Guide

Open multiple terminal tabs (or split panes) to run the various components of the distributed system simultaneously.

### Step 1: Infrastructure (Zookeeper & Kafka)
Start the messaging infrastructure first. Kafka requires Zookeeper to be running.

**Terminal 1: Start Zookeeper**
```bash
cd ~/big_data/kafka
bin/zookeeper-server-start.sh config/zookeeper.properties
```

**Terminal 2: Start Kafka Server**
*Wait for Zookeeper to initialize before running this.*
```bash
cd ~/big_data/kafka
bin/kafka-server-start.sh config/server.properties
```

---

### Step 2: Flink Cluster & Cleanup
Initialize the Flink processing cluster and clean up any old data from previous runs to ensure a fresh experiment.

**Terminal 3: Cluster Management**
```bash
# 1. Start the local Flink cluster
cd ~/big_data/flink
./bin/start-cluster.sh

# 2. Reset Kafka topics (Clears old queue data)
cd ~/big_data
./reset_topics.sh
```

---

### Step 3: Run the Flink Job
*Note: You must submit your compiled Java JAR to the Flink cluster now. If you are running from an IDE (IntelliJ/Eclipse), start the `FlinkSkyline.main()` method now.*

```bash
# Example Command (if running via CLI)
cd ~/big_data/flink
./bin/flink run -c org.main.FlinkSkyline path/to/your-project.jar --algo mr-angle --parallelism 4
```

---

### Step 4: Monitoring & Metrics
Start the consumers *before* the producer so you capture all generated results.

**Terminal 5: Metrics Collector**
Listens to the `output-skyline` topic and saves performance metrics to a CSV file.
```bash
cd ~/big_data/skyline_project/python 
source venv/bin/activate

# Usage: python3 metrics_collector.py <output_filename.csv>
python3 metrics_collector.py test.csv
```

**Terminal 6: Debug Console (Optional)**
Use this to visually verify that raw data is arriving in Kafka if something looks wrong.
```bash
~/big_data/kafka/bin/kafka-console-consumer.sh --topic output-skyline --bootstrap-server localhost:9092
```

---

### Step 5: Data Production
Start the data stream. This will flood the system with synthetic tuples, triggering the Flink job.

**Terminal 4: Data Generator**
```bash
cd ~/big_data/skyline_project/python 
source venv/bin/activate

# Syntax: python3 unified_producer.py <topic> <distribution> <dims> <min_val> <max_val>
# Example: Anti-Correlated data, 2 Dimensions, Range 0-10000
python3 unified_producer.py input-tuples anti_correlated 2 0 10000
```

| Argument | Options | Description |
| :--- | :--- | :--- |
| `<topic>` | `input-tuples` | The Kafka topic name for raw data. |
| `<distribution>` | `uniform`, `correlated`, `anti_correlated` | The statistical distribution of points. |
| `<dims>` | `2`, `3`, `4`, etc. | Number of dimensions per tuple. |
| `<min/max>` | Integer | The domain range (e.g., 0 to 10000). |

---

### Step 6: Visualization
Once the experiment is finished (or you have stopped the collector), generate graphs from the CSV results.

**Terminal 7: Plotting**
```bash
cd ~/big_data/skyline_project/python
source venv/bin/activate

# Run your specific visualization script
python3 graph_skyline_2d.py test.csv
# OR
python3 graph_performance.py test.csv
```

---

## Shutdown
To stop the environment correctly:
1. Stop the **Producer** (Ctrl+C in Tab 4).
2. Stop the **Collector** (Ctrl+C in Tab 5).
3. Stop **Flink** (`./bin/stop-cluster.sh` in Tab 3).
4. Stop **Kafka** (Ctrl+C in Tab 2).
5. Stop **Zookeeper** (Ctrl+C in Tab 1).
