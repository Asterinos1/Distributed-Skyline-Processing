import subprocess
import time
import sys
import os
import csv
import argparse
from confluent_kafka import Consumer, Producer
from confluent_kafka.serialization import SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroSerializer, AvroDeserializer

# Load Avro schema helper
def load_schema(schema_name):
    script_dir = os.path.dirname(os.path.abspath(__file__))
    schema_path = os.path.join(script_dir, "..", "..", "src", "main", "avro", schema_name)
    with open(schema_path, "r") as f:
        return f.read()

def reset_topics():
    print("[Benchmark] Resetting Kafka topics...")
    topics = ["input-tuples", "queries", "output-skyline"]
    for t in topics:
        subprocess.run([
            "docker", "exec", "kafka",
            "/opt/kafka/bin/kafka-topics.sh",
            "--bootstrap-server", "localhost:9092",
            "--delete", "--topic", t, "--if-exists"
        ], capture_output=True)
    
    # Recreate topics
    subprocess.run([
        "docker", "exec", "kafka",
        "/opt/kafka/bin/kafka-topics.sh",
        "--bootstrap-server", "localhost:9092",
        "--create", "--topic", "input-tuples",
        "--partitions", "4", "--replication-factor", "1"
    ], capture_output=True)
    subprocess.run([
        "docker", "exec", "kafka",
        "/opt/kafka/bin/kafka-topics.sh",
        "--bootstrap-server", "localhost:9092",
        "--create", "--topic", "queries",
        "--partitions", "1", "--replication-factor", "1"
    ], capture_output=True)
    subprocess.run([
        "docker", "exec", "kafka",
        "/opt/kafka/bin/kafka-topics.sh",
        "--bootstrap-server", "localhost:9092",
        "--create", "--topic", "output-skyline",
        "--partitions", "4", "--replication-factor", "1"
    ], capture_output=True)
    print("[Benchmark] Kafka topics reset successfully.")

def get_running_jobs():
    res = subprocess.run([
        "docker", "exec", "flink-jobmanager", "flink", "list"
    ], capture_output=True, text=True)
    jobs = []
    for line in res.stdout.splitlines():
        if " : " in line and ("RUNNING" in line or "CREATED" in line):
            parts = [p.strip() for p in line.split(":")]
            if len(parts) >= 3:
                job_id = parts[1].split()[0]
                jobs.append(job_id)
    return jobs

def cancel_all_jobs():
    jobs = get_running_jobs()
    if jobs:
        print(f"[Benchmark] Cancelling {len(jobs)} active Flink jobs...")
        for job_id in jobs:
            subprocess.run([
                "docker", "exec", "flink-jobmanager",
                "flink", "cancel", job_id
            ], capture_output=True)
        # Wait for cancellation
        time.sleep(3)

def capture_resource_usage():
    try:
        res = subprocess.run([
            "docker", "stats", "--no-stream", "--format", "{{.Name}}:{{.CPUPerc}}|{{.MemUsage}}"
        ], capture_output=True, text=True)
        lines = res.stdout.strip().splitlines()
        stats = {}
        for line in lines:
            if ":" in line:
                parts = line.split(":", 1)
                name = parts[0].strip()
                usage = parts[1].strip()
                if "flink-taskmanager" in name:
                    stats["TM_Stats"] = usage
                elif "flink-jobmanager" in name:
                    stats["JM_Stats"] = usage
        return stats
    except Exception as e:
        print(f"[Benchmark] Warning: Failed to capture resource usage: {e}")
        return {}

