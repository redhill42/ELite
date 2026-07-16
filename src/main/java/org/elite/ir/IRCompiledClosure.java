package org.elite.ir;

import elite.lang.Closure;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.closure.ClosureObject;
import org.elite.eval.closure.EnvExtent;
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.PropertyNotWritableException;
import javax.el.VariableMapper;
import java.util.Arrays;

public abstract class IRCompiledClosure extends Closure {
  private transient EvaluationContext context;

  public IRCompiledClosure(EvaluationContext context) {
    this.context = context;
  }

  public abstract String getName();

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
    return IRClosure.class;
  }

  @Override
  public boolean isProcedure() {
    return true;
  }

  @Override
  public abstract int arity(ELContext elctx);

  @Override
  public MethodInfo getMethodInfo(ELContext elctx) {
    Class<?>[] paramTypes = new Class<?>[this.arity(elctx)];
    Arrays.fill(paramTypes, Object.class);
    return new MethodInfo(getName(), Object.class, paramTypes);
  }

  @Override
  public Object invoke(ELContext elctx, Closure[] args) {
    return call(elctx, ELEngine.getArgValues(elctx, args));
  }

  @Override
  public Object call(ELContext elctx, Object[] args) {
    return execute(getContext(), args);
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
  @SuppressWarnings("unused")
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
    String name = getName();
    if (name == null || name.equals("<lambda>"))
      return "#<procedure>";
    else
      return "#<procedure:" + name + ">";
  }
}
