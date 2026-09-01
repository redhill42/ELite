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

import elite.lang.Builtin;
import org.elite.eval.StackTrace;
import org.elite.parser.IncompleteException;
import org.elite.parser.ParseException;
import org.elite.parser.Position;
import org.elite.shell.command.Command;
import org.elite.shell.command.CommandProvider;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.elite.resources.Resources.*;

public class Main {
  private final ShellContext shellContext;
  private CommandOptions options;
  private String filename;

  private static final Path HISTORY_FILE = Path.of(
    System.getProperty("user.home"), ".elite_history");

  public Main() {
    this.shellContext = new ShellContext();
  }

  public static void main(String[] args) {
    Main main = new Main();
    int status = main.run(args);
    if (status != 0) {
      System.exit(status);
    }
  }

  public int run(String[] args) {
    int status = parseOptions(args);
    if (status != PROCEED) {
      return status;
    }

    if (shellContext.isInteractive()) {
      System.out.println(_T(ELITE_WELCOME));
    }

    try {
      ScriptEngine engine = createScriptEngine(shellContext.getArguments());
      shellContext.setEngine(engine);
      int execStatus = 0;

      if (!shellContext.isInteractive())
        engine.put("elite.standalone", true);

      if (filename != null) {
        if (options.hasDump()) {
          String source = new String(Files.readAllBytes(Paths.get(filename)));
          dumpWithFlags(source);
          return 0;
        }
        execStatus = CommandProvider.exec(shellContext, filename);
      } else if (options.script != null) {
        if (options.hasDump()) {
          dumpWithFlags(options.script);
          return 0;
        }
        execStatus = exec_script(engine, options.script);
      }

      if (execStatus != 0) {
        return execStatus;
      }

      if (shellContext.isInteractive()) {
        engine.put("elite.interactive", true);
        repl(engine);
      }
    } catch (IOException ex) {
      System.err.println(ex.getMessage());
      return 1;
    }

    return 0;
  }

  // Return values of parseOptions: an exit status, or PROCEED to run the shell.
  private static final int PROCEED = -1;
  private static final int EXIT_OK = 0;
  private static final int EXIT_ERROR = 1;

  private int parseOptions(String[] args) {
    try {
      options = Cli.parse(args);
    } catch (CliException ex) {
      System.err.println(ex.getMessage());
      System.err.println();
      Cli.printUsage(System.err);
      return EXIT_ERROR;
    }

    if (options == null) {   // --help/-h
      Cli.printUsage(System.out);
      return EXIT_OK;
    }

    if (options.version) {
      System.out.println(_T(ELITE_VERSION));
      return EXIT_OK;
    }

    if (options.encoding != null)
      shellContext.setEncoding(options.encoding);
    if (options.interactive)
      shellContext.setInteractive(true);
    if (options.debug)
      System.setProperty("elite.debug", "true");
    System.setProperty("elite.opt.level", String.valueOf(options.optLevel));

    if (options.args.size() != 0) {
      filename = options.args.get(0);
      options.args.remove(0);
      shellContext.setArguments(options.args.toArray(new String[0]));
    }

    if (filename == null && options.script == null) {
      shellContext.setInteractive(true);
    }

    return PROCEED;
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

      String prompt = (lineno == 1) ? "> " : (lineno + ") ");
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
        buffer = buffer.substring(0, buffer.length() - 1);
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
        } else if (ex.getCause() instanceof ParseException) {
          terminal.writer().println(new AttributedString(
            ex.getCause().getMessage(),
            AttributedStyle.DEFAULT.foreground(AttributedStyle.RED)).toAnsi());
        } else {
          printStackTrace(ex);
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

  private static final String DUMP_SEPARATOR = "\n" + "═".repeat(60) + "\n";

  private void dumpWithFlags(String source) throws IOException {
    int count = 0;
    if (options.dumpAST)
      count++;
    if (options.dumpIR)
      count++;
    if (options.dumpBC)
      count++;

    int emitted = 0;
    if (options.dumpAST) {
      CommandProvider.dump_ast(shellContext, source);
      if (++emitted < count)
        System.out.print(DUMP_SEPARATOR);
    }
    if (options.dumpIR) {
      CommandProvider.dump(shellContext, source);
      if (++emitted < count)
        System.out.print(DUMP_SEPARATOR);
    }
    if (options.dumpBC) {
      CommandProvider.dump_bc(shellContext, source);
      if (++emitted < count)
        System.out.print(DUMP_SEPARATOR);
    }
  }

  private ScriptEngine createScriptEngine(String[] args) {
    ScriptEngineManager manager = new ScriptEngineManager();
    ScriptEngine engine = manager.getEngineByName("ELite");
    engine.put(ScriptEngine.ARGV, args);
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
    if (sp == -1)
      sp = cmdline.length();
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
