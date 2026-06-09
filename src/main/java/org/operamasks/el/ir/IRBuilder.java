package org.operamasks.el.ir;

import java.util.*;

import org.operamasks.el.parser.ELNode;
import org.operamasks.el.parser.Token;

import static org.operamasks.el.ir.IRFormat.*;
import static org.operamasks.el.ir.Opcode.*;

/**
 * Converts an ELNode expression tree into IR form with explicit jump-based control flow.
 *
 * <p>Design rule: every basic block MUST end with a terminator (JUMP, RETURN, or
 * conditional JUMP followed by unconditional JUMP). No block falls through to
 * the next block in memory. This ensures correctness regardless of block ID order.
 */
public class IRBuilder {

    // ── Block management (stored by ID, output in ID order) ──
    final Map<Integer, int[]> blockMap = new LinkedHashMap<>();
    IREmitter current;
    int currentBlockId = 0;
    int nextBlockId = 1;  // 0 is the initial block

    // ── Symbol table ──
    private final Map<String, Integer> varIndex = new LinkedHashMap<>();
    private final List<String> varNames = new ArrayList<>();

    // ── Constant pool ──
    private final Map<Object, Integer> constIndex = new HashMap<>();
    private final List<Object> constants = new ArrayList<>();

    // ── Loop stack ──
    private record LoopTargets(int continueBlock, int breakBlock) {}
    private final Deque<LoopTargets> loopStack = new ArrayDeque<>();

    // ── Tail-call optimization ──
    private String lambdaName = null;
    private boolean inTailPosition = false;

    IRBuilder() { currentBlockId = 0; current = new IREmitter(); }

    /** Create a nested builder inheriting TCO state. */
    private IRBuilder(IRBuilder parent) {
        this.currentBlockId = 0;
        this.current = new IREmitter();
        this.lambdaName = parent.lambdaName;
    }

    // ============ MAIN DISPATCH ============

    void build(ELNode node) {
        if (node == null) { emitPushNull(); return; }

        if (node instanceof ELNode.COMPOUND)   { buildCompound((ELNode.COMPOUND) node); return; }
        if (node instanceof ELNode.FOREACH)    { buildForEach((ELNode.FOREACH) node); return; }
        if (node instanceof ELNode.CONST_MATCH) { buildTrampoline(node); return; }
        if (node instanceof ELNode.MATCH)      { buildTrampoline(node); return; }

        switch (node.op) {
            case Token.NUMBER:    buildNumber((ELNode.NUMBER) node);   break;
            case Token.STRINGVAL: buildString((ELNode.STRINGVAL) node); break;
            case Token.CHARVAL:   buildConst(((ELNode.CHARVAL) node).value); break;
            case Token.TRUE:      emitPushTrue();  break;
            case Token.FALSE:     emitPushFalse(); break;
            case Token.NULL:      emitPushNull();  break;
            case Token.SYMBOL:    buildConst(((ELNode.SYMBOL) node).value); break;

            case Token.IDENT:     buildIdent((ELNode.IDENT) node);     break;
            case Token.ACCESS:    buildAccess((ELNode.ACCESS) node);   break;
            case Token.APPLY:     buildApply((ELNode.APPLY) node);     break;

            case Token.ADD: case Token.SUB: case Token.MUL:
            case Token.DIV: case Token.REM: case Token.POW:
                buildBinaryOp(node); break;
            case Token.NEG: case Token.POS: buildUnaryOp(node); break;
            case Token.CAT: buildCat(node); break;

            case Token.BITOR:  case Token.BITAND: case Token.XOR:
            case Token.SHL:    case Token.SHR:   case Token.USHR:
            case Token.BITNOT: buildBinaryOp(node); break;

            case Token.EQ:  case Token.NE:  case Token.IDNE: case Token.IDEQ:
            case Token.LT:  case Token.LE:  case Token.GT:   case Token.GE:
                buildComparison(node); break;
            case Token.AND: case Token.OR: case Token.NOT:
                buildLogical(node); break;

            case Token.COND:     buildConditional((ELNode.COND) node); break;
            case Token.COALESCE: buildCoalesce(node); break;

            case Token.ASSIGN:   buildAssign((ELNode.ASSIGN) node); break;
            case Token.DEFINE:   buildDefine((ELNode.DEFINE) node); break;

            case Token.THEN: buildThen((ELNode.THEN) node); break;
            case Token.EXPR: if (node instanceof ELNode.EXPR) buildExpr((ELNode.EXPR) node); else buildTrampoline(node); break;

            case Token.WHILE: buildWhile((ELNode.WHILE) node); break;
            case Token.FOR: if (node instanceof ELNode.FOR) buildFor((ELNode.FOR) node); else buildTrampoline(node); break;

            case Token.BREAK: buildBreak(); break;
            case Token.CONTINUE: buildContinue(); break;
            case Token.RETURN: buildReturn((ELNode.RETURN) node); break;
            case Token.LAMBDA: buildLambda((ELNode.LAMBDA) node); break;

            case Token.IN: case Token.INSTANCEOF: case Token.LBRACKET:
            case Token.LBRACE: case Token.TUPLE: case Token.RANGE:
                buildBinaryOp(node); break;
            case Token.NIL: emitPushNull(); break;

            default: buildTrampoline(node);
        }
    }

