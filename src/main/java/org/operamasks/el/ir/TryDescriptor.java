/*
 * Copyright 2006-2026 Daniel Yuan.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.operamasks.el.ir;

/**
 * Descriptor for a try/catch/finally construct stored in the IR constant pool.
 *
 * <p>Contains pre-compiled IR functions for the try body, catch handlers, and
 * optional finally block. The IR interpreter uses the {@link #tryNode} to
 * evaluate via AST trampoline. The bytecode compiler uses the IR functions
 * to generate JVM exception tables.
 */
public class TryDescriptor {
    /** Original TRY AST node (for IR interpreter trampoline). */
    public final org.operamasks.el.parser.ELNode.TRY tryNode;
    /** Compiled IR function for the try body. */
    public final IRFunction tryBody;
    /** Exception type names for each catch clause (null = catch-all). */
    public final String[] catchTypes;
    /** Variable names for caught exceptions. */
    public final String[] catchVars;
    /** Compiled IR functions for each catch handler body. */
    public final IRFunction[] catchBodies;
    /** Compiled IR function for the finally block (null if absent). */
    public final IRFunction finallyBlock;

    public TryDescriptor(org.operamasks.el.parser.ELNode.TRY tryNode,
                         IRFunction tryBody, String[] catchTypes, String[] catchVars,
                         IRFunction[] catchBodies, IRFunction finallyBlock) {
        this.tryNode = tryNode;
        this.tryBody = tryBody;
        this.catchTypes = catchTypes;
        this.catchVars = catchVars;
        this.catchBodies = catchBodies;
        this.finallyBlock = finallyBlock;
    }
}
