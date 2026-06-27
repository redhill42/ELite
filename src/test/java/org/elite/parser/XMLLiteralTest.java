package org.elite.parser;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for XML literal parsing and virtual DOM operations.
 *
 * XML literals in ELite use angle-bracket syntax and produce XmlNode trees.
 */
class XMLLiteralTest extends EliteTestBase {

    @Test
    @Disabled("XML literal syntax requires grammar extension (require 'xml')")
    void xmlLiteralElement() {
        exec("require 'xml'");
        Object result = eval("<hello/>");
        assertNotNull(result);
    }

    @Test
    @Disabled("XML literal syntax requires grammar extension (require 'xml')")
    void xmlLiteralWithContent() {
        exec("require 'xml'");
        Object result = eval("<greeting>Hello, World!</greeting>");
        assertNotNull(result);
    }

    @Test
    @Disabled("XML literal syntax requires grammar extension (require 'xml')")
    void xmlLiteralWithAttributes() {
        exec("require 'xml'");
        Object result = eval("<person name=\"Alice\" age=\"30\"/>");
        assertNotNull(result);
    }

    @Test
    @Disabled("XmlDocument not registered as a global function; XML support requires grammar extension")
    void xmlVirtualNodeExists() {
        Object node = eval("XmlDocument(\"<root/>\")");
        assertNotNull(node);
    }
}
