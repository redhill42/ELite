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

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptContext;
import javax.el.ELContext;

public class ELiteScriptEngineFactory implements ScriptEngineFactory
{
    public ELiteScriptEngineFactory() {
    }

    public String getName() {
        return (String)getParameter(ScriptEngine.NAME);
    }

    public String getEngineName() {
        return (String)getParameter(ScriptEngine.ENGINE);
    }

    public String getEngineVersion() {
        return (String)getParameter(ScriptEngine.ENGINE_VERSION);
    }

    public String getLanguageName() {
        return (String)getParameter(ScriptEngine.LANGUAGE);
    }

    public String getLanguageVersion() {
        return (String)getParameter(ScriptEngine.LANGUAGE_VERSION);
    }

    public List<String> getExtensions() {
        return extensions;
    }

    public List<String> getMimeTypes() {
        return mimeTypes;
    }

    public List<String> getNames() {
        return names;
    }

    public Object getParameter(String key) {
        if (key.equals(ScriptEngine.NAME)) {
            return "elite";
        } else if (key.equals(ScriptEngine.ENGINE)) {
            return "ELite";
        } else if (key.equals(ScriptEngine.ENGINE_VERSION)) {
            return "2.0.0";
        } else if (key.equals(ScriptEngine.LANGUAGE)) {
            return "ELite";
        } else if (key.equals(ScriptEngine.LANGUAGE_VERSION)) {
            return "2.0.0";
        } else if (key.equals("THREADING")) {
            return "MULTITHREADED";
        } else {
            throw new IllegalArgumentException("Invalid key");
        }
    }

    public ScriptEngine getScriptEngine() {
        return new ELiteScriptEngine(this);
    }

    // Callback method used to initialize context.
    protected void contextCreated(ELContext elctx, ScriptContext sctx) { }

    public String getMethodCallSyntax(String obj, String method, String... args) {
        String ret = obj + "." + method + "(";
        int len = args.length;
        if (len == 0) {
            ret += ")";
            return ret;
        }

        for (int i = 0; i < len; i++) {
            ret += args[i];
            if (i != len-1) {
                ret += ",";
            } else {
                ret += ")";
            }
        }
        return ret;
    }

    public String getOutputStatement(String toDisplay) {
        StringBuilder buf = new StringBuilder();
        int len = toDisplay.length();
        buf.append("print('");
        for (int i = 0; i < len; i++) {
            char ch = toDisplay.charAt(i);
            switch (ch) {
            case '\'':
                buf.append("\\'");
                break;
            case '\\':
                buf.append("\\\\");
                break;
            default:
                buf.append(ch);
                break;
            }
        }
        buf.append("\')");
        return buf.toString();
    }

    public String getProgram(String... statements) {
        int len = statements.length;
        String ret = "";
        for (int i = 0; i < len; i++) {
            ret += statements[i] + ";";
        }
        return ret;
    }

    private static List<String> names;
    private static List<String> mimeTypes;
    private static List<String> extensions;

    static {
        names = new ArrayList<String>(4);
        names.add("elite");
        names.add("ELite");
        names.add("xel");
        names.add("XEL");
        names = Collections.unmodifiableList(names);

        mimeTypes = new ArrayList<String>(0);
        mimeTypes = Collections.unmodifiableList(mimeTypes);

        extensions = new ArrayList<String>(2);
        extensions.add("elite");
        extensions.add("xel");
        extensions = Collections.unmodifiableList(extensions);
    }
}
