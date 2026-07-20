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

package org.elite.eval;

import org.elite.ir.BytecodeCompiler;
import org.elite.ir.IRBuilder;
import org.elite.ir.IRCompiledFunction;
import org.elite.ir.IRProgram;
import org.elite.parser.ASTDumper;
import org.elite.parser.ELNode;
import org.elite.parser.Position;
import javax.el.ELContext;
import javax.el.FunctionMapper;
import javax.el.VariableMapper;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ELProgram implements Serializable
{
    private final ExternalImports imps;
    private final List<ELNode> defs;
    private final List<ELNode> exps;

    private String filename;
    private int startLine = 1;
    private boolean standalone;

    /**
     * Optimization level for expression evaluation.
     * <ul>
     *   <li>0 — AST interpreter (correctness baseline)</li>
     *   <li>1 — IR interpreter (for IR verification only)</li>
     *   <li>2 — JVM bytecode (default; no fallback to interpreter)</li>
     *   <li>3 — JVM bytecode (reserved for aggressive optimizations; currently same as 2)</li>
     * </ul>
     * Read from system property {@code elite.opt.level}; defaults to 2 (bytecode).
     */
    public static final int OPT_LEVEL = Integer.getInteger("elite.opt.level", 2);

    @Serial
    private static final long serialVersionUID = 3112245719728771823L;

    public ELProgram() {
        this.imps = new ExternalImports();
        this.defs = new ArrayList<>();
        this.exps = new ArrayList<>();
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public void setStandalone(boolean standalone) {
        this.standalone = standalone;
    }

    public boolean isStandalone() {
        return standalone;
    }

    public void addExpression(ELNode exp) {
        (isDef(exp) ? defs : exps).add(exp);
    }

    private static boolean isDef(ELNode node) {
        if (node instanceof ELNode.DEFINE) {
            ELNode exp = ((ELNode.DEFINE)node).expr;
            return exp instanceof ELNode.LAMBDA || exp instanceof ELNode.CLASSDEF;
        } else {
            return false;
        }
    }

    /**
     * Get external imports for this <code>ELProgram</code>.
     */
    public ExternalImports getImports() { return imps; }

    /**
     * @return the list of definition statements.
     */
    public List<ELNode> getDefinitions() { return defs; }

    /**
     *  @return the list of expression/statement nodes.
     */
    public List<ELNode> getExpressions() { return exps; }

    /**
     * Execute the program entry point using AST interpreter. This is the
     * baseline for program executed correctly. The GrammarParser and
     * metaprogramming also needs AST interpreter for compile time evaluation.
     */
    public Object execute(ELContext elctx) {
        FunctionMapper fm = elctx.getFunctionMapper();
        VariableMapper vm = elctx.getVariableMapper();

        // The function mapper is not significant in XEL, we built it for all
        // expressions for performance reasons. The variable mapper will
        // be built for individual expression.
        if (fm != null) {
            FunctionMapperBuilder fmb = new FunctionMapperBuilder(fm);
            for (ELNode node : exps) {
                node.applyFunctionMapper(fmb);
            }
            fm = fmb.build();
        }

        // Import modules and classes to populate global context. The global
        // context is used by compilation and execution.
        imps.importExternals(elctx);

        // Evaluate expressions in global context.
        EvaluationContext env = new EvaluationContext(elctx, fm, vm);
        Frame frame = StackTrace.addFrame(elctx, "__toplevel__", filename,
                                          Position.make(startLine, 1));

        try {
            // Define function and class for forward reference
            for (ELNode node : defs) {
                frame.setPos(node.pos);
                node.getValue(env);
            }

            Object result = null;
            for (ELNode node : exps) {
                frame.setPos(node.pos);
                result = node.getValue(env);
            }
            return result;
        } finally {
            StackTrace.removeFrame(elctx);
        }
    }

    /**
     * Compile program into IR. The IR can be executed by IR interpreter or
     * compile to Java bytecode by BytecodeCompiler.
     */
    public IRProgram compile(ELContext elctx) {
        // Import modules and classes to populate global context. The global
        // context is used by compilation and execution.
        imps.importExternals(elctx);
        return IRBuilder.compile(elctx, this);
    }

    /**
     * Compile program into Java bytecode.
     */
    public IRCompiledFunction compileToByteCode(ELContext elctx) {
        return BytecodeCompiler.compile(compile(elctx));
    }

    /**
     * Dump the AST tree.
     */
    public String dump() {
        return ASTDumper.dump(this);
    }
}
