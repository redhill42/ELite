/*
 * Copyright (c) 2006-2011 Daniel Yuan.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */

package org.operamasks.el.script;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.ScriptEngine;
import javax.el.ELException;

import org.operamasks.el.eval.ELProgram;
import org.operamasks.el.eval.EvaluationException;

class ELiteCompiledScript extends CompiledScript
{
    private ELiteScriptEngine engine;
    private ELProgram program;

    public ELiteCompiledScript(ELiteScriptEngine engine, ELProgram program) {
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
