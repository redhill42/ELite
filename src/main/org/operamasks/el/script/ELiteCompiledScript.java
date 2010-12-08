/*
 * $Id: ELiteCompiledScript.java,v 1.1 2009/03/22 08:37:27 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
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
