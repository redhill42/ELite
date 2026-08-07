package org.elite.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for invokedynamic-based dynamic method dispatch, focusing on
 * overloaded Java methods, argument type coercion, and edge cases.
 */
class DynamicDispatchTest extends EliteTestBase {

    // ── String: multiple overloads ──

    @Test
    void stringLength() {
        assertEquals(5, eval("\"hello\".length()"));
    }

    @Test
    void stringSubstringOneArg() {
        assertEquals("llo", eval("\"hello\".substring(2)"));
    }

    @Test
    void stringSubstringTwoArgs() {
        assertEquals("el", eval("\"hello\".substring(1, 3)"));
    }

    @Test
    void stringIndexOfChar() {
        // 'l' as a string of length 1 → resolves to indexOf(int) or indexOf(String)
        assertEquals(2, eval("\"hello\".indexOf(\"l\")"));
    }

    @Test
    void stringIndexOfCharFromIndex() {
        assertEquals(3, eval("\"hello\".indexOf(\"l\", 3)"));
    }

    @Test
    void stringReplaceChar() {
        assertEquals("heLLo", eval("\"hello\".replace(\"l\", \"L\")"));
    }

    @Test
    void stringReplaceAll() {
        assertEquals("heQQo", eval("\"hello\".replaceAll(\"l\", \"Q\")"));
    }

    @Test
    void stringStartsWith() {
        assertTrue((Boolean) eval("\"hello\".startsWith(\"he\")"));
        assertFalse((Boolean) eval("\"hello\".startsWith(\"lo\")"));
    }

    @Test
    void stringEndsWith() {
        assertTrue((Boolean) eval("\"hello\".endsWith(\"lo\")"));
    }

    @Test
    void stringContains() {
        assertTrue((Boolean) eval("\"hello\".contains(\"el\")"));
        assertFalse((Boolean) eval("\"hello\".contains(\"xx\")"));
    }

    @Test
    void stringToUpperCase() {
        assertEquals("HELLO", eval("\"hello\".toUpperCase()"));
    }

    @Test
    void stringToLowerCase() {
        assertEquals("hello", eval("\"HELLO\".toLowerCase()"));
    }

    @Test
    void stringTrim() {
        assertEquals("hello", eval("\"  hello  \".trim()"));
    }

    @Test
    void stringCharAt() {
        // charAt returns a Character, which ELite may treat as a string
        Object result = eval("\"hello\".charAt(0)");
        assertNotNull(result);
    }

    @Test
    void stringIsEmpty() {
        assertTrue((Boolean) eval("\"\".isEmpty()"));
        assertFalse((Boolean) eval("\"hello\".isEmpty()"));
    }

    // ── String.valueOf: static method with many overloads ──

    @Test
    void stringValueOfInt() {
        assertEquals("42", eval("String.valueOf(42)"));
    }

    @Test
    void stringValueOfBoolean() {
        assertEquals("true", eval("String.valueOf(true)"));
    }

    @Test
    void stringValueOfDouble() {
        Object result = eval("String.valueOf(3.14)");
        assertTrue(((String) result).startsWith("3.14"));
    }

    // ── Math: static methods with primitive overloads ──

    @Test
    void mathAbsInt() {
        assertEquals(42L, evalL("Math.abs(-42)"));
    }

    @Test
    void mathAbsDouble() {
        assertEquals(42.5, evalD("Math.abs(-42.5)"), 0.001);
    }

    @Test
    void mathMaxInt() {
        assertEquals(10L, evalL("Math.max(5, 10)"));
    }

    @Test
    void mathMaxDouble() {
        assertEquals(10.5, evalD("Math.max(5.5, 10.5)"), 0.001);
    }

    @Test
    void mathMinInt() {
        assertEquals(5L, evalL("Math.min(5, 10)"));
    }

    @Test
    void mathSqrt() {
        assertEquals(3.0, evalD("Math.sqrt(9)"), 0.001);
    }

    // ── System.out: void return ──

    @Test
    void systemOutPrintln() {
        exec("System.out.println(\"invokedynamic dispatch test\")");
    }

    // ── ArrayList: methods with object and primitive overloads ──

    @Test
    void arrayListAddAndGet() {
        exec("import java.util.ArrayList");
        exec("define lst = new ArrayList()");
        exec("lst.add(\"first\")");
        exec("lst.add(\"second\")");
        assertEquals("first", eval("lst.get(0)"));
        assertEquals("second", eval("lst.get(1)"));
        assertEquals(2L, evalL("lst.size()"));
    }

    @Test
    void arrayListAddAtIndex() {
        exec("import java.util.ArrayList");
        exec("define lst = new ArrayList()");
        exec("lst.add(\"a\")");
        exec("lst.add(\"c\")");
        exec("lst.add(1, \"b\")");
        assertEquals("a", eval("lst.get(0)"));
        assertEquals("b", eval("lst.get(1)"));
        assertEquals("c", eval("lst.get(2)"));
    }

