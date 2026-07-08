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

package org.elite.script;

import org.elite.eval.EvaluationException;
import org.elite.ir.IRCompiledFunction;

import javax.el.ELException;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

class BytecodeCompiledScript extends CompiledScript {
    private final ELiteScriptEngine engine;
    private final IRCompiledFunction function;

    BytecodeCompiledScript(ELiteScriptEngine engine,
                           IRCompiledFunction function) {
        this.engine = engine;
        this.function = function;
    }

    public Object eval(ScriptContext context) throws ScriptException {
        try {
            return function.execute(engine.getELContext(context), null);
        } catch (EvaluationException ex) {
            ScriptException ex2 = new ScriptException(ex.getMessage());
            ex2.setStackTrace(ex.getStackTrace());
            throw ex2;
        } catch (ELException ex) {
            throw new ScriptException(ex);
        }
    }

    public ScriptEngine getEngine() {
        return engine;
    }
}
