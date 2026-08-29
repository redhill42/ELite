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
        engine.eval(
            """
            require 'matrix'
            let A = Matrix.random(16, 16)
            let B = Matrix.random(16, 16)
            A * B
            """);
    }

    @Test
    void complexTest() throws Exception {
        engine.eval(
            """
            require 'complex'
            let a = Complex(1, 2)
            let b = Complex(3, 4)
            assert "${a*b}" == "-5+10i"
            assert "${a/b}" == "0.44+0.08i"
            assert "${a.sqrt()}" == "1.272019649514069+0.7861513777574233i"
            """);
    }

    @Test
    void rationalComplexTest() throws Exception {
        engine.eval(
            """
            require 'rational'
            require 'complex'
            let a = Complex(1, 2)
            let b = Complex(3, 4)
            assert "${a/b}" == "11/25+2/25i"
            """);
    }

    @Test
    void complexMatrixTest() throws Exception {
        engine.eval(
            """
            require 'complex'
            require 'matrix'
            let A = Matrix.build(4, 4, Complex);
            let B = Matrix.build(4, 4, Complex);
            let C = A * B
            assert "$C" == "Matrix((34i, -10+38i, -20+42i, -30+46i), (10+38i, 46i, -10+54i, -20+62i), (20+42i, 10+54i, 66i, -10+78i), (30+46i, 20+62i, 10+78i, 94i))"
            """);
    }

    @Test
    void functionTest() throws Exception {
        engine.eval(
          """
          require 'function'
          define f = :x^2 + :x*:y + 1;
          assert "f(x,y) = $f" == "f(x,y) = x2 + xy + 1"
          assert "df(x,y)/dx = ${f.d(Variable(:x))}" == "df(x,y)/dx = 2x + y"
          """);
    }
}
