## Docker Infrastructure

The system runs on a 3-container setup defined in `docker-compose.yml`.

* **Kafka (`kafka`)**:
    * Runs in **KRaft mode** (no ZooKeeper required).
    * Listens on `localhost:9092`.
    * **Key Config**: `KAFKA_MESSAGE_MAX_BYTES` is set to **10MB** to allow large Skyline result payloads (default is 1MB).

* **Flink Cluster**:
    * **JobManager (`flink-jobmanager`)**: Coordinates execution. The Web Dashboard is accessible at `http://localhost:8081`.
    * **TaskManager (`flink-taskmanager`)**: The worker node. Configured with **4 Task Slots** to enable parallel processing out of the box.
