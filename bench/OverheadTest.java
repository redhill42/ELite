/**
 * Measure the overhead of Object[] packing in function calls.
 * Compares current compiled fib_rec vs hand-crafted static method.
 */
public class OverheadTest {
    static final int N = 20;
    static final int WARM = 1000;
    static final int ITERS = 10000;

    // === Hand-written version with typed int params ===
    static int fib_typed(int n) {
        if (n <= 1) return n;
        return fib_typed(n - 1) + fib_typed(n - 2);
    }

    public static void main(String[] args) throws Exception {
        // Warmup
        for (int i = 0; i < WARM; i++) fib_typed(N);
        org.elite.parser.Parser p = new org.elite.parser.Parser(
            "define fib(n) { if (n <= 1) { n } else { fib(n-1) + fib(n-2) } }; fib(20)");
        var prog = p.parse();
        var ir = org.elite.ir.IRBuilder.compileWithDefs(
            prog.getDefinitions(), prog.getExpressions());
        var bc = org.elite.ir.IRBytecodeCompiler.compile(ir);
        for (int i = 0; i < WARM; i++) bc.execute(null);

        // Benchmark: typed (hand-written)
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) fib_typed(N);
        long tTyped = System.nanoTime() - t0;

        // Benchmark: current bytecode (Object[] packing)
        t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) bc.execute(null);
        long tBC = System.nanoTime() - t0;

        System.out.printf("Typed (hand-written): %8.1f ns/call\n", tTyped / (double) ITERS);
        System.out.printf("Current (Object[] pack): %8.1f ns/call\n", tBC / (double) ITERS);
        System.out.printf("Overhead: %.1fx\n", (double) tBC / tTyped);
    }
}