    // ── Literals ──

    private void buildNumber(ELNode.NUMBER node) {
        Number n = node.value;
        if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
            emitPushConst(T_INT, n.intValue());
        } else if (n instanceof Long) {
            long v = n.longValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) emitPushConst(T_INT, (int) v);
            else emitPushConst(T_LONG, v);
        } else if (n instanceof Double || n instanceof Float) {
            emitPushConst(T_DOUBLE, n.doubleValue());
        } else {
            emitPushConst(K_NONE, n);
        }
    }
    private void buildString(ELNode.STRINGVAL node) { emitPushConst(T_STRING, node.value); }
    private void buildConst(Object value) { emitPushConst(K_NONE, value); }
    private void buildAccess(ELNode.ACCESS node) {
        // Emit: push base, push key, LOAD_PROPERTY
        build(node.right);   // base object
        build(node.index);   // key (field name or index)
        current.emitLoadProperty();
    }

    // ── Identifiers ──
    private void buildIdent(ELNode.IDENT node) {
        Integer idx = varIndex.get(node.id);
        if (idx != null) {
            int t = typeIdFromNode(node);
            current.emitPushVar(idx, t >= 0 ? t : T_INT);
        } else {
            // Global variable — put name in constant pool, emit PUSH_GLOBAL
            int nameIdx = putConstant(node.id);
            current.emitPushGlobal(nameIdx);
        }
    }

    // ── Apply ──
    private void buildApply(ELNode.APPLY node) {
        // Build arguments (never in tail position)
        boolean prev = inTailPosition;
        inTailPosition = false;
        for (ELNode arg : node.args) build(arg);
        // Build function target
        build(node.right);
        inTailPosition = prev;

        // Detect tail call: self-recursive call in tail position
        if (prev && lambdaName != null && node.right instanceof ELNode.IDENT) {
            String targetName = ((ELNode.IDENT) node.right).id;
            if (targetName.equals(lambdaName)) {
                current.emitInvokeTail(node.args.length);
                return;
            }
        }
        current.emitInvokeDyn(node.args.length);
    }

    // ── Binary arithmetic ──
    private void buildBinaryOp(ELNode node) {
        if (!(node instanceof ELNode.Binary bin)) { buildTrampoline(node); return; }
        int l = typeIdFromNode(bin.left), r = typeIdFromNode(bin.right);
        boolean prev = inTailPosition;
        inTailPosition = false; // sub-expressions of binary ops are NOT in tail position
        build(bin.left); build(bin.right);
        inTailPosition = prev;
        if (l >= 0 && r >= 0) emitTypedOp(node.op, widerType(l, r));
        else emitDynamicOp(node.op);
    }
    private void buildUnaryOp(ELNode node) {
        if (node instanceof ELNode.Unary un) {
            boolean prev = inTailPosition;
            inTailPosition = false;
            build(un.right);
            inTailPosition = prev;
            emitDynamicOp(node.op);
        } else buildTrampoline(node);
    }
    private void buildCat(ELNode node) {
        if (node instanceof ELNode.Binary bin) {
            boolean prev = inTailPosition;
            inTailPosition = false;
            build(bin.left); build(bin.right);
            inTailPosition = prev;
            current.emitDynCat();
        } else buildTrampoline(node);
    }

    private void emitTypedOp(int op, int t) {
        switch (op) {
            case Token.ADD -> { if(t==T_INT)current.emitIAdd(); else if(t==T_LONG)current.emitLAdd(); else if(t==T_DOUBLE)current.emitDAdd(); else current.emitDynAdd(); }
            case Token.SUB -> { if(t==T_INT)current.emitISub(); else if(t==T_LONG)current.emitLSub(); else if(t==T_DOUBLE)current.emitDSub(); else current.emitDynSub(); }
            case Token.MUL -> { if(t==T_INT)current.emitIMul(); else if(t==T_LONG)current.emitLMul(); else if(t==T_DOUBLE)current.emitDMul(); else current.emitDynMul(); }
            case Token.DIV -> { if(t==T_INT)current.emitIDiv(); else if(t==T_LONG)current.emitLDiv(); else if(t==T_DOUBLE)current.emitDDiv(); else current.emitDynDiv(); }
            case Token.REM -> { if(t==T_INT)current.emitIRem(); else current.emitDynRem(); }
            case Token.NEG -> { if(t==T_INT)current.emitINeg(); else if(t==T_LONG)current.emitLNeg(); else if(t==T_DOUBLE)current.emitDNeg(); else current.emitDynNeg(); }
            default -> emitDynamicOp(op);
        }
    }
    private void emitDynamicOp(int op) {
        switch (op) {
            case Token.ADD -> current.emitDynAdd(); case Token.SUB -> current.emitDynSub();
            case Token.MUL -> current.emitDynMul(); case Token.DIV -> current.emitDynDiv();
            case Token.REM -> current.emitDynRem(); case Token.NEG -> current.emitDynNeg();
            case Token.POW -> current.emitDynPow(); default -> current.emitDynAdd();
        }
    }

    // Shortcut for 'current' in emitter methods
    

    // ── Comparisons ──
    private void buildComparison(ELNode node) {
        if (!(node instanceof ELNode.Binary bin)) { buildTrampoline(node); return; }
        int l = typeIdFromNode(bin.left), r = typeIdFromNode(bin.right);
        boolean prev = inTailPosition;
        inTailPosition = false;
        build(bin.left); build(bin.right);
        inTailPosition = prev;
        if (l >= 0 && r >= 0) emitTypedCmp(node.op, widerType(l, r));
        else emitDynamicCmp(node.op);
    }
    private void emitTypedCmp(int op, int t) {
        switch (op) {
            case Token.EQ -> { if(t==T_INT)current.emitIEq(); else if(t==T_LONG)current.emitLEq(); else if(t==T_DOUBLE)current.emitDEq(); else current.emitDynEq(); }
            case Token.NE -> { if(t==T_INT)current.emitINe(); else if(t==T_LONG)current.emitLNe(); else if(t==T_DOUBLE)current.emitDNe(); else current.emitDynEq(); }
            case Token.LT -> { if(t==T_INT)current.emitILt(); else if(t==T_LONG)current.emitLLt(); else if(t==T_DOUBLE)current.emitDLt(); else current.emitDynLt(); }
            case Token.LE -> { if(t==T_INT)current.emitILe(); else if(t==T_LONG)current.emitLLe(); else if(t==T_DOUBLE)current.emitDLe(); else current.emitDynLe(); }
            case Token.GT -> { if(t==T_INT)current.emitIGt(); else if(t==T_LONG)current.emitLGt(); else if(t==T_DOUBLE)current.emitDGt(); else current.emitDynLt(); }
            case Token.GE -> { if(t==T_INT)current.emitIGe(); else if(t==T_LONG)current.emitLGe(); else if(t==T_DOUBLE)current.emitDGe(); else current.emitDynLe(); }
            default -> current.emitDynEq();
        }
    }
    private void emitDynamicCmp(int op) {
        switch (op) { case Token.EQ -> current.emitDynEq(); case Token.LT -> current.emitDynLt(); case Token.LE -> current.emitDynLe(); default -> current.emitDynEq(); }
    }

    // ── Logical AND/OR/NOT ──
    private void buildLogical(ELNode node) {
        if (node.op == Token.NOT) { build(((ELNode.Unary) node).right); current.emitNot(); return; }
        ELNode.Binary bin = (ELNode.Binary) node;
        int contB = allocBlockId(), endB = allocBlockId();
        if (node.op == Token.AND) {
            build(bin.left); current.emitDup(); current.emitJumpIfFalse(endB);
            current.emitPop(); build(bin.right); current.emitJump(contB);
            startBlock(endB); current.emitPop(); emitPushFalse(); current.emitJump(contB);
        } else {
            build(bin.left); current.emitDup(); current.emitJumpIfTrue(endB);
            current.emitPop(); build(bin.right); current.emitJump(contB);
            startBlock(endB); current.emitPop(); emitPushTrue(); current.emitJump(contB);
        }
        startBlock(contB);
    }

    // ── Conditional (if/else / ?:) ──
    private void buildConditional(ELNode.COND node) {
        build(node.cond);
        int thenB = allocBlockId(), elseB = allocBlockId(), mergeB = allocBlockId();
        current.emitJumpIfTrue(thenB);
        current.emitJump(elseB);
        // Both branches are in tail position if the conditional is
        startBlock(thenB); buildTail(node.left);  current.emitJump(mergeB);
        startBlock(elseB); buildTail(node.right); current.emitJump(mergeB);
        startBlock(mergeB);
    }

    // ── Coalesce ──
    private void buildCoalesce(ELNode node) {
        if (!(node instanceof ELNode.Binary bin)) { buildTrampoline(node); return; }
        build(bin.left);
        int keepB = allocBlockId(), nullB = allocBlockId(), mergeB = allocBlockId();
        current.emitDup(); current.emitJumpIfNonNull(keepB);
        current.emitJump(nullB);
        startBlock(nullB); current.emitPop(); build(bin.right); current.emitJump(mergeB);
        startBlock(keepB); current.emitJump(mergeB);
        startBlock(mergeB);
    }

    // ── Assign/Define ──
    private void buildAssign(ELNode.ASSIGN node) {
        build(node.right);
        if (node.left instanceof ELNode.IDENT ident) {
            int idx = ensureVar(ident.id); current.emitDup(); current.emitStoreVar(idx);
        } else buildTrampoline(node);
    }
    private void buildDefine(ELNode.DEFINE node) {
        if (node.expr != null) { build(node.expr); int idx = ensureVar(node.id); current.emitDup(); current.emitStoreVar(idx); }
    }

    // ── Sequential ──
    private void buildThen(ELNode.THEN node) {
        build(node.left); current.emitPop();
        // right side is in tail position if the THEN is
        buildTail(node.right);
    }
    private void buildExpr(ELNode.EXPR node) { build(node.right); }
    private void buildCompound(ELNode.COMPOUND node) {
        for (int i = 0; i < node.exps.length - 1; i++) {
            build(node.exps[i]); current.emitPop();
        }
        if (node.exps.length > 0) {
            buildTail(node.exps[node.exps.length - 1]);
        } else {
            emitPushNull();
        }
    }

    /** Build a node in tail position (preserves current tail status). */
    private void buildTail(ELNode node) {
        boolean prev = inTailPosition;
        inTailPosition = true;
        build(node);
        inTailPosition = prev;
    }

    // ── While ──
    private void buildWhile(ELNode.WHILE node) {
        int header = allocBlockId(), body = allocBlockId(), exit = allocBlockId();
        loopStack.push(new LoopTargets(header, exit));
        current.emitJump(header);
        startBlock(header); build(node.cond); current.emitJumpIfTrue(body); current.emitJump(exit);
        startBlock(body);   build(node.body); current.emitPop(); current.emitJump(header);
        startBlock(exit);   emitPushNull();
        // Exit block falls through to next — add RETURN at toplevel by caller
        loopStack.pop();
    }

    // ── For ──
    private void buildFor(ELNode.FOR node) {
        if (node.init != null) for (ELNode e : node.init) { build(e); current.emitPop(); }
        int header = allocBlockId(), body = allocBlockId(), exit = allocBlockId();
        loopStack.push(new LoopTargets(header, exit));
        current.emitJump(header);
        startBlock(header);
        if (node.cond != null) { build(node.cond); current.emitJumpIfTrue(body); } else current.emitJump(body);
        current.emitJump(exit);
        startBlock(body);
        if (node.body != null) { build(node.body); current.emitPop(); }
        if (node.step != null) for (ELNode e : node.step) { build(e); current.emitPop(); }
        current.emitJump(header);
        startBlock(exit); emitPushNull();
        loopStack.pop();
    }

    private void buildForEach(ELNode.FOREACH node) { buildTrampoline(node); }

    // ── Break / Continue / Return ──
    private void buildBreak()    { current.emitJump(loopStack.peek().breakBlock()); }
    private void buildContinue() { current.emitJump(loopStack.peek().continueBlock()); }
    private void buildReturn(ELNode.RETURN node) {
        if (node.right != null) { build(node.right); int t = typeIdFromNode(node.right); current.emitReturn(t >= 0 ? t : T_INT); }
        else current.emitReturnVoid();
    }

    // ── Lambda ──
    private void buildLambda(ELNode.LAMBDA node) {
        IRBuilder nested = new IRBuilder();
        // Set function name for TCO detection
        nested.lambdaName = node.name;
        for (ELNode.DEFINE var : node.vars) nested.ensureVar(var.id);
        // Body is always in tail position
        nested.inTailPosition = true;
        nested.build(node.body);
        if (!endsWithReturn(nested)) nested.current.emitReturnVoid();
        IRFunction fn = nested.finish(node.name != null ? node.name : "lambda", node.vars.length);
        emitPushConst(K_NONE, fn);
    }

    // ── Trampoline ──
    private void buildTrampoline(ELNode node) {
        int poolIdx = putConstant(node);
        current.emit2(0xE0 /* OP_INTERP_TRAMPOLINE */, K_DYN, poolIdx, 0);
    }

    // ── Block management ──
    private int allocBlockId() { return nextBlockId++; }

    private void startBlock(int blockId) {
        if (current != null && !current.isEmpty()) {
            blockMap.put(currentBlockId, current.toArray());
            current.clear();
        }
        currentBlockId = blockId;
        current = new IREmitter();
    }

    // ── Symbol/type helpers ──
    int ensureVar(String name) {
        return varIndex.computeIfAbsent(name, k -> { varNames.add(k); return varNames.size() - 1; });
    }
    private int putConstant(Object value) {
        return constIndex.computeIfAbsent(value, k -> { constants.add(k); return constants.size() - 1; });
    }
    private int typeIdFromNode(ELNode node) {
        if (node == null) return -1;
        if (node.inferredType != null) return typeIdFromEliteType(node.inferredType);
        return switch (node.op) {
            case Token.NUMBER -> {
                Number n = ((ELNode.NUMBER) node).value;
                if (n instanceof Integer || n instanceof Short || n instanceof Byte) yield T_INT;
                if (n instanceof Long) {
                    long v = n.longValue();
                    yield (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? T_INT : T_LONG;
                }
                if (n instanceof Double || n instanceof Float) yield T_DOUBLE;
                yield -1;
            }
            case Token.STRINGVAL -> T_STRING;
            case Token.CHARVAL -> T_CHAR;
            case Token.TRUE, Token.FALSE -> T_BOOL;
            default -> -1;
        };
    }
    private static int typeIdFromEliteType(org.operamasks.el.types.Type t) {
        if (t == org.operamasks.el.types.Type.INTEGER) return T_INT;
        if (t == org.operamasks.el.types.Type.LONG) return T_LONG;
        if (t == org.operamasks.el.types.Type.DOUBLE) return T_DOUBLE;
        if (t == org.operamasks.el.types.Type.FLOAT) return T_DOUBLE;
        if (t == org.operamasks.el.types.Type.BOOLEAN) return T_BOOL;
        if (t == org.operamasks.el.types.Type.STRING) return T_STRING;
        if (t == org.operamasks.el.types.Type.CHAR) return T_CHAR;
        return -1;
    }
    private static int widerType(int a, int b) {
        if (a == T_DOUBLE || b == T_DOUBLE) return T_DOUBLE;
        if (a == T_LONG || b == T_LONG) return T_LONG;
        return a >= 0 ? a : (b >= 0 ? b : T_INT);
    }

    // ── Finalization ──
    private static boolean endsWithReturn(IRBuilder b) {
        if (b.current != null && !b.current.isEmpty()) {
            InstructionView v = new InstructionView(b.current.toArray(), 0);
            int lastOp = -1; while (v.inBounds()) { lastOp = v.opcode(); v.advance(); }
            if (lastOp == RETURN || lastOp == RETURN_VOID) return true;
        }
        int maxId = b.blockMap.keySet().stream().max(Integer::compare).orElse(-1);
        if (maxId < 0) return false;
        int[] lb = b.blockMap.get(maxId);
        if (lb == null || lb.length == 0) return false;
        InstructionView v = new InstructionView(lb, 0);
        int lastOp = -1; while (v.inBounds()) { lastOp = v.opcode(); v.advance(); }
        return lastOp == RETURN || lastOp == RETURN_VOID;
    }

    IRFunction finish(String name, int paramCount) {
        // Seal current block
        if (current != null && !current.isEmpty()) blockMap.put(currentBlockId, current.toArray());
        else if (current != null) { current.emitReturnVoid(); blockMap.put(currentBlockId, current.toArray()); }

        int count = Math.max(nextBlockId, blockMap.keySet().stream().max(Integer::compare).orElse(0) + 1);
        int[][] ordered = new int[count][];
        for (int i = 0; i < count; i++) {
            int[] code = blockMap.get(i);
            ordered[i] = code != null ? code : new IREmitter().emitNop().toArray();
        }
        IntList merged = new IntList();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) { offsets[i] = merged.size(); merged.addAll(ordered[i]); }

        return new IRFunction(name, paramCount, merged.toArray(), offsets,
            constants.toArray(new Object[0]), varNames.toArray(new String[0]), new int[count]);
    }

    // ── Convenience emits ──
    private void emitPushConst(int typeId, Object value) {
        int idx = putConstant(value);
        int kind = (typeId >= 0) ? K_PRIM : K_NONE;
        int payload = idx & 0xFFFF;
        if (idx < 0x10000) current.emit1(PUSH_CONST, kind, payload);
        else               current.emit2(PUSH_CONST, kind, idx >>> 16, idx & 0xFFFF);
    }
    private void emitPushConst(int typeId, long value)   { emitPushConst(typeId, (Object)Long.valueOf(value)); }
    private void emitPushConst(int typeId, double value) { emitPushConst(typeId, (Object)Double.valueOf(value)); }
    private void emitPushTrue()  { current.emitPushTrue(); }
    private void emitPushFalse() { current.emitPushFalse(); }
    private void emitPushNull()  { current.emitPushNull(); }

    // ── TCO compilation API (for testing and direct use) ──

    /** Compile a lambda body with the given name (for TCO detection) and parameter names. */
    static IRFunction compileLambda(String name, String[] paramNames, ELNode body) {
        IRBuilder b = new IRBuilder();
        b.lambdaName = name;
        b.inTailPosition = true;
        for (String p : paramNames) b.ensureVar(p);
        b.build(body);
        if (!endsWithReturn(b)) b.current.emitReturnVoid();
        return b.finish(name != null ? name : "lambda", paramNames.length);
    }

    // ── Static API ──

    private static final ConstantFolder FOLDER = new ConstantFolder();

    public static IRFunction compile(ELNode node) {
        IRBuilder b = new IRBuilder();
        b.build(node);
        if (!endsWithReturn(b)) {
            int typeId = b.typeIdFromNode(node);
            b.current.emitReturn(typeId >= 0 ? typeId : T_INT);
        }
        return FOLDER.transform(b.finish("<expr>", 0));
    }

    public static IRFunction compile(List<ELNode> expressions) {
        IRBuilder b = new IRBuilder();
        for (int i = 0; i < expressions.size() - 1; i++) { b.build(expressions.get(i)); b.current.emitPop(); }
        if (!expressions.isEmpty()) {
            ELNode last = expressions.get(expressions.size() - 1);
            b.build(last);
            if (!endsWithReturn(b)) {
                int t = b.typeIdFromNode(last);
                b.current.emitReturn(t >= 0 ? t : T_INT);
            }
        }
        return FOLDER.transform(b.finish("<program>", 0));
    }
}