def run_benchmark_run(algo, parallelism, dist, dims, num_records, rate_limit=0):
    print(f"\n==================================================")
    print(f" Running Run: Algo={algo}, Parallelism={parallelism}, Dist={dist}, Dims={dims}, Records={num_records}, Rate={rate_limit}")
    print(f"==================================================")

    # 1. Clean up active jobs & reset topics
    cancel_all_jobs()
    reset_topics()

    # 2. Submit Flink job
    print(f"[Benchmark] Submitting Flink job...")
    submit_cmd = [
        "docker", "exec", "flink-jobmanager", "flink", "run", "-d",
        "-c", "org.main.FlinkSkyline",
        "/opt/flink/usrlib/Skyline-Project-Flink-1.0-SNAPSHOT.jar",
        "--config", "/opt/flink/usrlib/config.properties",
        "--algo", algo,
        "--parallelism", str(parallelism),
        "--dims", str(dims)
    ]
    res = subprocess.run(submit_cmd, capture_output=True, text=True)
    if "Job has been submitted with JobID" not in res.stdout:
        print("[Benchmark] ERROR: Flink job submission failed!")
        print("Stdout:", res.stdout)
        print("Stderr:", res.stderr)
        return None
    
    # Extract job ID
    job_id = res.stdout.split("JobID")[1].strip()
    print(f"[Benchmark] Flink job submitted. JobID: {job_id}")

    # Wait for Flink job to transition to RUNNING state
    print("[Benchmark] Waiting 8 seconds for Flink initialization...")
    time.sleep(8)

    # 3. Initialize Kafka Schema Registry and Deserializer for consuming results
    schema_registry_client = SchemaRegistryClient({'url': 'http://localhost:8082'})
    result_schema_str = load_schema("skyline_result.avsc")
    deserializer = AvroDeserializer(schema_registry_client, result_schema_str)

    consumer_conf = {
        'bootstrap.servers': 'localhost:9092',
        'group.id': f'benchmark-consumer-{time.time_ns()}',
        'auto.offset.reset': 'earliest'
    }
    consumer = Consumer(consumer_conf)
    consumer.subscribe(["output-skyline"])

    # 4. Launch Producer in background to generate data and send query trigger
    print(f"[Benchmark] Starting producer for {dist} distribution...")
    script_dir = os.path.dirname(os.path.abspath(__file__))
    producer_path = os.path.abspath(os.path.join(script_dir, "..", "src", "unified_producer.py"))
    
    producer_cmd = [
        "python", "-u", producer_path,
        "input-tuples", dist, str(dims), "0", "10000", "queries", str(num_records), str(rate_limit)
    ]
    
    # We will read producer stdout until trigger is sent
    prod_process = subprocess.Popen(producer_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    
    trigger_sent = False
    for line in prod_process.stdout:
        if "[Trigger] Sent" in line:
            print(f"[Benchmark] Producer: {line.strip()}")
            trigger_sent = True
            break
        elif "Sent" in line:
            print(f"[Benchmark] Producer: {line.strip()}")

    if trigger_sent:
        print("[Benchmark] Query trigger sent. Stopping producer...")
        prod_process.terminate()
        prod_process.wait()
    else:
        print("[Benchmark] ERROR: Producer failed to send query trigger!")
        prod_process.terminate()
        prod_process.wait()
        consumer.close()
        return None

    # 5. Send "Flush" records to advance watermarks and close window instantly
    print("[Benchmark] Sending watermark flush records to close the event-time window...")
    try:
        # Initialise producer on host
        tuple_schema_str = load_schema("service_tuple.avsc")
        query_schema_str = load_schema("query_trigger.avsc")
        
        tuple_serializer = AvroSerializer(schema_registry_client, tuple_schema_str)
        query_serializer = AvroSerializer(schema_registry_client, query_schema_str)

        host_producer = Producer({'bootstrap.servers': 'localhost:9092'})
        
        # We send records 15 seconds in the future
        future_ts = int((time.time() + 15) * 1000)
        
        # Flush tuple
        flush_tuple = {
            "id": "flush_tuple",
            "values": [99999.0] * dims,
            "originPartition": -1,
            "timestamp": future_ts
        }
        serialized_val = tuple_serializer(flush_tuple, SerializationContext("input-tuples", MessageField.VALUE))
        for p in range(4):
            host_producer.produce("input-tuples", value=serialized_val, partition=p)
        
        # Flush query trigger
        flush_query = {
            "queryId": "flush_query",
            "requiredCount": int(num_records + 1),
            "timestamp": future_ts
        }
        serialized_query = query_serializer(flush_query, SerializationContext("queries", MessageField.VALUE))
        host_producer.produce("queries", value=serialized_query)
        
        host_producer.flush()
        print("[Benchmark] Watermark flush records sent.")
    except Exception as e:
        print(f"[Benchmark] Warning: Failed to send flush records: {e}")

    # 6. Consume the result
    print("[Benchmark] Waiting for skyline query result...")
    start_time = time.time()
    timeout = 35 # seconds
    result_data = None

    try:
        while time.time() - start_time < timeout:
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                print(f"[Benchmark] Consumer Error: {msg.error()}")
                continue
            
            try:
                data = deserializer(msg.value(), SerializationContext(msg.topic(), MessageField.VALUE))
                if data:
                    q_id = str(data.get("queryId", ""))
                    # Ignore the flush query result
                    if q_id == "flush_query":
                        continue
                    print(f"[Benchmark] Successfully received result for Query {q_id}!")
                    result_data = data
                    break
            except Exception as e:
                print(f"[Benchmark] Deserialization error: {e}")
    finally:
        consumer.close()

    # Capture resource usage while Flink has completed processing but before we cancel
    resource_stats = capture_resource_usage()

    # 7. Cancel the Flink job
    print(f"[Benchmark] Cancelling Flink job {job_id}...")
    subprocess.run([
        "docker", "exec", "flink-jobmanager",
        "flink", "cancel", job_id
    ], capture_output=True)

    if result_data:
        metrics = {
            "Algo": algo,
            "Parallelism": parallelism,
            "Distribution": dist,
            "Dimensions": dims,
            "RateLimit": rate_limit,
            "Records": result_data.get("recordCount", 0),
            "SkylineSize": result_data.get("skylineSize", 0),
            "Optimality": round(result_data.get("optimality", 0.0), 4),
            "IngestTimeMs": result_data.get("ingestionTimeMs", 0),
            "LocalTimeMs": result_data.get("localProcessingTimeMs", 0),
            "GlobalTimeMs": result_data.get("globalProcessingTimeMs", 0),
            "TotalTimeMs": result_data.get("totalProcessingTimeMs", 0),
            "LatencyMs": result_data.get("latencyMs", 0),
            "TaskManager_Resource": resource_stats.get("TM_Stats", "N/A"),
            "JobManager_Resource": resource_stats.get("JM_Stats", "N/A")
        }
        print(f"[Benchmark] Metrics captured:")
        print(f"            Total Processing Time: {metrics['TotalTimeMs']} ms")
        print(f"            Latency:              {metrics['LatencyMs']} ms")
        print(f"            Skyline Size:         {metrics['SkylineSize']} points")
        print(f"            Optimality:           {metrics['Optimality']}")
        print(f"            TaskManager Resource: {metrics['TaskManager_Resource']}")
        return metrics
    else:
        print("[Benchmark] ERROR: Timeout reached! No query result received.")
        return None

def main():
    parser = argparse.ArgumentParser(description="Distributed Skyline Processing Automated Benchmark Runner")
    parser.add_argument("--algos", default="mr-angle,mr-dim,mr-grid", help="Comma-separated algorithms to test")
    parser.add_argument("--parallelisms", default="1,2,4", help="Comma-separated Flink parallelism levels")
    parser.add_argument("--distributions", default="uniform,correlated,anti_correlated", help="Comma-separated distributions to test")
    parser.add_argument("--dims", default="2,3,4", help="Comma-separated dimensions to test")
    parser.add_argument("--records", type=int, default=20000, help="Number of records to stream per run")
    parser.add_argument("--output", default="benchmark_results.csv", help="Output CSV filepath")
    parser.add_argument("--fast", action="store_true", help="Run a quick verification subset (1 run)")
    parser.add_argument("--trials", type=int, default=3, help="Number of measured trials per configuration")
    parser.add_argument("--warmups", type=int, default=1, help="Number of unmeasured warm-up runs per configuration")
    parser.add_argument("--rate", type=int, default=0, help="Producer rate limit in records/second (0 for unlimited)")
    args = parser.parse_args()

    if args.fast:
        # Override to single fast run
        algos = ["mr-angle"]
        parallelisms = [4]
        distributions = ["uniform"]
        dims = [3]
        records = 10000
        trials = 1
        warmups = 0
        rate = 0
    else:
        algos = [a.strip() for a in args.algos.split(",") if a.strip()]
        parallelisms = [int(p.strip()) for p in args.parallelisms.split(",") if p.strip()]
        distributions = [d.strip() for d in args.distributions.split(",") if d.strip()]
        dims = [int(d.strip()) for d in args.dims.split(",") if d.strip()]
        records = args.records
        trials = args.trials
        warmups = args.warmups
        rate = args.rate

    print("\n" + "="*60)
    print("      Distributed Skyline Processing Benchmark Suite")
    print("="*60)
    print(f"Algorithms:    {algos}")
    print(f"Parallelisms:  {parallelisms}")
    print(f"Distributions: {distributions}")
    print(f"Dimensions:    {dims}")
    print(f"Records/run:   {records}")
    print(f"Trials/config: {trials} (Warmups: {warmups})")
    print(f"Rate Limit:    {rate if rate > 0 else 'Unlimited'} records/sec")
    print(f"Output File:   {args.output}")
    print("="*60 + "\n")

    results = []

    # Reorder loops to start with simpler, faster combinations first (lower dimensions and easier distributions)
    sorted_dims = sorted(dims)
    dist_priority = {"correlated": 0, "uniform": 1, "anti_correlated": 2}
    sorted_dists = sorted(distributions, key=lambda x: dist_priority.get(x.lower().strip(), 99))

    print("\n" + "="*60)
    print("      Execution Order: Simple & Faster Configurations First")
    print("="*60)
    print(f"Sorted Dimensions:    {sorted_dims}")
    print(f"Sorted Distributions: {sorted_dists}")
    print("="*60 + "\n")

    try:
        for d in sorted_dims:
            for dist in sorted_dists:
                for algo in algos:
                    for p in parallelisms:
                        # 1. Warm-up runs
                        if warmups > 0:
                            print(f"\n[Benchmark] --- Performing {warmups} warm-up run(s) for Algo={algo}, Par={p}, Dist={dist}, Dims={d} ---")
                            for w in range(warmups):
                                print(f"[Benchmark] Warm-up {w + 1}/{warmups}...")
                                run_benchmark_run(algo, p, dist, d, records, rate)
                                time.sleep(2)
                        
                        # 2. Measured trial runs
                        print(f"\n[Benchmark] --- Performing {trials} measured trial(s) for Algo={algo}, Par={p}, Dist={dist}, Dims={d} ---")
                        for t in range(trials):
                            print(f"[Benchmark] Trial {t + 1}/{trials}...")
                            metrics = run_benchmark_run(algo, p, dist, d, records, rate)
                            if metrics:
                                metrics["Trial"] = t + 1
                                results.append(metrics)
                                # Save progress to CSV incrementally
                                file_exists = os.path.exists(args.output)
                                with open(args.output, "a", newline="", encoding="utf-8") as csvfile:
                                    writer = csv.DictWriter(csvfile, fieldnames=metrics.keys())
                                    if not file_exists:
                                        writer.writeheader()
                                    writer.writerow(metrics)
                            # Cool down between runs
                            time.sleep(2)
    except KeyboardInterrupt:
        print("\n[Benchmark] Benchmark interrupted by user.")
    finally:
        # Clean up any leftover jobs
        cancel_all_jobs()

    # Print final summary tables
    if results:
        print("\n" + "="*60)
        print("                  ALL TRIAL RUNS SUMMARY")
        print("="*60)
        headers = ["Algo", "Par", "Dist", "Dims", "Trial", "Rate", "SkySize", "Optimality", "Local(ms)", "Global(ms)", "Total(ms)", "Latency(ms)"]
        print("| " + " | ".join(headers) + " |")
        print("|" + "|".join(["---" for _ in headers]) + "|")
        for r in results:
            row = [
                r["Algo"],
                str(r["Parallelism"]),
                r["Distribution"],
                str(r["Dimensions"]),
                f"T{r['Trial']}",
                str(r["RateLimit"]),
                str(r["SkylineSize"]),
                str(r["Optimality"]),
                str(r["LocalTimeMs"]),
                str(r["GlobalTimeMs"]),
                str(r["TotalTimeMs"]),
                str(r["LatencyMs"])
            ]
            print("| " + " | ".join(row) + " |")
        print("="*60)

        # Calculate and print Averages Table
        from collections import defaultdict
        grouped = defaultdict(list)
        for r in results:
            key = (r["Algo"], r["Parallelism"], r["Distribution"], r["Dimensions"], r["RateLimit"])
            grouped[key].append(r)
            
        print("\n" + "="*60)
        print("                  AGGREGATED CONFIGURATION AVERAGES")
        print("="*60)
        avg_headers = ["Algo", "Par", "Dist", "Dims", "Rate", "Avg SkySize", "Avg Local(ms)", "Avg Global(ms)", "Avg Total(ms)", "Avg Latency(ms)"]
        print("| " + " | ".join(avg_headers) + " |")
        print("|" + "|".join(["---" for _ in avg_headers]) + "|")
        for key, runs in sorted(grouped.items()):
            algo, p, dist, d, rate_lim = key
            avg_sky = int(sum(r["SkylineSize"] for r in runs) / len(runs))
            avg_local = round(sum(r["LocalTimeMs"] for r in runs) / len(runs), 1)
            avg_global = round(sum(r["GlobalTimeMs"] for r in runs) / len(runs), 1)
            avg_total = round(sum(r["TotalTimeMs"] for r in runs) / len(runs), 1)
            avg_latency = round(sum(r["LatencyMs"] for r in runs) / len(runs), 1)
            
            row = [
                algo,
                str(p),
                dist,
                str(d),
                str(rate_lim),
                str(avg_sky),
                str(avg_local),
                str(avg_global),
                str(avg_total),
                str(avg_latency)
            ]
            print("| " + " | ".join(row) + " |")
        print("="*60)
        print(f"Results saved to: {os.path.abspath(args.output)}")
    else:
        print("\n[Benchmark] No results captured.")

if __name__ == "__main__":
    main()
