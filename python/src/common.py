"""Shared configuration and utilities for Skyline Processing Python tools."""
import os

BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SCHEMA_REGISTRY_URL = os.environ.get("SCHEMA_REGISTRY_URL", "http://localhost:8082")

def load_schema(schema_name):
    """Load an Avro schema file from the project's avro directory."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    schema_path = os.path.join(script_dir, "..", "..", "src", "main", "avro", schema_name)
    with open(schema_path, "r") as f:
        return f.read()
