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

package org.elite.shell;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.*;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.elite.ir.SymbolTableBuilder;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import org.elite.eval.ELProgram;
import org.elite.shell.command.Command;
import org.elite.shell.command.CommandProvider;
import org.elite.parser.IncompleteException;
import org.elite.parser.Position;
import org.elite.parser.Parser;
import org.elite.ir.IRPrinter;
import org.elite.parser.ASTDumper;
import org.elite.eval.StackTrace;
import static org.elite.resources.Resources.*;
import elite.lang.Builtin;

// Experimental
public class Main
{
    private ShellContext shellContext;
    private String script;
    private String filename;
    private boolean dumpIR = false;
    private boolean dumpAST = false;
    private boolean dumpBC  = false;

    private static final Path HISTORY_FILE = Path.of(
        System.getProperty("user.home"), ".elite_history");

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
                if (dumpAST || dumpIR || dumpBC) {
                    String source = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(filename)));
                    ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
                    dumpWithFlags(elctx, source);
                    return 0;
                }
                status = CommandProvider.exec(shellContext, filename);
            } else if (script != null) {
                if (dumpAST || dumpIR || dumpBC) {
                    ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
                    dumpWithFlags(elctx, script);
                    return 0;
                }
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
                } else if (args[argIndex].equals("--debug")) {
                    System.setProperty("elite.debug", "true");
                } else if (args[argIndex].equals("--dump-ir")) {
                    dumpIR = true;
                } else if (args[argIndex].equals("--dump-bc")) {
                    dumpBC = true;
                } else if (args[argIndex].equals("--dump-ast")) {
                    dumpAST = true;
                } else if (args[argIndex].startsWith("-O")) {
                    String level = args[argIndex].substring(2);
                    if (level.matches("[0-3]")) {
                        System.setProperty("elite.opt.level", level);
                    } else {
                        System.err.println("Invalid optimization level: " + args[argIndex] + " (use -O0, -O1, -O2, -O3)");
                        printUsage();
                        return false;
                    }
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

        // Build JLine terminal and line reader
        Terminal terminal = TerminalBuilder.builder()
            .name("ELite")
            .encoding(shellContext.getEncoding() != null
                ? shellContext.getEncoding() : "UTF-8")
            .build();

        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(new VariableCompletor(elctx, engine))
            .variable(LineReader.HISTORY_FILE, HISTORY_FILE)
            .variable(LineReader.HISTORY_FILE_SIZE, 1000)
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%P  ")
            .option(LineReader.Option.CASE_INSENSITIVE, false)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build();

        reader.setVariable(LineReader.INDENTATION, 0);

        String buffer = null;
        int lineno = 1;

        while (true) {
            if (shellContext.isCompleted())
                break;

            String prompt = (lineno == 1) ? "> " : (lineno+") ");
            String line;
            try {
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                // Ctrl-C: discard buffer
                buffer = null;
                lineno = 1;
                continue;
            } catch (EndOfFileException e) {
                // Ctrl-D
                break;
            }
            if (line == null)
                break;  // EOF

            if (lineno == 1) {
                // the first line
                line = line.trim();
                if (line.isEmpty())
                    continue;
                if (exec_cmd(shellContext, line))
                    continue;
                buffer = line;
            } else {
                buffer += "\n" + line;
            }

            // Explicit line continuation via trailing backslash
            if (buffer.endsWith("\\")) {
                buffer = buffer.substring(0, buffer.length()-1);
                lineno++;
                continue;
            }

            try {
                shellContext.setLastScript(buffer);
                Object value = engine.eval(buffer);
                engine.put("_", value);
                if (value != null) {
                    StackTrace.addFrame(elctx, "__toplevel__", null, Position.make(1, 1));
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
                    terminal.writer().println(
                        new AttributedString(ex.getMessage(),
                            AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
                            .toAnsi());
                }
            } catch (Exception | Error ex) {
                printStackTrace(ex);
            }

            buffer = null;
            lineno = 1;
        }

        terminal.close();
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

    private static final String DUMP_SEPARATOR =
        "\n" + "═".repeat(60) + "\n";

    private void dumpWithFlags(ELContext elctx, String source) throws IOException {
        int count = 0;
        if (dumpAST) count++;
        if (dumpIR)  count++;
        if (dumpBC)  count++;

        int emitted = 0;
        if (dumpAST) {
            System.out.print(dumpAST(source));
            if (++emitted < count) System.out.print(DUMP_SEPARATOR);
        }
        if (dumpIR) {
            System.out.print(IRPrinter.dumpProgramIR(elctx, source));
            if (++emitted < count) System.out.print(DUMP_SEPARATOR);
        }
        if (dumpBC) {
            System.out.print(IRPrinter.dumpProgramBC(source));
            if (++emitted < count) System.out.print(DUMP_SEPARATOR);
        }
    }

    private static String dumpAST(String script) {
        Parser parser = new Parser(script);
        ELProgram program = parser.parse();
        SymbolTableBuilder.build(program);
        return ASTDumper.dump(program);
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

    private static final Map<String, Method> commands = new HashMap<>();
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
