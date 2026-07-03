package org.elite.parser;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.EliteTestBase;

/**
 * Tests for grammar-based DSL extension and operator declarations.
 *
 * These features are highly experimental and may not work in the current build.
 */
class GrammarDSLTest extends EliteTestBase {

    // ---- Operator declarations ----

    @Test
    void infixOperatorDeclaration() {
        exec("@infix(7) '%%' = (%)");
        // If supported, should parse without errors
    }

    @Test
    void prefixOperatorDeclaration() {
        exec("@prefix '!!'");
    }

    // ---- Grammar extension ----

    @Test
    void simpleGrammarRule() {
        exec("grammar { goal : 'hello' -> print(\"Hello from grammar\") }");
        // If supported, should parse and evaluate without errors
    }

    @Test
    void grammarWithCaptures() {
        exec("grammar { goal : 'greet' #name -> print(\"Hello, \" ~ name) }");
    }

    // ---- ParserCombinator API ----

    @Test
    void parserCombinatorAvailable() {
        // The ParserCombinator class should be on the classpath
        assertNotNull(ParserCombinator.class);
    }

    @Test
    void grammarClassAvailable() {
        // The Grammar class should be on the classpath
        assertNotNull(Grammar.class);
    }
}
