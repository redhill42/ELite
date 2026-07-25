package org.elite.ir;

import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.StackTrace;
import javax.el.ELContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class IRCompiledFunction {

  private final Method method;

  IRCompiledFunction(Method m) {
    this.method = m;
  }

  public Object execute(ELContext elctx, Object[] args) {
    ELContext previousContext = ELEngine.setCurrentELContext(elctx);
    StackTrace.addFrame(elctx, "", null, 0); // add a pseudo frame
    try {
      EvaluationContext env = new EvaluationContext(elctx);
      return method.invoke(null, env, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re)
        throw re;
      if (cause instanceof Error err)
        throw err;
      throw new RuntimeException(cause);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } finally {
      StackTrace.removeFrame(elctx);
      ELEngine.setCurrentELContext(previousContext);
    }
  }
}
