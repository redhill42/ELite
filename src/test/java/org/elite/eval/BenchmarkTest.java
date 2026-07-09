package org.elite.eval;

import static org.junit.jupiter.api.Assertions.*;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.elite.ir.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.elite.parser.ELNode;
import org.elite.parser.Parser;

/**
 * Performance benchmarks for ELite evaluation.
 *
 * Each benchmark runs a warmup phase followed by measurement iterations.
 * Results are printed to stdout for comparison across optimization passes.
 *
 * Build and run:
 *   mvn test -Dtest=BenchmarkTest
 */
@Disabled
class BenchmarkTest {

    private static ScriptEngine engine;

    @BeforeAll
    static void createEngine() {
        engine = new ScriptEngineManager().getEngineByName("ELite");
        assertNotNull(engine, "ELite ScriptEngine not found on classpath");
    }

    // ---- helpers ----

    private static Object eval(String expr) {
        try {
            return engine.eval(expr);
        } catch (ScriptException e) {
            throw new RuntimeException("eval failed: " + expr, e);
        }
    }

    private static void exec(String stmt) {
        try {
            engine.eval(stmt);
        } catch (ScriptException e) {
            throw new RuntimeException("exec failed: " + stmt, e);
        }
    }

    /** Run a single expression many times and return ops/sec. */
    private static double bench(String label, String expr, int warmupIters, int benchIters) {
        // Compile/pre-warm the expression
        for (int i = 0; i < warmupIters; i++) {
            eval(expr);
        }

        long start = System.nanoTime();
        for (int i = 0; i < benchIters; i++) {
            eval(expr);
        }
        long elapsed = System.nanoTime() - start;

        double opsPerSec = benchIters / (elapsed / 1_000_000_000.0);
        System.out.printf("  %-40s %10d iters  %12.0f ops/s  (%6.1f ns/op)%n",
                label, benchIters, opsPerSec, elapsed / (double) benchIters);
        return opsPerSec;
    }

    /** Evaluate a setup expression once, then benchmark a body expression. */
    private static double benchWithSetup(String label, String setup, String body,
                                          int warmupIters, int benchIters) {
        eval(setup);
        return bench(label, body, warmupIters, benchIters);
    }

    private static final int WARMUP = 200;
    private static final int ITERS  = 10_000;

    // ==================== Arithmetic ====================

    @Test
    void benchArithmeticIntAdd() {
        System.out.println("\n--- Arithmetic (int) ---");
        bench("int add (100 + 200)",      "100 + 200", WARMUP, ITERS);
        bench("int sub (500 - 37)",       "500 - 37", WARMUP, ITERS);
        bench("int mul (7 * 8)",          "7 * 8", WARMUP, ITERS);
        bench("int div (100 / 3)",        "100 / 3", WARMUP, ITERS);
        bench("int mixed (1+2*3+4*5)",   "1 + 2 * 3 + 4 * 5", WARMUP, ITERS);
        bench("int complex precedence",   "((10 + 5) * 3 - 8) / 2 + 100 * 4", WARMUP, ITERS);
    }

    @Test
    void benchArithmeticDouble() {
        System.out.println("\n--- Arithmetic (double) ---");
        bench("double add (3.14+2.72)",   "3.14 + 2.72", WARMUP, ITERS);
        bench("double mul (1.5*2.0)",     "1.5 * 2.0", WARMUP, ITERS);
        bench("double div (100.0/3.0)",   "100.0 / 3.0", WARMUP, ITERS);
        bench("double mixed",             "1.5 * 2.0 + 3.5 * 4.0 - 1.5 / 2.0", WARMUP, ITERS);
    }

    @Test
    void benchArithmeticLong() {
        System.out.println("\n--- Arithmetic (long) ---");
        bench("long add (big nums)",      "5000000000 + 7000000000", WARMUP, ITERS);
        bench("long mul overflow",        "1000000 * 1000000", WARMUP, ITERS);
    }

    // ==================== Comparisons ====================

    @Test
    void benchComparisons() {
        System.out.println("\n--- Comparisons ---");
        bench("int eq (100 == 100)",       "100 == 100", WARMUP, ITERS);
        bench("int lt (50 < 100)",         "50 < 100", WARMUP, ITERS);
        bench("int le (100 <= 100)",       "100 <= 100", WARMUP, ITERS);
        bench("string eq",                 "\"hello\" == \"hello\"", WARMUP, ITERS);
        bench("chained compare",           "10 < 20 and 20 < 30 and 30 <= 40", WARMUP, ITERS);
    }

