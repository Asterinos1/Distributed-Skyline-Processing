from confluent_kafka import Producer
from confluent_kafka.serialization import SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroSerializer
from sys import argv
import os
import time

"""
Query Trigger Publisher (Avro Version).

This script acts as the Control Signal Generator for the Distributed Skyline Flink Job.
It publishes a "Query Trigger" message to a Kafka topic, which instructs the Flink
workers to calculate and emit their local skylines, using Avro serialization.
"""

def load_schema(schema_name):
    script_dir = os.path.dirname(os.path.abspath(__file__))
    schema_path = os.path.join(script_dir, "..", "..", "src", "main", "avro", schema_name)
    with open(schema_path, "r") as f:
        return f.read()

def send_query_trigger():
    """
    Constructs and sends a single Query Trigger message to Kafka.
    """
    # Parse CLI Arguments with defaults
    topic_name = argv[1] if len(argv) > 1 else "queries"
    algo_str = argv[2] if len(argv) > 2 else "mr-dim"
    trigger_interval = int(argv[3]) if len(argv) > 3 else 60  # in seconds

    # --- Algorithm Mapping ---
    algo_map = {
        "mr-dim": 1,
        "mr-grid": 2,
        "mr-angle": 3
    }

    # Default to 1 (mr-dim) if unknown string provided
    skyline_algorithm = algo_map.get(algo_str.lower(), 1)

    # --- Schema Registry & Serializer Setup ---
    schema_registry_client = SchemaRegistryClient({'url': 'http://localhost:8082'})
    schema_str = load_schema("query_trigger.avsc")
    avro_serializer = AvroSerializer(schema_registry_client, schema_str)

    # --- Kafka Producer Initialization ---
    prod = Producer({'bootstrap.servers': 'localhost:9092'})
    
    print(f"Starting query trigger stream for {skyline_algorithm} ({algo_str}) in Avro format every {trigger_interval} seconds...")

    try:
        # --- Construct and Send Trigger ---
        # We pass 0 as requiredCount for immediate trigger on current buffer data.
        query_record = {
            "queryId": str(skyline_algorithm),
            "requiredCount": 0,
            "timestamp": int(time.time() * 1000)
        }
        
        serialized_query = avro_serializer(query_record, SerializationContext(topic_name, MessageField.VALUE))
        prod.produce(topic_name, value=serialized_query)
        
        # Force the buffer to send immediately
        prod.flush()
        print(f"[{time.strftime('%H:%M:%S')}] Trigger sent: {query_record}")

        # Keep process alive for the duration of the interval
        time.sleep(trigger_interval)
        
    except KeyboardInterrupt:
        print("Stopping query trigger.")
    except Exception as e:
        print(f"An error occurred: {e}")
    finally:
        # Resource Cleanup
        prod.flush()

def main():
    send_query_trigger()

if __name__ == '__main__':
    main()
