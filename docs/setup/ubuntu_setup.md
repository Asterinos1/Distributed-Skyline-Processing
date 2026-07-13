# Distributed Skyline Query Processing (Flink & Kafka) - Ubuntu Setup Guide

This guide describes how to deploy and run the containerized Flink and Kafka environment on Ubuntu Linux. The infrastructure is orchestrated using Docker Compose, which starts Kafka (in KRaft mode, removing the need for Zookeeper), Confluent Schema Registry, and a Flink Cluster (JobManager + TaskManager).

## Prerequisites

Ensure the following packages are installed on your Ubuntu system:
* **Docker** and **Docker Compose** (v2+)
* **Java 11** (OpenJDK 11)
* **Maven** (to build the Java project)
* **Python 3.8+** (with virtual environment support)

### 1. Install Docker & Compose (if not already installed)
```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER
# Log out and log back in to apply group changes
```

### 2. Install Java 11 & Maven
```bash
sudo apt-get install -y openjdk-11-jdk maven
```

### 3. Install Python Dependencies
```bash
sudo apt-get install -y python3-pip python3-venv
pip3 install -r requirements.txt
```

---

## Setup & Execution Guide

### Step 1: Build the Flink Job
From the root of the repository, compile the Java project into a fat JAR:
```bash
mvn clean package
```
This generates the packaged JAR at `target/Skyline-Project-Flink-1.0-SNAPSHOT.jar`, which is automatically mounted into the Flink containers.

### Step 2: Launch the Infrastructure
Deploy the Docker containers in the background:
```bash
cd deploy
docker compose up -d
```
Verify that all 4 containers are running:
```bash
docker ps
```
You should see:
* `kafka` (port `9092`)
* `schema-registry` (port `8082`)
* `flink-jobmanager` (port `8081`)
* `flink-taskmanager`

---

### Step 3: Run the Automated Benchmark Suite
You can execute a full parameter sweep benchmark directly from the host. The benchmark runner handles job submission, resets topics, rate-limits production, and logs metrics (including CPU and memory consumption) automatically:

```bash
# Run a quick single-trial verification benchmark
python3 python/benchmarks/run_benchmark.py --fast

# Run the full sweep (Warning: 81 configurations, 324 runs, takes ~80 mins)
python3 python/benchmarks/run_benchmark.py
```
Results are saved directly to `benchmark_results.csv`.

---

### Step 4: Manual Run & Live Dashboard (Optional)

If you prefer to submit and trigger queries manually:

#### A. Submit Flink Job via Web UI
1. Open the Flink Dashboard at [http://localhost:8081](http://localhost:8081).
2. Go to **Submit New Job** $\rightarrow$ **Add New** $\rightarrow$ upload `target/Skyline-Project-Flink-1.0-SNAPSHOT.jar`.
3. Select the uploaded JAR and set the **Program Arguments**:
   ```text
   --config /opt/flink/usrlib/config.properties --algo mr-angle --parallelism 4 --dims 3
   ```
4. Click **Submit**.

#### B. Start the Metrics Collector
Listens to `output-skyline` and appends metrics to a CSV:
```bash
python3 python/src/metrics_collector.py results.csv
```

#### C. Start the Data Producer
Produce synthetic data to trigger skyline evaluation (Syntax: `unified_producer.py <topic> <distribution> <dims> <min> <max> <query_topic> <query_threshold> [rate_limit]`):
```bash
# Example: anti-correlated distribution, 3D, query triggered after 20k records, rate limited at 5k recs/sec
python3 python/src/unified_producer.py input-tuples anti_correlated 3 0 10000 queries 20000 5000
```

#### D. Start the Live Dashboard
To view results in real-time on the React web dashboard:
```bash
# Terminal 1: Start the WebSockets bridge
python3 python/src/websocket_bridge.py

# Terminal 2: Run Vite dev server
cd dashboard
npm install
npm run dev
```
Open [http://localhost:5173](http://localhost:5173) in your browser.

---

## Shutdown & Cleanup

To tear down the infrastructure and stop all containers:
```bash
cd deploy
docker compose down
```