    // ==================== Control Flow ====================

    @Test
    void benchConditional() {
        System.out.println("\n--- Conditional ---");
        bench("simple if/else",           "if (true) { 1 } else { 2 }", WARMUP, ITERS);
        bench("nested if/else",           "if (1 < 2) { if (3 > 2) { 10 } else { 20 } } else { 30 }", WARMUP, ITERS);
        bench("ternary (cond?a:b)",       "true ? 100 : 200", WARMUP, ITERS);
    }

    @Test
    void benchLoops() {
        System.out.println("\n--- Loops ---");
        exec("define whileSum(n) { define x = 0; while (x < n) { x = x + 1 }; x }");
        bench("while loop (x100)",           "whileSum(100)", WARMUP, ITERS / 100);
        exec("define eachSum(n) { define s = 0; for (j in [0..n]) { s = s + j }; s }");
        bench("for-each range (x100)",       "eachSum(100)", WARMUP, ITERS / 100);
    }

    // ==================== Function Calls ====================

    @Test
    void benchFunctionCalls() {
        System.out.println("\n--- Function Calls ---");
        // Define a function, then call it many times
        exec("define add(x, y) => x + y");
        bench("call add(x,y)",            "add(3, 4)", WARMUP, ITERS);

        exec("define factorial(n) { if (n <= 1) { 1 } else { n * factorial(n - 1) } }");
        bench("recursive factorial(10)",  "factorial(10)", WARMUP, ITERS);

        exec("define fib(n) { if (n <= 1) { n } else { fib(n-1) + fib(n-2) } }");
        bench("recursive fib(10)",        "fib(10)", WARMUP, ITERS);
    }

    // ==================== Variables ====================

    @Test
    void benchVariableAccess() {
        System.out.println("\n--- Variable Access ---");
        exec("define a = 42");
        exec("define b = 3.14");
        exec("define c = \"hello\"");
        bench("read int var",             "a", WARMUP, ITERS);
        bench("read double var",          "b", WARMUP, ITERS);
        bench("read string var",          "c", WARMUP, ITERS);
    }

    // ==================== Data Structures ====================

    @Test
    void benchDataStructures() {
        System.out.println("\n--- Data Structures ---");
        bench("list literal [1,2,3]",     "[1, 2, 3]", WARMUP, ITERS);
        bench("list index access",        "(\\list => list[2])([1,2,3,4,5])", WARMUP, ITERS);
        bench("map literal",              "{a: 1, b: 2, c: 3}", WARMUP, ITERS);
    }

    // ==================== String Operations ====================

    @Test
    void benchStrings() {
        System.out.println("\n--- String Operations ---");
        bench("string concat (~)",        "\"hello\" ~ \" \" ~ \"world\"", WARMUP, ITERS);
        bench("string length",            "\"hello world\".length", WARMUP, ITERS);
    }

    // ==================== Pattern Matching ====================

    @Test
    void benchPatternMatching() {
        System.out.println("\n--- Pattern Matching ---");
        // match/case requires specific syntax; skip standalone test
    }

    // ==================== Pipeline ====================

    @Test
    void benchPipeline() {
        System.out.println("\n--- Pipeline / Lambda ---");
        bench("simple lambda",            "(\\x => x + 1)(5)", WARMUP, ITERS);
        bench("pipeline (->)",           "5 -> (\\x => x + 1) -> (\\x => x * 2)", WARMUP, ITERS);
    }

    // ==================== Composite ====================

    @Test
    void benchComposite() {
        System.out.println("\n--- Composite (mixed workload) ---");
        // A realistic expression mixing arithmetic, vars, conditionals, and calls
        exec("define max2(a, b) { if (a >= b) { a } else { b } }");
        exec("define x = 100");
        exec("define y = 200");
        bench("mixed expr", "((x + y) * 2 - 50) / 3 + max2(x, y)", WARMUP, ITERS);
    }

    // ==================== Overall Summary ====================

