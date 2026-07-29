package org.main;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocalSkylineResultTest {

    @Test
    void testConstructorAndGetters() {
        List<ServiceTuple> points = new ArrayList<>();
        points.add(new ServiceTuple("1", new double[]{1.0, 2.0}));
        
        LocalSkylineResult result = new LocalSkylineResult(1, "query1", 1000L, 2000L, points, 50L);
        
        assertEquals(1, result.partitionId);
        assertEquals("query1", result.queryPayload);
        assertEquals(1000L, result.triggerTimestamp);
        assertEquals(2000L, result.partitionStartTime);
        assertEquals(1, result.skylinePoints.size());
        assertEquals(50L, result.cpuTimeMillis);
    }
}
