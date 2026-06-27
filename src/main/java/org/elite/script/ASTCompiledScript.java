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

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.ScriptEngine;
import javax.el.ELException;

import org.elite.eval.ELProgram;
import org.elite.eval.EvaluationException;

class ASTCompiledScript extends CompiledScript
{
    private final ELiteScriptEngine engine;
    private final ELProgram program;

    ASTCompiledScript(ELiteScriptEngine engine, ELProgram program) {
        this.engine = engine;
        this.program = program;
    }

    public Object eval(ScriptContext context) throws ScriptException {
        try {
            return program.execute(engine.getELContext(context));
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
