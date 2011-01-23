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

package org.operamasks.el.shell;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.operamasks.el.shell.command.Command;
import org.operamasks.el.shell.command.CommandProvider;
import org.operamasks.el.parser.IncompleteException;
import org.operamasks.el.parser.Position;
import org.operamasks.el.eval.StackTrace;
import elite.lang.Builtin;

import jline.ConsoleReader;
import jline.Terminal;
import jline.ANSIBuffer;

// Experimental
public class Main
{
    private static final String[] WELCOME = {
        "Welcome to ELite (Version 0.5.0)",
        "Copyright (c) 2006-2011 Daniel Yuan.",
        "ELite comes with ABSOLUTELY NO WARRANTY. This is free software,",
        "and you are welcome to redistribute it under certain conditions.",
        ""
    };

    private static final String USAGE[] = {
        "usage: elite [options] [args]",
        "options:",
        "  -e <script>        specify a command line script",
        "  -c <encoding>      specify the encoding of files",
        "  -i                 interactive mode",
        "  -h                 print this usage information"
    };

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
            for (String s : WELCOME) {
                System.out.println(s);
            }
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
        for (String s : USAGE) {
            System.err.println(s);
        }
    }

    private void repl(ScriptEngine engine) throws IOException {
        ELContext elctx = (ELContext)engine.get(ELContext.class.getName());

        ConsoleReader console = new ConsoleReader(System.in, new PrintWriter(System.out));
        console.addCompletor(new VariableCompletor(elctx, engine));
        String buffer = null;
        int lineno = 1;

        while (true) {
            if (shellContext.isCompleted()) {
                break;
            }

            String prompt = (lineno == 1) ? "> " : (lineno+") ");
            String line = console.readLine(prompt);
            if (line == null) {
                break;
            }

            if (lineno == 1) {
                // the first line
                line = line.trim();
                if (line.length() == 0)
                    continue;
                if (exec_cmd(shellContext, line))
                    continue;
                buffer = line;
            } else {
                // the continuation line
                if (line.length() == 0) {
                    buffer = null;
                    lineno = 1;
                    continue;
                } else {
                    buffer += "\n" + line;
                }
            }

            if (buffer.endsWith("\\")) {
                // continuation line
                buffer = buffer.substring(0, buffer.length()-1);
                lineno++;
                continue;
            }
            
            try {
                Object value = engine.eval(buffer);
                engine.put("_", value);
                if (value != null) {
                    StackTrace.addFrame(elctx, "__toplevel__", null, Position.make(1,1));
                    try {
                        Builtin.print(elctx, value);
                    } finally {
                        StackTrace.removeFrame(elctx);
                    }
                }
            } catch (ScriptException ex) {
                if (ex.getCause() instanceof IncompleteException) {
                    lineno++;
                    continue;
                } else {
                    System.err.println(hilight(ex.getMessage()));
                }
            } catch (Exception ex) {
                printStackTrace(ex);
            } catch (Error ex) {
                printStackTrace(ex);
            }

            buffer = null;
            lineno = 1;
        }
    }

    private static String hilight(String text) {
        if (Terminal.getTerminal().isANSISupported()) {
            ANSIBuffer ansi = new ANSIBuffer();
            ansi.red(text);
            return ansi.toString(true);
        } else {
            return text;
        }
    }

    private void printStackTrace(Throwable except) {
        if (Terminal.getTerminal().isANSISupported()) {
            StringWriter writer = new StringWriter();
            except.printStackTrace(new PrintWriter(writer));
            System.err.println(hilight(writer.toString()));
        } else {
            except.printStackTrace(System.err);
        }
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
