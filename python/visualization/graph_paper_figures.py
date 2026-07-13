import matplotlib.pyplot as plt
import numpy as np

"""
Paper Figure Replication Script.

This script is designed to generate the final comparative figures for the academic paper
or technical report. It focuses on two key metrics:
- Processing Time vs. Dimensionality (Figure 5 replication)
- Local Optimality vs. Dimensionality (Figure 7 replication)

The script uses hardcoded dictionaries to store the final aggregated results from 
the experiments. This allows for easy manual entry of data points gathered from 
multiple Flink job runs without needing to parse dozens of raw CSV files dynamically.
"""

# ==========================================
# ENTER YOUR RESULTS HERE
# ==========================================
# Run your experiments and copy the "TotalTime(ms)" 
# for 100,000 records (Small) or 10M records (Large)

dimensions = [2, 3, 4]

# Objective: Processing Time (Seconds)
# Enter the total processing time derived from the Flink job results.
# Ensure consistency in the record count used (e.g., all data points for 1M records).
time_results = {
    "MR-Dim":   [19544, 27264, 716996],  # Times for Dim 2, 4, 8
    "MR-Grid":  [17593, 26601, 691882],
    "MR-Angle": [17282, 27015,  766937]
}

# Objective: Local Optimality (0.0 - 1.0)
# Enter the optimality ratio calculated by the Global Aggregator.
# A value of 1.0 means perfect local pruning (no false positives sent to reducer).
# A value near 0.0 means poor pruning (many dominated points sent to reducer).
optimality_results = {
    "MR-Dim":   [0.7379, 0.6742, 0.25],
    "MR-Grid":  [0.5415, 0.5906, 0.25],
    "MR-Angle": [0.7453, 0.6652, 0.25] 
}
# ==========================================

"""
Generates and saves the publication-ready figures.

This function creates two separate line charts using the data dictionaries defined above.

The first plot visualizes how the Processing Time scales as dimensionality increases.
It typically reveals the "Curse of Dimensionality," where higher dimensions lead to
exponentially longer processing times due to the difficulty of dominance checks.

The second plot visualizes the Local Optimality ratio. This metric assesses the efficiency
of the partitioning strategy. Effective partitioners (like MR-Angle on anti-correlated data)
maintain high optimality scores even in higher dimensions, whereas weaker strategies 
tend to degrade toward zero.

Outputs:
- figure_5_replication.png (Time Analysis)
- figure_7_replication.png (Optimality Analysis)
"""
def plot_paper_figures():
    # --- Figure 5 Replication (Time vs Dim) ---
    plt.figure(figsize=(10, 5))
    for algo, times in time_results.items():
        plt.plot(dimensions, times, marker='o', label=algo)
    
    plt.title("Processing Time vs Dimensionality (Cardinality 1 Million)")
    plt.xlabel("Dimensions")
    plt.ylabel("Processing Time (ms)")
    plt.xticks(dimensions)
    plt.legend()
    plt.grid(True)
    plt.savefig("figure_5_replication.png")
    print("Saved figure_5_replication.png")

    # --- Figure 7 Replication (Optimality vs Dim) ---
    plt.figure(figsize=(10, 5))
    for algo, opts in optimality_results.items():
        plt.plot(dimensions, opts, marker='s', linestyle='--', label=algo)

    plt.title("Local Skyline Optimality vs Dimensionality (Cardinality 1 Million)")
    plt.xlabel("Dimensions")
    plt.ylabel("Optimality Ratio")
    plt.xticks(dimensions)
    plt.legend()
    plt.grid(True)
    plt.savefig("figure_7_replication.png")
    print("Saved figure_7_replication.png")
    
    plt.show()

if __name__ == "__main__":
    plot_paper_figures()

