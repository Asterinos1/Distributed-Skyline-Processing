# Distributed Skyline Query Processing (Docker Version)

An Apache Flink + Kafka project written in Java for **distributed stream processing** of Skyline Queries, developed as part of the *Special Topics in Databases - COMP 622* course at the **Technical University of Crete**.

This project implements three partitioning strategies (**MR-Dim**, **MR-Grid**, and **MR-Angle**) using the **Flink DataStream API** to perform real-time dominance analysis on high-volume data streams ingested via Apache Kafka.

Authors: 
* [Asterinos1](https://github.com/Asterinos1)
* [eNiaro](https://github.com/eNiaro)

## Prerequisites

Before you begin, ensure you have the following installed on your system:
* Docker
* Docker Compose
* Python 3.x
* Java 11 and Maven (for building the Flink job)

You will also need to install the required Python dependencies for the data generators and visualization scripts:

    pip install kafka-python faker pandas matplotlib numpy

## Repository Structure Overview

* `docker-setup/`: Contains the `docker-compose.yml` to spin up Kafka and Flink.
* `java/`: Contains the Flink streaming application source code.
* `python/`: Contains data generators, query triggers, metrics collectors, and visualization scripts.

---

## Step 1: Start the Infrastructure (Docker)

The project relies on Kafka (running in KRaft mode) and Flink (JobManager and TaskManager). These are containerized for easy deployment.

1. Navigate to the Docker setup directory:
   
       cd docker-setup

2. Start the services in detached mode:
   
       docker-compose up -d

3. Verify the containers are running:
   
       docker ps

   You should see three containers running: `kafka` (Port 9092), `flink-jobmanager` (Port 8081), and `flink-taskmanager`.

4. Access the Flink Web UI by navigating to `http://localhost:8081` in your web browser.

---
## Step 2: Build and Submit the Flink Job

There are two ways to build and execute the job: directly through an IDE or by compiling it into a JAR file and submitting it to the Flink cluster.

### Option A: Using IntelliJ IDEA
If you are developing locally using IntelliJ IDEA, you can simply run the application directly by pressing the green **Execute/Run** button on your main entry class.

To run the job with specific parameters:
1. Click on the drop-down menu next to the run button at the top of the IDE and select **Edit Configurations...**
2. Select your application's Run/Debug configuration from the left panel.
3. In the **Program arguments** field, enter your parameters separated by spaces (e.g., `--algorithm mr-dim --parallelism 4`).
4. Click **Apply** and then **OK**, and run the job.

### Option B: Compiling and Submitting via Web UI
To manually compile the project and submit it to your running Docker cluster, follow these steps::

1. Navigate to the Java source directory and package the project:
   
       cd ../java
       mvn clean package
2. Locate the compiled JAR file in the `java/target/` directory.
3. Open the Flink Web UI at `http://localhost:8081`.
4. Click on **Submit New Job** in the left-hand menu.
5. Click **Add New** and upload your compiled JAR file.
6. Click on the uploaded JAR to expand the submission menu.
7. Click Submit.

*(Optional) In the Program Arguments text box, enter any required arguments (e.g., --algorithm mr-grid). If your entry class is not defined in the JAR's manifest, specify it in the Entry Class box.*


---

## Step 3: Start the Metrics Collector

Before sending data, start the metrics collector so it can listen to the Flink output topic and record the performance data to a CSV file.

1. Open a new terminal and navigate to the project root.
2. Run the metrics collector script:

       python python/metrics_collector.py results.csv

   This will listen on the `output-skyline` Kafka topic and write or append results to `results.csv`. Leave this terminal running.

---

## Step 4: Run the Data Generator

With the infrastructure running and the collector listening, you can now push synthetic multi-dimensional data into Kafka.

The `unified_producer.py` script streams data and automatically sends query triggers to Flink every 1,000,000 records.

1. Open a new terminal and navigate to the project root.
2. Execute the producer. The syntax is: `python python/unified_producer.py <data_topic> <distribution> <dimensions> <min_val> <max_val> <query_topic>`

   Example (3D Anti-Correlated data, domain 0-10000):
   
       python python/unified_producer.py input-tuples anti_correlated 3 0 10000 queries

   *Supported distributions:* `uniform`, `correlated`, `anti_correlated`.

*(Optional)* If you want to trigger a query manually without waiting for the automated threshold, you can use the `query_trigger.py` script:

    python python/query_trigger.py queries mr-angle 60

---

## Step 5: Visualize the Results

Once your experiment has generated sufficient data in the `results.csv` file, you can use the provided graphing scripts to analyze the performance.

* **Skyline Points 2D Visualization:**
    View the actual Pareto frontier for a specific run.
    
        python python/graph_skyline_points_2d.py results.csv -1

* **Performance Dashboard:**
    Compare multiple algorithms across ingestion time, scalability, and optimality. You must provide labels and their corresponding CSV files.
    
        python python/graph_ingestion_parallelism.py MR-Angle=results_angle.csv MR-Grid=results_grid.csv

* **Paper Figures Generation:**
    Replicates academic figures (Processing Time vs Dimensionality).
    
        python python/graph_paper_figures.py

* **Performance By Dimension:**
    Visualizes metrics across 2D, 3D, and 4D setups (requires specific CSV files as defined in the script).
    
        python python/graph_performance_by_dimension.py

---

## Teardown

When you are finished with your experiments, you can stop and remove the Docker containers.

1. Navigate to the Docker setup directory:
   
       cd docker-setup

2. Spin down the services:
   
       docker-compose down
