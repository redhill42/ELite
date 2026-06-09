package org.operamasks.el.ir;

/**
 * A transformation pass over an IR function.
 * Each pass reads the current IR and produces an optimized version.
 * Passes are pure functions: they never mutate the input.
 */
@FunctionalInterface
public interface IRPass {
    IRFunction transform(IRFunction input);
}
