package org.elite.ir;

import elite.lang.Closure;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.closure.CallableClosure;
import org.elite.eval.closure.ClosureObject;
import org.elite.eval.closure.EnvExtent;
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.PropertyNotWritableException;
import javax.el.VariableMapper;
import java.util.Arrays;

import static org.elite.resources.Resources.*;

public abstract class IRCompiledClosure extends Closure
                                        implements CallableClosure {
  private transient EvaluationContext context;
  private final String name;
  private final int paramCount;

  protected IRCompiledClosure(EvaluationContext context, String name,
                              int paramCount) {
    this.context = context;
    this.name = name;
    this.paramCount = paramCount;
  }

  @Override
  public EvaluationContext getContext() {
    return this.context;
  }

  @Override
  public EvaluationContext getContext(ELContext elctx) {
    if (this.context == null) {
      if (elctx == null)
        elctx = ELEngine.getCurrentELContext();
      this.context = new EvaluationContext(elctx);
    } else {
      if (elctx != null)
        this.context.setELContext(elctx);
    }
    return context;
  }

  @Override
  public void _setenv(ELContext elctx, VariableMapper env) {
    this.context = getContext(elctx).pushContext(env);
  }

  @Override
  public Object getValue(ELContext elctx) {
    return this;
  }

  @Override
  public void setValue(ELContext elctx, Object value) {
    throw new PropertyNotWritableException();
  }

  public boolean isReadOnly(ELContext elctx) {
    return true;
  }

  @Override
  public Class<?> getType(ELContext elctx) {
    return IRCompiledClosure.class;
  }

  @Override
  public boolean isProcedure() {
    return true;
  }

  @Override
  public int arity(ELContext elctx) {
    return paramCount;
  }

  @Override
  public MethodInfo getMethodInfo(ELContext elctx) {
    Class<?>[] paramTypes = new Class<?>[this.arity(elctx)];
    Arrays.fill(paramTypes, Object.class);
    return new MethodInfo(name, Object.class, paramTypes);
  }

  @Override
  public Object invoke(ELContext elctx, Closure[] args) {
    return call(elctx, ELEngine.getArgValues(elctx, args));
  }

  @Override
  public Object call(ELContext elctx, Object[] args) {
    if (args.length != paramCount) {
      throw new EvaluationException(
        elctx, _T(EL_FN_BAD_ARG_COUNT, name, paramCount, args.length));
    }

    return execute(getContext(elctx), args);
  }

  protected abstract Object execute(EvaluationContext env, Object[] args);

  /**
   * Invoke the procedure within the given scope. The variables in the
   * scope is visible to the procedure. The procedure is behaviors like
   * a member procedure of scoped object.
   *
   * @param elctx the evaluation context
   * @param scope the scoped object
   * @param args  the procedure arguments
   * @return result of procedure execution
   */
  @Override
  public Object call_with(ELContext elctx, Object scope, Closure... args) {
    if (scope instanceof ClosureObject) {
      scope = ((ClosureObject)scope).get_owner();
    }

    EvaluationContext env = getContext(elctx);
    env = env.pushContext(new EnvExtent(env, scope));
    return execute(env, ELEngine.getArgValues(elctx, args));
  }

  @Override
  public Class<?> getExpectedType() {
    return Object.class;
  }

  @Override
  public String getExpressionString() {
    return null;
  }

  @Override
  public boolean isLiteralText() {
    return false;
  }

  public boolean equals(Object obj) {
    return this == obj;
  }

  public int hashCode() {
    return System.identityHashCode(this);
  }

  public String toString() {
    if (name == null || name.equals("<lambda>"))
      return "#<procedure>";
    else
      return "#<procedure:" + name + ">";
  }
}
