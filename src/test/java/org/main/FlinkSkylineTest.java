package org.main;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FlinkSkylineTest {

    @Test
    public void testServiceTupleDominance() {
        // Minimization strategy (lower is better)
        ServiceTuple t1 = new ServiceTuple("1", new double[]{10.0, 20.0});
        ServiceTuple t2 = new ServiceTuple("2", new double[]{15.0, 25.0});
        ServiceTuple t3 = new ServiceTuple("3", new double[]{5.0, 30.0});
        ServiceTuple t4 = new ServiceTuple("4", new double[]{10.0, 20.0}); // identical to t1

        // t1 dominates t2 (better in all dimensions)
        assertTrue(t1.dominates(t2));
        assertFalse(t2.dominates(t1));

        // t1 and t3 do not dominate each other (one is better in dim 0, other in dim 1)
        assertFalse(t1.dominates(t3));
        assertFalse(t3.dominates(t1));

        // Identical points do not dominate each other (must be strictly better in at least one)
        assertFalse(t1.dominates(t4));
        assertFalse(t4.dominates(t1));
    }

    @Test
    public void testKDTreeDominancePruning() {
        FlinkSkyline.KDTree tree = new FlinkSkyline.KDTree(2);
        
        List<ServiceTuple> skyline = new ArrayList<>();
        skyline.add(new ServiceTuple("1", new double[]{10.0, 20.0}));
        skyline.add(new ServiceTuple("2", new double[]{5.0, 35.0}));
        skyline.add(new ServiceTuple("3", new double[]{30.0, 5.0}));

        tree.rebuild(skyline);

        // Candidate t1: [15.0, 25.0] is dominated by [10.0, 20.0]
        ServiceTuple t1 = new ServiceTuple("c1", new double[]{15.0, 25.0});
        assertTrue(tree.isDominated(t1));

        // Candidate t2: [4.0, 40.0] is not dominated (better in first dim than all)
        ServiceTuple t2 = new ServiceTuple("c2", new double[]{4.0, 40.0});
        assertFalse(tree.isDominated(t2));

        // Candidate t3: [25.0, 4.0] is not dominated (better in second dim than all)
        ServiceTuple t3 = new ServiceTuple("c3", new double[]{25.0, 4.0});
        assertFalse(tree.isDominated(t3));
    }

    @Test
    public void testDimPartitioner() {
        FlinkSkyline.PartitioningLogic.DimPartitioner partitioner = 
                new FlinkSkyline.PartitioningLogic.DimPartitioner(4, 1000.0);

        ServiceTuple t1 = new ServiceTuple("1", new double[]{100.0, 500.0}); // slice 0
        ServiceTuple t2 = new ServiceTuple("2", new double[]{300.0, 500.0}); // slice 1
        ServiceTuple t3 = new ServiceTuple("3", new double[]{600.0, 500.0}); // slice 2
        ServiceTuple t4 = new ServiceTuple("4", new double[]{900.0, 500.0}); // slice 3

        assertEquals(0, partitioner.getKey(t1));
        assertEquals(1, partitioner.getKey(t2));
        assertEquals(2, partitioner.getKey(t3));
        assertEquals(3, partitioner.getKey(t4));
    }

    @Test
    public void testGridPartitioner() {
        FlinkSkyline.PartitioningLogic.GridPartitioner partitioner = 
                new FlinkSkyline.PartitioningLogic.GridPartitioner(4, 1000.0, 2);

        // Midpoint is 500.0 for both dimensions
        // Sector bitmask: bit 0 for dim 0, bit 1 for dim 1
        ServiceTuple t1 = new ServiceTuple("1", new double[]{200.0, 200.0}); // Bottom-Left: 00 -> 0
        ServiceTuple t2 = new ServiceTuple("2", new double[]{800.0, 200.0}); // Bottom-Right (x>=500): 01 -> 1
        ServiceTuple t3 = new ServiceTuple("3", new double[]{200.0, 800.0}); // Top-Left (y>=500): 10 -> 2
        ServiceTuple t4 = new ServiceTuple("4", new double[]{800.0, 800.0}); // Top-Right (x>=500, y>=500): 11 -> 3

        assertEquals(0, partitioner.getKey(t1));
        assertEquals(1, partitioner.getKey(t2));
        assertEquals(2, partitioner.getKey(t3));
        assertEquals(3, partitioner.getKey(t4));
    }

    @Test
    public void testAnglePartitioner() {
        FlinkSkyline.PartitioningLogic.AnglePartitioner partitioner = 
                new FlinkSkyline.PartitioningLogic.AnglePartitioner(4, 2);

        ServiceTuple t1 = new ServiceTuple("1", new double[]{10.0, 1.0}); // close to X axis (small angle)
        ServiceTuple t2 = new ServiceTuple("2", new double[]{1.0, 10.0}); // close to Y axis (large angle)

        Integer key1 = partitioner.getKey(t1);
        Integer key2 = partitioner.getKey(t2);

        assertNotNull(key1);
        assertNotNull(key2);
        assertTrue(key1 < key2, "Points closer to X-axis should have smaller partition keys than Y-axis");
    }


    @Test
    public void testLocalSkylineResultPOJO() {
        List<ServiceTuple> points = Arrays.asList(new ServiceTuple("1", new double[]{1.0, 2.0}));
        LocalSkylineResult result = new LocalSkylineResult(1, "q1|100", 1000L, 500L, points, 10L);
        
        assertEquals(1, result.partitionId);
        assertEquals("q1|100", result.queryPayload);
        assertEquals(1, result.skylinePoints.size());
    }
}
