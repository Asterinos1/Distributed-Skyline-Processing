import pandas as pd
import matplotlib.pyplot as plt
import sys
import os

"""
Flink Skyline Performance Analysis Script.

This tool generates a comprehensive 2x2 dashboard visualization of the Flink job's performance.
It is designed to compare multiple algorithms (e.g., MR-Angle vs. MR-Dim) or configurations 
side-by-side.

The script produces four distinct subplots to evaluate different system characteristics:
- Ingestion Time: Tracks the raw speed of data uptake from Kafka.
- Total Processing Time: Measures the end-to-end latency (Scalability).
- Optimality Ratio: visualizes the efficiency of the pruning strategy over time.
- Time Breakdown: A bar chart showing the split between Local and Global processing time 
  for the final data batch.

This dashboard is useful for identifying bottlenecks (e.g., high global merge times) and 
verifying that the system scales linearly with data volume.
"""

"""
Generates the performance dashboard.

The function iterates through the provided file mapping (Label -> CSV Path). For each file, 
it loads the data, sorts it by record count to ensure monotonic lines, and plots the 
relevant metrics onto the subplot grid.

The 'Ingestion Time' and 'Total Processing Time' plots use line graphs to show trends 
as data volume increases. The 'Optimality' plot uses a dashed line to highlight stability 
or degradation in pruning power. Finally, the 'Time Breakdown' uses a stacked bar chart 
to visualize the contribution of local vs. global processing for the largest dataset size.

Inputs:
- file_map: A dictionary mapping a display label (e.g., "MR-Angle") to a CSV filepath.

Outputs:
- Saves a high-resolution image 'performance_analysis.png'.
- Displays the interactive plot window.
"""
def plot_performance(file_map):
    # Setup Figure with 2x2 Subplots
    fig, axes = plt.subplots(2, 2, figsize=(15, 10))
    fig.suptitle('Flink Skyline Performance Analysis', fontsize=16)

    # Flatten axes for easy iteration
    ax_ingest = axes[0, 0]
    ax_total = axes[0, 1]
    ax_optimality = axes[1, 0]
    ax_breakdown = axes[1, 1]

    for label, filepath in file_map.items():
        if not os.path.exists(filepath):
            print(f"Warning: File {filepath} not found. Skipping.")
            continue
            
        try:
            df = pd.read_csv(filepath)
            
            # Sort by Records to ensure clean lines
            df = df.sort_values(by="Records")
            
            # X-Axis: Millions of Records
            x = df["Records"] / 1_000_000

            # Ingestion Time (Objective 4)
            # Plotting raw ingestion time per query batch
            ax_ingest.plot(x, df["IngestTime(ms)"], marker='.', label=label)
            
            # Total Processing Time (Objective 2 & 5)
            # Convert to Seconds for readability
            ax_total.plot(x, df["TotalTime(ms)"] / 1000, marker='o', label=label)

            # Optimality Evolution (Objective 3)
            ax_optimality.plot(x, df["Optimality"], marker='x', linestyle='--', label=label)

            # Processing Breakdown (Summary)
            # We take the breakdown of the FINAL point in the stream to represent steady-state behavior
            last_row = df.iloc[-1]
            ax_breakdown.bar(label, last_row["LocalTime(ms)"], label='Local CPU' if label==list(file_map.keys())[0] else "", color='skyblue')
            ax_breakdown.bar(label, last_row["GlobalTime(ms)"], bottom=last_row["LocalTime(ms)"], label='Global Merge' if label==list(file_map.keys())[0] else "", color='orange')

        except Exception as e:
            print(f"Error processing {filepath}: {e}")

    # --- Formatting ---
    
    # Ingestion Plot
    ax_ingest.set_title('Ingestion Time vs Data Volume')
    ax_ingest.set_xlabel('Records (Millions)')
    ax_ingest.set_ylabel('Time (ms)')
    ax_ingest.legend()
    ax_ingest.grid(True, alpha=0.3)

    # Total Time Plot
    ax_total.set_title('Total Processing Time (Scalability)')
    ax_total.set_xlabel('Records (Millions)')
    ax_total.set_ylabel('Time (Seconds)')
    ax_total.legend()
    ax_total.grid(True, alpha=0.3)

    # Optimality Plot
    ax_optimality.set_title('Local Optimality Ratio')
    ax_optimality.set_xlabel('Records (Millions)')
    ax_optimality.set_ylabel('Optimality (0.0 - 1.0)')
    ax_optimality.set_ylim(0, 1.1)
    ax_optimality.legend()
    ax_optimality.grid(True, alpha=0.3)

    # Breakdown Plot
    ax_breakdown.set_title('Time Breakdown (Final Batch)')
    ax_breakdown.set_ylabel('Time (ms)')
    # Handles separate legend for bars manually if needed, or relies on unique labels
    
    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.savefig("performance_analysis.png")
    print("Graph saved as 'performance_analysis.png'")
    plt.show()

if __name__ == "__main__":
    # Usage: python graph_series.py Label1=file1.csv Label2=file2.csv
    if len(sys.argv) < 2:
        print("Usage: python graph_series.py <Label>=<File.csv> ...")
        print("Example: python graph_series.py MR-Angle=angle.csv MR-Grid=grid.csv")
        sys.exit(1)

    # Parse args into dictionary
    files = {}
    for arg in sys.argv[1:]:
        if "=" in arg:
            label, path = arg.split("=", 1)
            files[label] = path
    
    plot_performance(files)
