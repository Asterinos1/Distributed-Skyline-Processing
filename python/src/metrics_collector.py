import csv
import sys
import os
import json
from common import load_schema, BOOTSTRAP_SERVERS, SCHEMA_REGISTRY_URL
from confluent_kafka import Consumer
from confluent_kafka.serialization import SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroDeserializer

"""
Skyline Metrics Collector (Avro Version).

This script acts as the final sink for the Flink Skyline Experiment. It listens to the 
designated output Kafka topic where the Flink job publishes its results in Avro format. 
It deserializes the Avro payload, parses these metrics, and persists them into a 
structured CSV file for easier analysis and plotting later.
"""

# Configuration Constants
TOPIC = "output-skyline"



def collect_metrics(output_filename):
    # Check if file exists to decide if we need to write headers
    file_exists = os.path.isfile(output_filename)
    
    # Initialize Schema Registry and Deserializer
    schema_registry_client = SchemaRegistryClient({'url': SCHEMA_REGISTRY_URL})
    schema_str = load_schema("skyline_result.avsc")
    avro_deserializer = AvroDeserializer(schema_registry_client, schema_str)

    # Connect to Kafka Consumer
    # We use 'latest' offset reset to ensure we only capture results generated 
    # after this collector has started, avoiding processing stale data from previous runs.
    print(f"--- Listening on topic '{TOPIC}' ---")
    consumer_conf = {
        'bootstrap.servers': BOOTSTRAP_SERVERS,
        'group.id': 'metrics-collector-group',
        'auto.offset.reset': 'latest'
    }
    consumer = Consumer(consumer_conf)
    consumer.subscribe([TOPIC])

    # Open CSV Output File
    # The file is opened in append mode ('a') to preserve existing data.
    # newline='' is used to prevent blank lines between rows on Windows platforms.
    with open(output_filename, mode='a', newline='') as file:
        writer = csv.writer(file)
        
        # Define Schema columns
        headers = [
            "QueryID", 
            "Records", 
            "SkylineSize", 
            "Optimality", 
            "IngestTime(ms)", 
            "LocalTime(ms)", 
            "GlobalTime(ms)", 
            "TotalTime(ms)", 
            "Latency(ms)",
            "SkylinePoints" # Stores the raw JSON array of points [[x,y],...]
        ]
        
        # Initialize Headers
        # Only write the header row if we are creating a fresh file.
        if not file_exists:
            writer.writerow(headers)
            print(f"Created '{output_filename}' with headers.")
        else:
            print(f"Appending to existing '{output_filename}'.")

        print("Waiting for Flink results... (Press Ctrl+C to stop)")

        try:
            # Main Event Loop
            while True:
                msg = consumer.poll(1.0)
                if msg is None:
                    continue
                if msg.error():
                    print(f"Consumer error: {msg.error()}")
                    continue

                # Deserialize Avro message
                data = avro_deserializer(msg.value(), SerializationContext(msg.topic(), MessageField.VALUE))
                if data is None:
                    continue

                # Extract Metrics from Avro record dict
                q_id = data.get("queryId", "N/A")
                records = data.get("recordCount", 0)
                size = data.get("skylineSize", 0)
                optimality = data.get("optimality", 0.0)
                
                # Timing Metrics Extraction
                t_ingest = data.get("ingestionTimeMs", 0)
                t_local = data.get("localProcessingTimeMs", 0)
                t_global = data.get("globalProcessingTimeMs", 0)
                t_total = data.get("totalProcessingTimeMs", 0)
                t_latency = data.get("latencyMs", 0)
                
                # Raw Points Extraction
                raw_points = data.get("pointsJson", "[]")

                # Console Feedback
                # Provides real-time feedback to the user that a query has finished.
                print(f"[Query {q_id}] Records: {records} | Size: {size} | TotalTime: {t_total}ms")

                # Persist to CSV
                writer.writerow([
                    q_id, records, size, optimality, 
                    t_ingest, t_local, t_global, t_total, t_latency, 
                    raw_points
                ])
                
                # Flush buffer to ensure data is written to disk immediately
                file.flush()

        except KeyboardInterrupt:
            print("\nStopping collector...")
        finally:
            consumer.close()
            print("Collector closed.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python metrics_collector.py <filename.csv>")
        sys.exit(1)
    
    filename = sys.argv[1]
    collect_metrics(filename)
