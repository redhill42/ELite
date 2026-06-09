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

package org.operamasks.el.shell;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.operamasks.el.shell.command.Command;
import org.operamasks.el.shell.command.CommandProvider;
import org.operamasks.el.parser.IncompleteException;
import org.operamasks.el.parser.Position;
import org.operamasks.el.eval.StackTrace;
import static org.operamasks.el.resources.Resources.*;
import elite.lang.Builtin;

// Experimental
public class Main
{
    private ShellContext shellContext;
    private String script;
    private String filename;

    public Main() {
        this.shellContext = new ShellContext();
    }

    public static void main(String args[]) {
        Main main = new Main();
        int status = main.run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    public int run(String args[]) {
        if (!parseOptions(args)) {
            return 1;
        }

        if (shellContext.isInteractive()) {
            System.out.println(_T(ELITE_WELCOME));
        }

        try {
            ScriptEngine engine = createScriptEngine(shellContext.getArguments());
            shellContext.setEngine(engine);
            int status = 0;

            if (filename != null) {
                status = CommandProvider.exec(shellContext, filename);
            } else if (script != null) {
                status = exec_script(engine, script);
            }

            if (status != 0) {
                return status;
            }

            if (shellContext.isInteractive()) {
                repl(engine);
            }
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
            return 1;
        }

        return 0;
    }

    private boolean parseOptions(String[] args) {
        int argIndex = 0;

        try {
            for (; argIndex < args.length; argIndex++) {
                if (args[argIndex].equals("-e")) {
                    script = args[++argIndex];
                } else if (args[argIndex].equals("-c") || args[argIndex].equals("-encoding")) {
                    shellContext.setEncoding(args[++argIndex]);
                } else if (args[argIndex].equals("-i")) {
                    shellContext.setInteractive(true);
                } else if (args[argIndex].startsWith("-")) {
                    printUsage();
                    return false;
                } else {
                    break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            printUsage();
            return false;
        }

        if (argIndex < args.length) {
            filename = args[argIndex++];
        }

        String[] arguments = new String[args.length - argIndex];
        System.arraycopy(args, argIndex, arguments, 0, arguments.length);
        shellContext.setArguments(arguments);

        if (filename == null && script == null) {
            shellContext.setInteractive(true);
        }

        return true;
    }

    private void printUsage() {
        System.err.println(_T(ELITE_USAGE));
    }

    private void repl(ScriptEngine engine) throws IOException {
        ELContext elctx = (ELContext)engine.get(ELContext.class.getName());

        ConsoleReader console = new ConsoleReader(System.in, System.out);
        console.setCompletor(new ELiteCompletor(elctx, engine));

        String buffer = null;
        int lineno = 1;

        while (true) {
            if (shellContext.isCompleted()) break;

            String prompt = (lineno == 1) ? "> " : (lineno+") ");
            String line = console.readLine(prompt);
            if (line == null) break;

            if (lineno == 1) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (exec_cmd(shellContext, line)) continue;
                buffer = line;
            } else {
                if (line.length() == 0) { buffer = null; lineno = 1; continue; }
                else buffer += "\n" + line;
            }

            if (buffer.endsWith("\\")) {
                buffer = buffer.substring(0, buffer.length()-1);
                lineno++;
                continue;
            }
            
            try {
                Object value = engine.eval(buffer);
                engine.put("_", value);
                if (value != null) {
                    StackTrace.addFrame(elctx, "__toplevel__", null, Position.make(1,1));
                    try { Builtin.print(elctx, value); }
                    finally { StackTrace.removeFrame(elctx); }
                }
            } catch (ScriptException ex) {
                if (ex.getCause() instanceof IncompleteException) { lineno++; continue; }
                else System.err.println(hilight(ex.getMessage()));
            } catch (Exception ex) { printStackTrace(ex); }
            catch (Error ex) { printStackTrace(ex); }

            buffer = null;
            lineno = 1;
        }
        console.close();
    }

    private static String hilight(String text) {
        // Use ANSI red color codes directly
        return "\033[31m" + text + "\033[0m";
    }

    private void printStackTrace(Throwable except) {
        except.printStackTrace(System.err);
    }

    private int exec_script(ScriptEngine engine, String script) {
        try {
            Object value = engine.eval(script);
            if (value != null) {
                ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
                Builtin.print(elctx, value);
            }
        } catch (ScriptException ex) {
            System.err.println(ex.getMessage());
            return 1;
        }

        return 0;
    }

    private ScriptEngine createScriptEngine(String[] args) {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("ELite");

        engine.put(ScriptEngine.ARGV, args);
        engine.put("env", System.getenv());
        engine.put("endl", System.getProperty("line.separator"));

        return engine;
    }

    // Shell Commands

    private static Map<String, Method> commands = new HashMap<String, Method>();
    static {
        for (Method method : CommandProvider.class.getMethods()) {
            if (Modifier.isPublic(method.getModifiers()) &&
                Modifier.isStatic(method.getModifiers()) &&
                !"main".equals(method.getName())) {
                Command meta = method.getAnnotation(Command.class);
                String key = meta == null ? method.getName() : meta.value();
                commands.put(key, method);
            }
        }
    }

    private static boolean exec_cmd(ShellContext shellContext, String cmdline) {
        int sp = cmdline.indexOf(' ');
        if (sp == -1) sp = cmdline.length();
        String tok = cmdline.substring(0, sp);
        String args = cmdline.substring(sp).trim();

        if (tok.equals("main")) {
            return false;
        }

        Method cmd = commands.get(tok);
        if (cmd != null) {
            try {
                cmd.invoke(null, shellContext, args);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return true;
        }

        return false;
    }
}
