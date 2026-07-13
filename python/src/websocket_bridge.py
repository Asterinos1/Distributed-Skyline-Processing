import json
import asyncio
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
import uvicorn
from confluent_kafka import Consumer
from confluent_kafka.serialization import SerializationContext, MessageField
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroDeserializer
import os

# Configure Logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("websocket_bridge")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Start the Kafka consumer background task on startup
    task = asyncio.create_task(kafka_consumer_loop())
    yield
    # Cancel the task gracefully on shutdown
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass

app = FastAPI(title="Skyline Real-time WebSocket Bridge", lifespan=lifespan)


# Enable CORS for frontend dashboard
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Keep track of active websocket connections
active_connections = set()

def load_schema(schema_name):
    script_dir = os.path.dirname(os.path.abspath(__file__))
    schema_path = os.path.join(script_dir, "..", "..", "src", "main", "avro", schema_name)
    with open(schema_path, "r") as f:
        return f.read()

async def kafka_consumer_loop():
    """
    Background loop that consumes metrics from the output-skyline Kafka topic
    and broadcasts them to all connected WebSocket clients.
    """
    await asyncio.sleep(2)  # Wait for startup
    logger.info("Starting Kafka consumer background task...")

    try:
        # Initialize Schema Registry and Deserializer
        schema_registry_client = SchemaRegistryClient({'url': 'http://localhost:8082'})
        schema_str = load_schema("skyline_result.avsc")
        avro_deserializer = AvroDeserializer(schema_registry_client, schema_str)

        # Kafka Consumer Config
        # 'earliest' ensures the bridge replays any results already in the topic
        # when it starts, so the dashboard populates immediately even if the bridge
        # was started after a query result was already produced.
        consumer_conf = {
            'bootstrap.servers': 'localhost:9092',
            'group.id': 'websocket-bridge-group',
            'auto.offset.reset': 'earliest'
        }
        consumer = Consumer(consumer_conf)
        consumer.subscribe(["output-skyline"])

        while True:
            # Poll for new messages (non-blocking in thread pool to prevent event loop lag)
            msg = await asyncio.to_thread(consumer.poll, 0.5)
            if msg is None:
                await asyncio.sleep(0.1)
                continue
            if msg.error():
                logger.error(f"Kafka consumer error: {msg.error()}")
                continue

            try:
                # Deserialise Avro payload
                data = avro_deserializer(msg.value(), SerializationContext(msg.topic(), MessageField.VALUE))
                if data is None:
                    continue

                # Prepare payload for Web client
                payload = {
                    "queryId": data.get("queryId", "N/A"),
                    "recordCount": data.get("recordCount", 0),
                    "skylineSize": data.get("skylineSize", 0),
                    "optimality": data.get("optimality", 0.0),
                    "ingestionTimeMs": data.get("ingestionTimeMs", 0),
                    "localProcessingTimeMs": data.get("localProcessingTimeMs", 0),
                    "globalProcessingTimeMs": data.get("globalProcessingTimeMs", 0),
                    "totalProcessingTimeMs": data.get("totalProcessingTimeMs", 0),
                    "latencyMs": data.get("latencyMs", 0),
                    "points": json.loads(data.get("pointsJson", "[]"))
                }

                logger.info(f"Broadcast: Query {payload['queryId']} | Size: {payload['skylineSize']}")

                # Broadcast to all connected clients
                if active_connections:
                    message_str = json.dumps(payload)
                    disconnected = []
                    for connection in active_connections:
                        try:
                            await connection.send_text(message_str)
                        except Exception:
                            disconnected.append(connection)
                    for conn in disconnected:
                        active_connections.remove(conn)

            except Exception as ex:
                logger.error(f"Error processing Kafka message: {ex}")

            await asyncio.sleep(0.01)

    except Exception as e:
        logger.error(f"Fatal error in Kafka consumer task: {e}")
    finally:
        try:
            consumer.close()
        except NameError:
            pass
        logger.info("Kafka consumer task stopped.")



@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    active_connections.add(websocket)
    logger.info(f"New client connected. Active connections: {len(active_connections)}")
    try:
        # Keep the connection alive with a periodic ping.
        # We do NOT block on receive_text() — that would stall if the browser
        # never sends a message, preventing proper connection cleanup.
        while True:
            await asyncio.sleep(30)
            await websocket.send_text('{"type":"ping"}')
    except WebSocketDisconnect:
        active_connections.discard(websocket)
        logger.info(f"Client disconnected. Active connections: {len(active_connections)}")
    except ConnectionResetError:
        # WinError 10054: browser tab closed or refreshed — normal on Windows, not an error.
        active_connections.discard(websocket)
        logger.info(f"Client reset connection. Active connections: {len(active_connections)}")
    except Exception as e:
        logger.error(f"WebSocket error: {e}")
        active_connections.discard(websocket)

if __name__ == "__main__":
    import sys
    import warnings
    # Suppress asyncio loop policy deprecation warnings in Python 3.14+
    warnings.filterwarnings("ignore", category=DeprecationWarning)

    # On Windows, the default ProactorEventLoop raises noisy ConnectionResetError
    # tracebacks (WinError 10054) when a browser tab closes a WebSocket.
    # SelectorEventLoop handles this gracefully.
    if sys.platform == "win32":
        try:
            asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
        except (AttributeError, DeprecationWarning):
            pass # Gracefully handle removal or deprecation errors
    uvicorn.run(app, host="0.0.0.0", port=8000)
