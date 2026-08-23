package org.elite.ir;

import org.elite.eval.ELEngine;
import org.elite.eval.EvaluationContext;
import org.elite.eval.EvaluationException;
import org.elite.eval.StackTrace;
import javax.el.ELContext;
import java.lang.invoke.MethodHandle;

public class IRCompiledFunction {

  private final MethodHandle target;

  IRCompiledFunction(MethodHandle target) {
    this.target = target;
  }

  public Object execute(ELContext elctx, Object[] args) {
    ELContext previousContext = ELEngine.setCurrentELContext(elctx);
    StackTrace.addFrame(elctx, "", null, 0); // add a pseudo frame
    try {
      EvaluationContext env = new EvaluationContext(elctx);
      return target.invokeExact(env, args);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new EvaluationException(elctx, e);
    } finally {
      StackTrace.removeFrame(elctx);
      ELEngine.setCurrentELContext(previousContext);
    }
  }
}
