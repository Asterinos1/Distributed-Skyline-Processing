package org.main;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTupleTest {

    @Test
    void testDominates() {
        ServiceTuple t1 = new ServiceTuple("1", new double[]{10.0, 10.0});
        ServiceTuple t2 = new ServiceTuple("2", new double[]{20.0, 20.0});
        ServiceTuple t3 = new ServiceTuple("3", new double[]{10.0, 10.0});
        ServiceTuple t4 = new ServiceTuple("4", new double[]{5.0, 25.0});

        assertTrue(t1.dominates(t2));
        assertFalse(t2.dominates(t1));
        assertFalse(t1.dominates(t3)); // Equal, does not dominate
        assertFalse(t1.dominates(t4)); // Better in one, worse in another
    }

    @Test
    void testFromStringValid() {
        ServiceTuple t = ServiceTuple.fromString("101,25.5,0.99");
        assertNotNull(t);
        assertEquals("101", t.id);
        assertEquals(25.5, t.values[0]);
        assertEquals(0.99, t.values[1]);
    }

    @Test
    void testFromStringInvalid() {
        ServiceTuple t = ServiceTuple.fromString("101");
        assertNull(t);
        ServiceTuple t2 = ServiceTuple.fromString("bad,data,string");
        assertNull(t2);
    }
}
