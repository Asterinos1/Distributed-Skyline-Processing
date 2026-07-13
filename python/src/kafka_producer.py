from confluent_kafka import Producer
from confluent_kafka.serialization import SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroSerializer
from faker import Faker
from enum import Enum
from sys import argv
import os
import time

"""
Synthetic Data Stream Generator (Avro Version).

This script serves as a lightweight data producer for the Flink Skyline Experiment.
It continuously generates synthetic multi-dimensional data points using three distinct 
statistical distributions (Uniform, Correlated, Anti-Correlated) and pushes them 
to a specified Kafka topic using Avro serialization.
"""

class GenMethod(Enum):
    """
    Enumeration for supported data generation strategies.
    This ensures type safety when validating the distribution method provided via
    command-line arguments.
    """
    UNIFORM = "uniform"
    CORRELATED = "correlated"
    ANTI_CORRELATED = "anti_correlated"

    @classmethod
    def from_str(cls, label):
        """
        Safe utility to convert a string label into the corresponding Enum constant.
        It normalizes the input to lowercase to handle case-insensitive arguments.
        """
        return cls(label.lower())

def load_schema(schema_name):
    script_dir = os.path.dirname(os.path.abspath(__file__))
    schema_path = os.path.join(script_dir, "..", "..", "src", "main", "avro", schema_name)
    with open(schema_path, "r") as f:
        return f.read()

"""
Generates a data point with independent random values for each dimension.
"""
def generate_uniform_data(faker, dimensions, d_min, d_max):
    return [faker.random_int(min=d_min, max=d_max) for _ in range(dimensions)]

"""
Generates a data point where dimensions are positively correlated.
"""
def generate_correlated_data(faker, dimensions, d_min, d_max):
    base = faker.random_int(min=d_min, max=d_max)
    # Define a small noise window (10% of domain) to maintain correlation
    offset = int((d_max - d_min) * 0.1)
    
    # Generate dimensions and clamp them to ensure they remain within [d_min, d_max]
    return [max(d_min, min(d_max, base + faker.random_int(min=-offset, max=offset))) for _ in range(dimensions)]

"""
Generates a data point where dimensions are negatively correlated.
"""
def generate_anti_correlated_data(faker, dimensions, d_min, d_max):
    rand_vals = [faker.random.random() for _ in range(dimensions)]
    
    # Calculate the target sum representing the center plane of the hypercube
    target_sum = (d_min + d_max) / 2.0 * dimensions
    
    # Determine the scaling factor required to project the random vector onto the plane
    current_sum = sum(rand_vals)
    scale = target_sum / current_sum if current_sum != 0 else 1
    
    # Apply scaling and clamp results to integer bounds
    return [max(d_min, min(d_max, int(v * scale))) for v in rand_vals]

"""
Main Execution Loop.
"""
def generate_data():
    faker = Faker()
    
    # CLI Argument Parsing with fallback defaults
    topic_name = argv[1] if len(argv) > 1 else "input-tuples"
    method_str = argv[2] if len(argv) > 2 else "uniform"
    dimensions = int(argv[3]) if len(argv) > 3 else 2
    d_min = int(argv[4]) if len(argv) > 4 else 0
    d_max = int(argv[5]) if len(argv) > 5 else 1000

    generation_method = GenMethod.from_str(method_str)
    
    # Initialize Schema Registry client and Avro Serializer
    schema_registry_client = SchemaRegistryClient({'url': 'http://localhost:8082'})
    schema_str = load_schema("service_tuple.avsc")
    avro_serializer = AvroSerializer(schema_registry_client, schema_str)

    # Initialize connection to local Kafka Broker
    prod = Producer({'bootstrap.servers': 'localhost:9092'})

    print(f"Starting {generation_method.value} stream in Avro format...")

    try:
        point_id = 0
        while True:
            # Delegate generation to the specific statistical helper function
            if generation_method == GenMethod.UNIFORM:
                data = generate_uniform_data(faker, dimensions, d_min, d_max)
            elif generation_method == GenMethod.CORRELATED:
                data = generate_correlated_data(faker, dimensions, d_min, d_max)
            else:
                data = generate_anti_correlated_data(faker, dimensions, d_min, d_max)

            # Construct Avro Record dict
            record = {
                "id": str(point_id),
                "values": [float(v) for v in data],
                "originPartition": -1,
                "timestamp": int(time.time() * 1000)
            }
            
            # Serialize
            serialized_value = avro_serializer(record, SerializationContext(topic_name, MessageField.VALUE))

            # Send serialized bytes to Kafka
            prod.produce(topic_name, value=serialized_value)

            # Periodic progress logging
            if point_id % 100000 == 0:
                print(f"Sent {point_id} records...")
                prod.flush()
            point_id += 1
            
    except KeyboardInterrupt:
        print("Stopping.")
    finally:
        # Resource Cleanup: Flush producer before exit
        prod.flush()

if __name__ == '__main__':
    generate_data()
