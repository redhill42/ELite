package org.operamasks.el.ir;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class BytecodeClosureTest {
    @Test void closureCompilesOK() {
        var p = new org.operamasks.el.parser.Parser("define f(x) => \\y => x+y; f(1)(2)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());
        assertNotNull(IRBytecodeCompiler.compile(fn));
    }
    @Test void simpleCallCompilesOK() {
        var p = new org.operamasks.el.parser.Parser("define add(a,b) => a + b; add(1, 2)");
        var prog = p.parse();
        IRFunction fn = IRBuilder.compileWithDefs(prog.getDefinitions(), prog.getExpressions());
        assertNotNull(IRBytecodeCompiler.compile(fn));
    }
}