    @Test
    void benchOverallSummary() {
        System.out.println("\n==============================================");
        System.out.println("  ELite Performance Benchmark Summary");
        System.out.println("  (AST Tree-Walking Interpreter — baseline)");
        System.out.println("==============================================");

        double total = 0;
        int count   = 0;

        System.out.println("\n--- Arithmetic ---");
        total += bench("10 + 20",                "10 + 20", WARMUP, ITERS); count++;
        total += bench("7 * 8",                  "7 * 8", WARMUP, ITERS); count++;
        total += bench("100.0 / 3.0",            "100.0 / 3.0", WARMUP, ITERS); count++;
        total += bench("1+2*3+4*5",              "1 + 2 * 3 + 4 * 5", WARMUP, ITERS); count++;

        System.out.println("\n--- Comparisons ---");
        total += bench("100 == 100",             "100 == 100", WARMUP, ITERS); count++;
        total += bench("\"hello\" == \"hello\"", "\"hello\" == \"hello\"", WARMUP, ITERS); count++;

        System.out.println("\n--- Control Flow ---");
        total += bench("if (true) { 1 } else { 2 }", "if (true) { 1 } else { 2 }", WARMUP, ITERS); count++;
        total += bench("true ? 100 : 200",       "true ? 100 : 200", WARMUP, ITERS); count++;

        System.out.println("\n--- Variables ---");
        exec("define a = 42");
        total += bench("read var a",             "a", WARMUP, ITERS); count++;

        System.out.println("\n--- Function Calls ---");
        exec("define sq(x) => x * x");
        total += bench("call sq(5)",             "sq(5)", WARMUP, ITERS); count++;
        total += bench("lambda (\\x=>x+1)(5)",   "(\\x => x + 1)(5)", WARMUP, ITERS); count++;

        System.out.println("\n--- Data Structures ---");
        total += bench("list [1,2,3]",          "[1, 2, 3]", WARMUP, ITERS); count++;
        total += bench("map {a:1,b:2}",         "{a: 1, b: 2}", WARMUP, ITERS); count++;

        System.out.println("\n--- Strings ---");
        total += bench("concat \"a\"~\"b\"",     "\"a\" ~ \"b\"", WARMUP, ITERS); count++;

        double avg = total / count;
        System.out.printf("%n=== Average across %d benchmarks: %,.0f ops/s (%.1f ns/op) ===%n",
                count, avg, 1_000_000_000.0 / avg);
    }

    // ==================== IR vs AST Comparison ====================

    private static ELContext elctx = ELEngine.createELContext();

    /** Benchmark IR interpreter on a simple expression. */
    private static double benchIR(String label, String expr, int warmup, int iters) {
        ELNode node = Parser.parseExpression(elctx, expr);
        IRFunction fn = IRBuilder.compile(elctx, node);
        IRInterpreter interp = new IRInterpreter(new EvaluationContext(elctx), fn);

        // Warmup
        for (int i = 0; i < warmup; i++) interp.execute(null);

        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) interp.execute(null);
        long elapsed = System.nanoTime() - start;

