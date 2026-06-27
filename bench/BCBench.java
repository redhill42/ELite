import javax.el.ELContext;
import org.elite.eval.ELEngine;

/**
 * IR Interpreter vs JVM Bytecode Compiler benchmark.
 */
public class BCBench {
    static ELContext elctx = ELEngine.createELContext();
    static int WARM = 500, ITERS = 10000;

    public static void main(String[] args) {
        System.out.println("=== IR Interpreter vs Bytecode Compiler ===\n");

        // Single expressions (no function definitions needed)
        bench("int add",       "10 + 20");
        bench("int mul",       "7 * 8");
        bench("double add",    "3.14 + 2.72");
        bench("double div",    "100.0 / 3.0");
        bench("complex arith", "((10 + 5) * 3 - 8) / 2 + 100 * 4");
        bench("int compare",   "100 == 100");
        bench("int lt",        "50 < 100");
        bench("conditional",   "true ? 100 : 200");
        bench("coalesce",      "100 ?? 200");

        // With function definitions - use ScriptEngine for proper variable registration
        benchFunc("fib(15)",   "define fib(n) { if (n <= 1) { n } else { fib(n-1) + fib(n-2) } }; fib(15)", 200, 2000);
        benchFunc("fact(10)",  "define fact(n, acc) { if (n <= 1) { acc } else { fact(n-1, n*acc) } }; fact(10, 1)", 500, 5000);
        benchFunc("sumTo(100)","define sumTo(n) { define s=0; for(i in [1..n]) { s=s+i }; s }; sumTo(100)", 500, 5000);

        System.out.println("\nDone.");
    }

    static void bench(String label, String expr) {
        ELNode node = Parser.parseExpression(expr);
        IRFunction fn = IRBuilder.compile(node);

        // IR Interpreter
        IRInterpreter interp = new IRInterpreter(elctx, fn);
        for (int i = 0; i < WARM; i++) interp.execute(null);
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) interp.execute(null);
        long tIR = System.nanoTime() - t0;

        // Bytecode
        IRBytecodeCompiler.CompiledFunction bc = IRBytecodeCompiler.compile(fn);
        for (int i = 0; i < WARM; i++) bc.execute(null);
        t0 = System.nanoTime();
        for (int i = 0; i < ITERS; i++) bc.execute(null);
        long tBC = System.nanoTime() - t0;

        double irNs = tIR / (double) ITERS;
        double bcNs = tBC / (double) ITERS;
        System.out.printf("%-20s IR %8.1f ns  BC %8.1f ns  speedup %5.1fx\n",
            label, irNs, bcNs, irNs / bcNs);
    }

    static void benchFunc(String label, String prog, int warm, int iters) {
        try {
            javax.script.ScriptEngine eng = new javax.script.ScriptEngineManager().getEngineByName("ELite");
            eng.eval(prog); // Execute once to define functions, then measure the result

            // For function benchmarks, we can't easily separate IR vs BC via ScriptEngine.
            // Instead compile the program via IRBuilder and run both.
            Parser p = new Parser(prog);
            var prg = p.parse();
            IRFunction fn = IRBuilder.compileWithDefs(prg.getDefinitions(), prg.getExpressions());

            // IR Interpreter - use fresh context for each call
            IRInterpreter interp = new IRInterpreter(elctx, fn);
            for (int i = 0; i < warm; i++) interp.execute(null);
            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) interp.execute(null);
            long tIR = System.nanoTime() - t0;

            // Bytecode
            IRBytecodeCompiler.CompiledFunction bc = IRBytecodeCompiler.compile(fn);
            for (int i = 0; i < warm; i++) bc.execute(null);
            t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) bc.execute(null);
            long tBC = System.nanoTime() - t0;

            double irNs = tIR / (double) iters;
            double bcNs = tBC / (double) iters;
            System.out.printf("%-20s IR %8.1f ns  BC %8.1f ns  speedup %5.1fx\n",
                label, irNs, bcNs, irNs / bcNs);
        } catch (Exception e) {
            System.out.printf("%-20s ERROR: %s\n", label, e.getMessage());
        }
    }
}
