package org.operamasks.el.ir;

/**
 * Thrown when the bytecode compiler cannot compile an IR function
 * due to unimplemented features (not a compiler bug).
 *
 * <p>This is distinct from {@link VerifyError} or other JVM errors:
 * <ul>
 *   <li>{@code CompilationError} — capability gap, caller should fall back</li>
 *   <li>{@code VerifyError} — compiler bug, should be fixed</li>
 * </ul>
 */
public class CompilationError extends Error {
    public CompilationError(String message) {
        super(message);
    }

    public CompilationError(String message, Throwable cause) {
        super(message, cause);
    }
}
