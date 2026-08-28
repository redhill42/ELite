package org.elite.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.jscience.mathematics.vector.Matrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests that run the sample .xel scripts to catch
 * regressions in the full compilation and execution pipeline.
 *
 * <p>Every sample is executed twice in separate JVMs:
 * <ul>
 *   <li>opt level 2 — JVM bytecode executor (the default)</li>
 *   <li>opt level 0 — AST interpreter (correctness baseline)</li>
 * </ul>
 * and the two runs must produce identical output.
 *
 * <p>Swing-dependent samples (GameOfLife, meta, swing) are excluded
 * because they require a display environment. {@code list} is excluded
 * from the output comparison because it prints a {@code shuffle} result
 * (nondeterministic); it still must run without errors.
 */
class SampleScriptTest {

    private static final List<String> SAMPLES = List.of(
        "C", "dsl", "hello", "list", "rbtree", "scheme", "seq", "uri", "xml", "xmlbuilder"
    );

    private static final String SAMPLE_DIR = "src/sample/";

    private static final long SCRIPT_TIMEOUT_SECONDS = 180;

    private ScriptEngine engine;

    static List<String> sampleNames() {
        return SAMPLES;
    }

    @BeforeEach
    void setup() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleNames")
    void sampleRunsWithoutError(String name) throws Exception {
        Path path = Paths.get(SAMPLE_DIR + name + ".xel");
        assertTrue(Files.exists(path), "Sample file not found: " + path);

        engine.getContext().setWriter(
          new PrintWriter(OutputStream.nullOutputStream()));
        String source = Files.readString(path);
        assertDoesNotThrow(() -> engine.eval(source),
            () -> name + ".xel should execute without errors");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleNames")
    void sampleOutputMatchesAstInterpreter(String name) throws Exception {
        Path path = Paths.get(SAMPLE_DIR + name + ".xel").toAbsolutePath();
        assertTrue(Files.exists(path), "Sample file not found: " + path);

        System.out.println("Running " + name);
        String bytecodeOut = runSample(name, "2", path);
        String astOut = runSample(name, "0", path);

        assertEquals(astOut, bytecodeOut,
            () -> name + ".xel output differs between AST interpreter"
                + " (opt level 0) and bytecode executor (opt level 2)");
    }

    /**
     * Run a sample in a separate JVM at the given optimization level and
     * capture its stdout. A fresh JVM is required because the optimization
     * level is fixed when {@code ELProgram} is loaded.
     */
    private String runSample(String name, String optLevel, Path path) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        // NB: use the attached "-O<level>" form; Main.parseOptions mangles
        // the space-separated "-O <level>" into an empty value.
        Process proc = new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                "org.elite.shell.Main", "-O" + optLevel, path.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();

        if (!proc.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            fail(name + ".xel timed out at opt level " + optLevel);
        }

        int exit = proc.exitValue();
        String out;
        try (var in = proc.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        assertEquals(0, exit,
            () -> name + ".xel exited with " + exit + " at opt level " + optLevel);
        return out;
    }

    @Test
    void matrixTest() throws Exception {
        engine.eval("""
            require 'matrix'
            let A = Matrix.random(16, 16)
            let B = Matrix.random(16, 16)
            A * B
            """);
    }
}
