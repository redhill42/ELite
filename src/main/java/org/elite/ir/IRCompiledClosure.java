package org.elite.ir;

import elite.lang.Closure;
import elite.lang.Symbol;
import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.VariableMapperImpl;
import org.elite.eval.closure.AbstractClosure;
import org.elite.eval.closure.CallableClosure;
import org.elite.eval.seq.Cons;
import javax.el.ELContext;
import javax.el.MethodInfo;
import javax.el.PropertyNotWritableException;
import javax.el.ValueExpression;
import javax.el.VariableMapper;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static org.elite.resources.Resources.*;

public abstract class IRCompiledClosure extends Closure
                                        implements CallableClosure {
  private transient EvaluationContext context;

  protected IRCompiledClosure(EvaluationContext context) {
    this.context = context;
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
  public abstract int arity(ELContext elctx);

  @Override
  public MethodInfo getMethodInfo(ELContext elctx) {
    return null; // unused
  }

  @Override
  public Object invoke(ELContext elctx, Closure[] args) {
    return call(elctx, ELEngine.getArgValues(elctx, args));
  }

  @Override
  public Object call(ELContext elctx, Object[] args) {
    return execute(getContext(elctx), args);
  }

  /**
   * Invoke the procedure within the given scope. The variables in the
   * scope is visible to the procedure. The procedure is behaviors like
   * a member procedure of scoped object.
   *
   * @param elctx the evaluation context
   * @param scope the scoped object
   * @return result of procedure execution
   */
  @SuppressWarnings("unused")
  public Object call_with(ELContext elctx, Object scope) {
    EvaluationContext env = getContext(elctx);
    env = env.pushContext(new EnvExtent(env, scope));
    return execute(env, new Object[]{scope});
  }

  /**
   * Execute the actual closure method.
   * @param env the evaluation context
   * @param args the procedure arguments
   * @return result of procedure execution
   */
  protected abstract Object execute(EvaluationContext env, Object[] args);

  /**
   * Fill procedure execution arguments with default values.
   *
   * @param elctx the evaluation context
   * @param nvars number of procedure parameters
   * @param declaringClass where the actual closure method declared
   * @param methodName the actual closure method name
   * @param args the procedure arguments
   * @return arguments with default values filled.
   */
  @SuppressWarnings("unused")
  protected static Object[]
  getArgs(ELContext elctx, int nvars, boolean isBlock, Class<?> declaringClass,
          String methodName, Object[] args) {
    if (args.length == nvars)
      return args;

    if (isBlock) {
      assert nvars == 1;
      return new Object[]{args};
    }

    try {
      Method method = declaringClass.getMethod(
        methodName, EvaluationContext.class, Object[].class);
      MetaMethod meta = method.getAnnotation(MetaMethod.class);
      Value[] defaultValues = meta.defaultValues();

      int argc = args.length;
      if ((argc > nvars) || (argc + defaultValues.length < nvars))
        throw new EvaluationException(
          elctx, _T(EL_FN_BAD_ARG_COUNT, meta.name(), nvars, argc));

      int delta = nvars - argc;
      Object[] xargs = new Object[nvars];
      System.arraycopy(args, 0, xargs, 0, argc);
      for (int i = argc, j = defaultValues.length - delta; i < nvars; i++, j++)
        xargs[i] = getDefaultValue(elctx, defaultValues[j]);
      return xargs;
    } catch (NoSuchMethodException e) {
      throw new EvaluationException(elctx, e);
    }
  }

  private static Object getDefaultValue(ELContext elctx, Value value) {
    return switch (value.kind()) {
      case NULL   -> null;
      case NIL    -> Cons.nil();
      case BOOL   -> value.boolValue();
      case CHAR   -> value.charValue();
      case INT    -> value.intValue();
      case LONG   -> value.longValue();
      case FLOAT  -> value.floatValue();
      case DOUBLE -> value.doubleValue();
      case STRING -> value.stringValue();
      case SYMBOL -> Symbol.valueOf(value.stringValue());
      case CLASS  -> value.classValue();
      case FIELD  -> getFieldValue(elctx, value.classValue(), value.stringValue());
      case CONST  -> getConstValue(elctx, value.classValue(), value.intValue());
    };
  }

  private static Object getFieldValue(ELContext elctx, Class<?> c, String name) {
    try {
      return c.getField(name).get(null);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new EvaluationException(elctx, e);
    }
  }

  private static Object getConstValue(ELContext elctx, Class<?> c, int index) {
    Object[] constants = (Object[])getFieldValue(elctx, c, "$C");
    return constants[index];
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
    return "#<procedure>";
  }

  private static class EnvExtent extends VariableMapperImpl {
    final EvaluationContext env;
    final Object scope;

    EnvExtent(EvaluationContext env, Object scope) {
      this.env = env;
      this.scope = scope;
    }

    public ValueExpression resolveVariable(String name) {
      ValueExpression value = super.resolveVariable(name);
      if (value != null)
        return value;

      value = env.resolveVariable(name);
      if (value != null)
        return value;

      // Create a wrapper to call into scoped object.
      Closure wrapper = new ScopedClosure(env, scope, name);
      super.setVariable(name, wrapper);
      return wrapper;
    }
  }

  private static class ScopedClosure extends AbstractClosure {
    final EvaluationContext env;
    final Object scope;
    final String name;

    ScopedClosure(EvaluationContext env, Object scope, String name) {
      this.env = env;
      this.scope = scope;
      this.name = name;
    }

    @Override
    public Object invoke(ELContext elctx, Closure[] args) {
      return call(elctx, ELEngine.getArgValues(elctx, args));
    }

    @Override
    public Object call(ELContext elctx, Object[] args) {
      try {
        CallSite cs = DynamicBootstrap.invokeBootstrap(
          MethodHandles.lookup(), name, MethodType.methodType(
            Object.class, EvaluationContext.class, Object.class,
            Object[].class),
          0);
        return cs.getTarget().invoke(env, scope, args);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Throwable t) {
        throw new EvaluationException(elctx, t);
      }
    }

    @Override
    public Object getValue(ELContext elctx) {
      try {
        CallSite cs = DynamicBootstrap.getValueBootstrap(
          MethodHandles.lookup(), name, MethodType.methodType(
            Object.class, EvaluationContext.class, Object.class));
        return cs.getTarget().invoke(env, scope);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch(Throwable t) {
        throw new EvaluationException(elctx, t);
      }
    }

    @Override
    public void setValue(ELContext elctx, Object value) {
      try {
        CallSite cs = DynamicBootstrap.setValueBootstrap(
          MethodHandles.lookup(), name, MethodType.methodType(
            void.class, Object.class, Object.class, EvaluationContext.class));
        cs.getTarget().invoke(value, scope, env);
      } catch (RuntimeException | Error e) {
        throw e;
      } catch(Throwable t) {
        throw new EvaluationException(elctx, t);
      }
    }
  }
}
