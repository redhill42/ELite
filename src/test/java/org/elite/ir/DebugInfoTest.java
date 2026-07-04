package org.elite.ir;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DebugInfoTest {
    @Test
    void lineTableSearch() {
        DebugInfo di = new DebugInfo("",
            new int[]{10, 5, 20, 7, 30, 9});
        assertEquals(5, di.lineForPC(0));
        assertEquals(5, di.lineForPC(5));
        assertEquals(5, di.lineForPC(10));
        assertEquals(7, di.lineForPC(15));
        assertEquals(7, di.lineForPC(19));
        assertEquals(7, di.lineForPC(20));
        assertEquals(9, di.lineForPC(21));
        assertEquals(9, di.lineForPC(25));
        assertEquals(9, di.lineForPC(29));
        assertEquals(9, di.lineForPC(30));
        assertEquals(0, di.lineForPC(31));
        assertEquals(0, di.lineForPC(35));
        assertEquals(0, di.lineForPC(40));
    }

    @Test
    void emptyTable() {
        DebugInfo di = new DebugInfo("", new int[0]);
        assertEquals(0, di.lineForPC(5));
    }

    @Test
    void singleElementTable() {
        DebugInfo di = new DebugInfo("", new int[]{10, 5});
        assertEquals(5, di.lineForPC(0));
        assertEquals(5, di.lineForPC(5));
        assertEquals(5, di.lineForPC(9));
        assertEquals(5, di.lineForPC(10));
        assertEquals(0, di.lineForPC(11));
        assertEquals(0, di.lineForPC(15));
    }
}
