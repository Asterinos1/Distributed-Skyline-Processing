import pandas as pd
import matplotlib.pyplot as plt

"""
Performance Visualization Script.

This script generates a comparative performance analysis of three distributed Skyline algorithms
(MR-Angle, MR-Dim, MR-Grid) across different dimensionalities (2D, 3D, and 4D).

It operates by reading a set of CSV output files—produced by the Flink job—and rendering
their performance metrics onto three side-by-side line charts. The script handles data 
transformation automatically, converting raw record counts into millions and processing 
timestamps from milliseconds into seconds for better readability.

The final output is a single image file ('performance_plots.png') containing the three 
subplots, which is suitable for inclusion in academic papers or technical reports.
"""

# 1. SETUP: File Mapping
# We define dictionaries to map the specific algorithm names to their corresponding 
# CSV result files for each dimension. This allows the script to iterate through 
# the data in a structured manner without hardcoding file paths inside the loop.

# Plot 1: 2 Dimensions
files_2d = {
    'MR-Angle': 'mrAngle_2dims.csv',
    'MR-Dim': 'mrDim_2dims.csv',
    'MR-Grid': 'mrGrid_2dims.csv'
}

# Plot 2: 3 Dimensions
files_3d = {
    'MR-Angle': 'mrAngle_3dims.csv',
    'MR-Dim': 'mrDim_3dims.csv',
    'MR-Grid': 'mrGrid_3dims.csv'
}

# Plot 3: 4 Dimensions
files_4d = {
    'MR-Angle': 'mrAngle_4dims_50000.csv',
    'MR-Dim': 'mrDim_4dims_50000.csv',
    'MR-Grid': 'mrGrid_4dims_50000.csv'
}


# Color and Marker styling for consistency across subplots
markers = {
    'MR-Angle': 'o',
    'MR-Dim': 's',
    'MR-Grid': '^'
}

colors = {
    'MR-Angle': 'blue',
    'MR-Dim': 'green',
    'MR-Grid': 'orange'
}

# 3. GENERATE PLOTS

def plot_performance_by_dimension():
    # Setup Figure with 3 horizontal Subplots
    fig, axes = plt.subplots(1, 3, figsize=(18, 5))
    titles = ['Performance in 2 Dimensions', 'Performance in 3 Dimensions', 'Performance in 4 Dimensions']
    file_groups = [files_2d, files_3d, files_4d]

    for i, (ax, files) in enumerate(zip(axes, file_groups)):
        for algo, filepath in files.items():
            try:
                # Read the CSV Data
                df = pd.read_csv(filepath)
                
                # Data Transformation
                x = df['Records'] / 1_000_000 
                y = df['TotalTime(ms)'] / 1000 
                
                # Plot the line for the current algorithm
                ax.plot(x, y, marker=markers[algo], label=algo, color=colors[algo])
            except FileNotFoundError:
                print(f"Warning: File {filepath} not found. Skipping.")

        # Chart Styling
        ax.set_title(titles[i])
        ax.set_xlabel('Records (Millions)')
        ax.set_ylabel('Total Time (Seconds)')
        ax.grid(True, alpha=0.3)
        ax.legend()

    # Layout Adjustment and Saving
    plt.tight_layout()
    plt.savefig('performance_plots.png')
    print("Saved performance_plots.png")
    plt.show()

if __name__ == '__main__':
    plot_performance_by_dimension()
