import javax.script.*;

/**
 * Runs the ELite benchmark and reports timing for each test.
 * Usage: java -cp ... BenchRunner [N]
 */
public class BenchRunner {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        ScriptEngine e = new ScriptEngineManager().getEngineByName("ELite");

        // Load the benchmark script
        String script = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("bench/bench.elite")));
        e.eval(script);

        System.out.println("=== ELite Benchmark (n=" + n + ") ===");
        int warmup = 2, iters = 10;

        time(e, "fib_iter",    "fib_iter(" + (n * 1000) + ")", warmup, iters);
        time(e, "sum_loop",    "sum_loop(" + (n * 100000) + ")", warmup, iters);
        time(e, "nested_loop", "nested_loop(" + (n * 10) + ")", warmup, iters);
        time(e, "fib_rec",     "fib_rec(" + n + ")", warmup, Math.max(3, iters/5));
        time(e, "fib_tail",    "fib_tail(" + (n * 1000) + ", 0, 1)", warmup, iters);
        time(e, "list_bench",  "list_bench(" + (n * 1000) + ")", warmup, iters);
        time(e, "str_bench",   "str_bench(" + (n * 10000) + ")", warmup, iters);
        time(e, "map_bench",   "map_bench(" + (n * 1000) + ")", warmup, iters);
    }

    static void time(ScriptEngine e, String label, String expr,
                     int warmup, int iters) throws Exception {
        for (int i = 0; i < warmup; i++) e.eval(expr);
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) e.eval(expr);
        long elapsed = System.nanoTime() - start;
        double avgMs = (elapsed / (double) iters) / 1_000_000.0;
        System.out.printf("  %-20s %10.3f ms\n", label, avgMs);
    }
}
