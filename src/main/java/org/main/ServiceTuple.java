package org.main;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Data Model for a Multi-Dimensional Service Tuple.
 *
 * Represents a single data point in the Skyline Query.
 * It encapsulates a unique identifier and a vector of numerical values (dimensions)
 * representing various service attributes (e.g., Latency, Cost, Reliability).
 *
 * Implements Serializable to ensure safe transport across Flink network partitions.
 */
public class ServiceTuple implements Serializable {

    /**
     * Unique identifier for the record (e.g., "1", "1042").
     * Used for tracking and synchronization barriers.
     */
    public String id;

    /**
     * The multi-dimensional attributes of the service.
     * Example: [Latency, Price] -> [120.5, 50.0]
     */
    public double[] values;

    /**
     * Metadata field to track the source partition of this tuple.
     * This is populated during the Local Phase and used in the Global Phase
     * to calculate the "Optimality" metric (determining which partitions contribute most to the final result).
     * Default: -1 (Unassigned).
     */
    public int originPartition = -1;

    /**
     * Timestamp of when the tuple was generated/ingested.
     * Crucial for Event Time and Watermark alignment in Phase 4.
     */
    public long timestamp = 0L;

    /**
     * Default constructor for serialization/deserialization frameworks (e.g., Flink POJO serialization).
     */
    public ServiceTuple() {}

    /**
     * Parameterized Constructor.
     *
     * @param id     Unique record identifier.
     * @param values Double array representing the dimensions.
     */
    public ServiceTuple(String id, double[] values) {
        this(id, values, 0L);
    }

    /**
     * Full Parameterized Constructor.
     *
     * @param id        Unique record identifier.
     * @param values    Double array representing the dimensions.
     * @param timestamp Generation timestamp.
     */
    public ServiceTuple(String id, double[] values, long timestamp) {
        this.id = id;
        this.values = values;
        this.timestamp = timestamp;
    }

    /**
     * Map from generated Avro record to internal ServiceTuple POJO.
     */
    public static ServiceTuple fromAvro(org.main.avro.ServiceTupleAvro avro) {
        double[] vals = new double[avro.getValues().size()];
        for (int i = 0; i < vals.length; i++) {
            vals[i] = avro.getValues().get(i);
        }
        ServiceTuple t = new ServiceTuple(avro.getId().toString(), vals, avro.getTimestamp());
        t.originPartition = avro.getOriginPartition();
        return t;
    }

    /**
     * Map from internal ServiceTuple POJO to generated Avro record.
     */
    public org.main.avro.ServiceTupleAvro toAvro() {
        java.util.List<Double> valsList = new java.util.ArrayList<>(values.length);
        for (double val : values) {
            valsList.add(val);
        }
        return new org.main.avro.ServiceTupleAvro(id, valsList, originPartition, timestamp);
    }

    /**
     * Dominance Check (Minimization Strategy).
     *
     * Determines if 'this' tuple dominates the 'other' tuple.
     * Standard Skyline definition: A dominates B if A is no worse than B in all dimensions
     * and strictly better than B in at least one dimension.
     *
     * Logic Assumption: Lower values are better (Minimization).
     * - If this.val > other.val -> 'this' is worse, so it cannot dominate.
     * - If this.val < other.val -> 'this' is strictly better in this dimension.
     *
     * @param other The tuple to compare against.
     * @return true if 'this' dominates 'other', false otherwise.
     */
    public boolean dominates(ServiceTuple other) {
        boolean betterInAtLeastOne = false;
        for (int i = 0; i < values.length; i++) {
            // Minimization: If we are larger (worse) in any dimension, we cannot dominate.
            if (this.values[i] > other.values[i]) return false;

            // If we are strictly smaller (better), mark it.
            if (this.values[i] < other.values[i]) betterInAtLeastOne = true;
        }
        return betterInAtLeastOne;
    }

    /**
     * Factory Method: CSV Parser.
     *
     * Converts a raw CSV string from Kafka into a ServiceTuple object.
     * Expected Format: "ID,Value1,Value2,Value3..."
     * Example: "101,25.5,0.99" -> ServiceTuple(id="101", values=[25.5, 0.99])
     *
     * @param s The raw CSV string.
     * @return A valid ServiceTuple instance, or null if the string is malformed or empty.
     */
    public static ServiceTuple fromString(String s) {
        try {
            String[] p = s.split(",");
            // Validation: Must have at least an ID and one Dimension
            if (p.length < 2) return null;

            double[] vals = new double[p.length - 1];
            for (int i = 1; i < p.length; i++) {
                vals[i - 1] = Double.parseDouble(p[i]);
            }
            return new ServiceTuple(p[0], vals);
        } catch (Exception e) {
            // Return null to allow the calling filter to discard malformed records safely
            return null;
        }
    }

    /**
     * String Representation.
     *
     * @return Format: "ID:[id] [val1, val2, ...]"
     */
    @Override
    public String toString() {
        return "ID:" + id + " " + Arrays.toString(values);
    }
}
