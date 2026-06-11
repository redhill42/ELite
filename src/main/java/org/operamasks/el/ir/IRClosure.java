package org.operamasks.el.ir;

/**
 * A closure: an IRFunction bundled with captured variable values.
 * Created by the CLOSURE opcode at runtime.
 */
public class IRClosure {
    final IRFunction function;
    final Object[] captured;

    public IRClosure(IRFunction function, Object[] captured) {
        this.function = function;
        this.captured = captured;
    }
}