    @Test
    void arrayListRemoveByIndex() {
        exec("import java.util.ArrayList");
        exec("define lst = new ArrayList()");
        exec("lst.add(\"a\")");
        exec("lst.add(\"b\")");
        exec("lst.add(\"c\")");
        Object removed = eval("lst.remove(1)");
        assertEquals("b", removed);
        assertEquals(2L, evalL("lst.size()"));
    }

    @Test
    void arrayListRemoveByObject() {
        exec("import java.util.ArrayList");
        exec("define lst = new ArrayList()");
        exec("lst.add(\"a\")");
        exec("lst.add(\"b\")");
        exec("lst.add(\"c\")");
        Object result = eval("lst.remove(\"b\")");
        assertTrue((Boolean) result);
        assertEquals(2L, evalL("lst.size()"));
    }

    @Test
    void arrayListClear() {
        exec("import java.util.ArrayList");
        exec("define lst = new ArrayList()");
        exec("lst.add(\"a\")");
        exec("lst.clear()");
        assertEquals(0L, evalL("lst.size()"));
    }

    @Test
    void arrayListContains() {
        exec("import java.util.ArrayList");
        exec("define lst = new ArrayList()");
        exec("lst.add(\"hello\")");
        assertTrue((Boolean) eval("lst.contains(\"hello\")"));
        assertFalse((Boolean) eval("lst.contains(\"world\")"));
    }

    // ── StringBuilder: mutable char sequence ──

    @Test
    void stringBuilderAppend() {
        exec("import java.lang.StringBuilder");
        exec("define sb = new StringBuilder()");
        exec("sb.append(\"Hello\")");
        exec("sb.append(\" \")");
        exec("sb.append(\"World\")");
        assertEquals("Hello World", eval("sb.toString()"));
    }

    @Test
    void stringBuilderAppendInt() {
        exec("import java.lang.StringBuilder");
        exec("define sb = new StringBuilder()");
        exec("sb.append(\"value=\")");
        exec("sb.append(42)");
        assertEquals("value=42", eval("sb.toString()"));
    }

    // ── Chained method calls ──

    @Test
    void chainedStringCalls() {
        assertEquals(5, eval("\"  hello  \".trim().length()"));
    }

    @Test
    void chainedCallsWithToList() {
        assertEquals("HELLO", eval("\"hello\".toUpperCase()"));
        // Verify result is a String that can be further operated on
        Object s = eval("\"hello\".toUpperCase()");
        assertTrue(s instanceof String);
        assertEquals("HELLO", s);
    }

    // ── Method with null argument ──

    @Test
    void stringEquals() {
        assertTrue((Boolean) eval("\"hello\".equals(\"hello\")"));
        assertFalse((Boolean) eval("\"hello\".equals(\"world\")"));
    }

    @Test
    void objectToString() {
        exec("import java.util.Date");
        Object result = eval("Date(0).toString()");
        assertNotNull(result);
        assertTrue(result instanceof String);
    }

    // ── Thread: static and instance methods ──

    @Test
    void threadCurrentThread() {
        Object t = eval("Thread.currentThread()");
        assertNotNull(t);
    }

    @Test
    void threadGetName() {
        Object name = eval("Thread.currentThread().getName()");
        assertNotNull(name);
        assertTrue(name instanceof String);
    }

    // ── Integer: static parse and instance toString ──

    @Test
    void integerParseInt() {
        assertEquals(42L, evalL("Integer.parseInt(\"42\")"));
    }

    @Test
    void integerToString() {
        exec("define i = Integer.valueOf(99)");
        assertEquals("99", eval("i.toString()"));
    }

    // ── Method with multiple consecutive calls on same object ──

    @Test
    void repeatedMethodCallsOnSameObject() {
        exec("import java.util.ArrayList");
        exec("define lst = new ArrayList()");
        // Multiple add calls — all should dispatch correctly
        for (int i = 0; i < 5; i++) {
            exec("lst.add(" + i + ")");
        }
        assertEquals(5L, evalL("lst.size()"));
        assertEquals(0L, evalL("lst.get(0)"));
        assertEquals(4L, evalL("lst.get(4)"));
    }

    // ── Edge case: method name collision with ELite keyword ──

    @Test
    void stringSplit() {
        exec("import java.util.Arrays");
        exec("define parts = \"a,b,c\".split(\",\")");
        // split returns String[], verify it's a Java array
        Object parts = eval("parts");
        assertNotNull(parts);
    }

    // ── Multiple objects, same method name, different classes ──

    @Test
    void multipleReceiversSameMethodName() {
        exec("import java.util.ArrayList");
        exec("define s = \"hello\"");
        exec("define lst = new ArrayList()");
        exec("lst.add(\"x\")");
        // Both String and ArrayList have .isEmpty()
        assertFalse((Boolean) eval("s.isEmpty()"));
        assertFalse((Boolean) eval("lst.isEmpty()"));
    }
}
