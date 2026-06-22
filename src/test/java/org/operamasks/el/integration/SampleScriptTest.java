package org.operamasks.el.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests that run the sample .xel scripts to catch
 * regressions in the full compilation and execution pipeline.
 *
 * <p>The optimization level is controlled by the system property
 * {@code elite.opt.level} (default: 2). Test other levels via:
 * <pre>
 *   mvn test -Dtest=SampleScriptTest -Delite.opt.level=0
 *   mvn test -Dtest=SampleScriptTest -Delite.opt.level=1
 *   mvn test -Dtest=SampleScriptTest -Delite.opt.level=3
 * </pre>
 *
 * <p>Swing-dependent samples (GameOfLife, meta, swing) are excluded
 * because they require a display environment.
 */
class SampleScriptTest {

    private static final List<String> SAMPLES = List.of(
        "hello", "seq", "rbtree", "C", "uri", "dsl", "xml", "list", "scheme"
    );

    private static final String SAMPLE_DIR = "src/sample/";

    private ScriptEngine engine;

    static List<String> sampleNames() {
        return SAMPLES;
    }

    @BeforeEach
    void setup() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine);
        // Shell-provided variables that sample scripts may reference
        engine.put("endl", System.getProperty("line.separator"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleNames")
    void sampleRunsWithoutError(String name) throws Exception {
        Path path = Paths.get(SAMPLE_DIR + name + ".xel");
        assertTrue(Files.exists(path), "Sample file not found: " + path);

        String source = Files.readString(path);
        assertDoesNotThrow(() -> engine.eval(source),
            () -> name + ".xel should execute without errors");
    }
}