        double opsPerSec = iters / (elapsed / 1_000_000_000.0);
        System.out.printf("  %-40s %10d iters  %12.0f ops/s  (%6.1f ns/op)  [IR]%n",
                label, iters, opsPerSec, elapsed / (double) iters);
        return opsPerSec;
    }

    @Test
    void benchIRvsAST() {
        System.out.println("\n==============================================");
        System.out.println("  IR Interpreter vs AST Tree-Walking");
        System.out.println("==============================================");

        String[][] tests = {
            {"arithmetic: 10 + 20",         "10 + 20"},
            {"arithmetic: 7 * 8",           "7 * 8"},
            {"arithmetic: 3.14 + 2.72",     "3.14 + 2.72"},
            {"arithmetic: 100.0 / 3.0",     "100.0 / 3.0"},
            {"arithmetic: 1+2*3+4*5",       "1 + 2 * 3 + 4 * 5"},
            {"comparison: 100 == 100",      "100 == 100"},
            {"comparison: 50 < 100",        "50 < 100"},
            {"conditional: true?100:200",   "true ? 100 : 200"},
            {"conditional: false?100:200",  "false ? 100 : 200"},
            {"concat: \"a\"~\"b\"",          "\"a\" ~ \"b\""},
            {"complex mix",                 "((10 + 5) * 3 - 8) / 2 + 100 * 4"},
        };

        double astTotal = 0, irTotal = 0;
        for (String[] t : tests) {
            System.out.println("\n  " + t[0] + ":");
            astTotal += bench("  AST", t[1], WARMUP, ITERS);
            irTotal  += benchIR("  IR ", t[1], WARMUP, ITERS);
        }

        double speedup = irTotal / astTotal;
        System.out.printf("%n=== IR/AST speedup: %.2fx ===%n", speedup);
    }

    // ==================== Java Interop ====================

    /** Test bean with getter, setter, and public field. */
    public static class Person {
        private String name;
        private int age;
        public String city;
        public Person(String name, int age, String city) {
            this.name = name; this.age = age; this.city = city;
        }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    private static final Person PERSON = new Person("Alice", 30, "Beijing");
    private static final java.util.List<String> LIST = java.util.List.of("a", "b", "c", "d", "e");
    private static final java.util.Map<String, Object> MAP = java.util.Map.of("name", "Alice", "age", 30);

    private static void setupInteropVars(ScriptEngine eng) {
        eng.put("p",  PERSON);
        eng.put("list", LIST);
        eng.put("m",  MAP);
    }

    // ---- O0 / O2 / O3 helpers ----

    /** Benchmark using low-level AST evaluation. */
    private static double benchAST(String label, String expr, int warmup, int iters,
                                    EvaluationContext evalCtx) {
        ELNode node = Parser.parseExpression(elctx, expr);
        for (int i = 0; i < warmup; i++) node.getValue(evalCtx);
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) node.getValue(evalCtx);
        long elapsed = System.nanoTime() - start;
        double opsPerSec = iters / (elapsed / 1_000_000_000.0);
        System.out.printf("  %-40s %10d iters  %12.0f ops/s  (%6.1f ns/op)  [AST]%n",
                label, iters, opsPerSec, elapsed / (double) iters);
        return opsPerSec;
    }

    /** Benchmark using IR interpreter (O2 path). */
    private static double benchIR2(String label, String expr, int warmup, int iters,
                                    javax.el.ELContext ectx, EvaluationContext evalCtx) {
        IRFunction fn = IRBuilder.compile(ectx, Parser.parseExpression(ectx, expr));
        IRInterpreter interp = new IRInterpreter(evalCtx, fn);
        for (int i = 0; i < warmup; i++) interp.execute(null);
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) interp.execute(null);
        long elapsed = System.nanoTime() - start;
        double opsPerSec = iters / (elapsed / 1_000_000_000.0);
        System.out.printf("  %-40s %10d iters  %12.0f ops/s  (%6.1f ns/op)  [IR]%n",
                label, iters, opsPerSec, elapsed / (double) iters);
        return opsPerSec;
    }

    /** Benchmark using bytecode compiler (O3 path). */
    private static double benchBC(String label, String expr, int warmup, int iters,
                                   javax.el.ELContext ectx, EvaluationContext evalCtx) {
        IRFunction fn = IRBuilder.compile(elctx, Parser.parseExpression(elctx, expr));
        IRCompiledFunction cf = IRBytecodeCompiler.compile(fn);
        for (int i = 0; i < warmup; i++) cf.execute(ectx, null);
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) cf.execute(ectx, null);
        long elapsed = System.nanoTime() - start;
        double opsPerSec = iters / (elapsed / 1_000_000_000.0);
        System.out.printf("  %-40s %10d iters  %12.0f ops/s  (%6.1f ns/op)  [BC]%n",
                label, iters, opsPerSec, elapsed / (double) iters);
        return opsPerSec;
    }

    // ==================== Java Interop Benchmarks ====================

    @Test
    void benchJavaInteropComparison() {
        System.out.println("\n==============================================");
        System.out.println("  Java Interop: O0 (AST) vs O2 (IR) vs O3 (BC)");
        System.out.println("==============================================");

        setupInteropVars(engine);
        javax.el.ELContext ectx = (javax.el.ELContext) engine.get(ELContext.class.getName());
        EvaluationContext evalCtx = new EvaluationContext(ectx,
            ectx.getFunctionMapper(), ectx.getVariableMapper());

        int warmup = 100;
        int iters  = 10_000;
        int itersHeavy = iters / 10; // for expressions with side effects (setter) or contains

        String[][] interopTests = {
            // {label, expr, itersOverride}
            // Getter: INVOKE_GETTER
            {"getter (p.name)",              "p.name",              String.valueOf(iters)},
            {"prim getter (p.age)",          "p.age",               String.valueOf(iters)},
            // Setter: INVOKE_SETTER
            {"setter (p.name = \"X\")",      "p.name = \"X\"",     String.valueOf(itersHeavy)},
            {"prim setter (p.age=0)",        "p.age = 0",          String.valueOf(itersHeavy)},
            // Field load/store: LOAD_FIELD / STORE_FIELD
            {"field load (p.city)",          "p.city",              String.valueOf(iters)},
            {"field store (p.city=\"X\")",   "p.city = \"X\"",     String.valueOf(itersHeavy)},
            // Map access: LOAD_PROPERTY
            {"map get (m.name)",             "m.name",              String.valueOf(iters)},
            // List contains: CONTAINS
            {"contains (elem in list)",      "\"c\" in list",       String.valueOf(iters)},
            // List index
            {"list index (list[2])",         "list[2]",             String.valueOf(iters)},
        };

        double totalO0 = 0, totalO2 = 0, totalO3 = 0;

        for (String[] t : interopTests) {
            String label = t[0];
            String expr  = t[1];
            int n = Integer.parseInt(t[2]);

            System.out.println("\n  " + label + ":");
            totalO0 += benchAST(label, expr, warmup, n, evalCtx);
            totalO2 += benchIR2(label, expr, warmup, n, ectx, evalCtx);
            totalO3 += benchBC(label, expr, warmup, n, ectx, evalCtx);
        }

        System.out.printf("%n=== Overall interop scores ===%n");
        System.out.printf("  O0 (AST):  %,.0f%n", totalO0);
        System.out.printf("  O2 (IR):   %,.0f  (%.2fx vs AST)%n", totalO2, totalO2 / totalO0);
        System.out.printf("  O3 (BC):   %,.0f  (%.2fx vs AST, %.2fx vs IR)%n",
                totalO3, totalO3 / totalO0, totalO3 / totalO2);
    }

    @Test
    void benchArithmeticComparison() {
        System.out.println("\n==============================================");
        System.out.println("  Arithmetic: O0 (AST) vs O2 (IR) vs O3 (BC)");
        System.out.println("==============================================");

        javax.el.ELContext ectx = (javax.el.ELContext) engine.get(ELContext.class.getName());
        EvaluationContext evalCtx = new EvaluationContext(ectx,
            ectx.getFunctionMapper(), ectx.getVariableMapper());

        int warmup = 200;
        int iters  = 10_000;

        String[][] tests = {
            {"int add 10+20",           "10 + 20"},
            {"int mul 7*8",             "7 * 8"},
            {"int complex 1+2*3+4*5",   "1 + 2 * 3 + 4 * 5"},
            {"double add 3.14+2.72",    "3.14 + 2.72"},
            {"double div 100.0/3.0",    "100.0 / 3.0"},
            {"long add big",            "5000000000 + 7000000000"},
            {"compare 100==100",        "100 == 100"},
            {"compare str \"a\"==\"a\"", "\"a\" == \"a\""},
            {"ternary true?100:200",    "true ? 100 : 200"},
        };

        double totalO0 = 0, totalO2 = 0, totalO3 = 0;

        for (String[] t : tests) {
            System.out.println("\n  " + t[0] + ":");
            totalO0 += benchAST(t[0], t[1], warmup, iters, evalCtx);
            totalO2 += benchIR2(t[0], t[1], warmup, iters, ectx, evalCtx);
            totalO3 += benchBC(t[0], t[1], warmup, iters, ectx, evalCtx);
        }

        System.out.printf("%n=== Overall arithmetic scores ===%n");
        System.out.printf("  O0 (AST):  %,.0f%n", totalO0);
        System.out.printf("  O2 (IR):   %,.0f  (%.2fx vs AST)%n", totalO2, totalO2 / totalO0);
        System.out.printf("  O3 (BC):   %,.0f  (%.2fx vs AST, %.2fx vs IR)%n",
                totalO3, totalO3 / totalO0, totalO3 / totalO2);
    }
}
