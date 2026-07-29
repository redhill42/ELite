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
package org.elite.shell.command;

import elite.lang.Builtin;
import org.elite.eval.ELProgram;
import org.elite.eval.VariableMapperImpl;
import org.elite.ir.BytecodeCompiler;
import org.elite.ir.BytecodeConsumer;
import org.elite.ir.SymbolTableBuilder;
import org.elite.parser.Parser;
import org.elite.resolver.MethodResolver;
import org.elite.shell.ShellContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URL;
import java.util.Set;
import java.util.TreeSet;

@SuppressWarnings("unused")
public final class CommandProvider {
  private static final String[] COMMANDS = {
    "?                       print this usage information",
    "@ <filename>            specify a file to execute",
    "ls [x|a]                list system|global methods and all variable's key",
    "quit                    quit shell",
    "which <classname>       find out the absolute path of specify classname"
  };

  @Command("?")
  public static void help(ShellContext shellContext, String args) {
    for (String s : COMMANDS) {
      System.out.println(s);
    }
  }

  @Command("@")
  public static int exec(ShellContext shellContext, String filename) {
    if (filename == null || filename.isEmpty()) {
      System.err.println("file name is null!");
      return 1;
    }
    try {
      ScriptEngine engine = shellContext.getEngine();
      String text = readFile(filename, shellContext.getEncoding());
      engine.put(ScriptEngine.FILENAME, filename);
      engine.eval(text);
    } catch (ScriptException | IOException ex) {
      System.err.println(ex.getMessage());
      return 1;
    }

    return 0;
  }

  public static void quit(ShellContext shellContext, String args) {
    System.out.println("Bye!");
    shellContext.setCompleted(true);
  }

  @Command("dump-ast")
  public static void dump_ast(ShellContext shellContext, String script) {
    if (script.isBlank())
      script = shellContext.getLastScript();
    if (script.isBlank())
      return;
    ScriptEngine engine = shellContext.getEngine();
    ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
    Parser parser = new Parser(elctx, script);
    ELProgram program = parser.parse();
    program.setStandalone(true);
    SymbolTableBuilder.build(program);
    System.out.println(program.dump());
  }

  public static void dump(ShellContext shellContext, String script) {
    if (script.isBlank())
      script = shellContext.getLastScript();
    if (script.isBlank())
      return;
    ScriptEngine engine = shellContext.getEngine();
    ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
    ELProgram program = new Parser(elctx, script).parse();
    program.setStandalone(true);
    System.out.println(program.compile(elctx).dump());
  }

  @Command("dump-bc")
  public static void dump_bc(ShellContext shellContext, String script) {
    if (script.isBlank())
      script = shellContext.getLastScript();
    if (script.isBlank())
      return;
    ScriptEngine engine = shellContext.getEngine();
    ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
    ELProgram program = new Parser(elctx, script).parse();
    program.setStandalone(true);
    BytecodeCompiler.compile(
      program.compile(elctx), "ELiteProgram", new Dumper(),
      shellContext.isInteractive() ? null : program.getImports());
  }

  private static class Dumper implements BytecodeConsumer {
    @Override
    public void acceptProgram(String name, byte[] bc) {
      dumpBytecode(bc);
    }

    @Override
    public void acceptClass(String name, byte[] bc) {
      dumpBytecode(bc);
    }

    private void dumpBytecode(byte[] bc) {
      ClassReader cr = new ClassReader(bc);
      TraceClassVisitor trace = new TraceClassVisitor(
        null, new CustomTextifier(), new PrintWriter(System.out));
      cr.accept(trace, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
  }

  private static class CustomTextifier extends Textifier {
    CustomTextifier() {
      super(Opcodes.ASM9);
    }

    @Override
    protected Textifier createTextifier() {
      return new CustomTextifier();
    }

    @Override
    public void visitLabel(final Label label) {
      stringBuilder.setLength(0);
      stringBuilder.append(tab);
      appendLabel(label);
      stringBuilder.append(":\n");
      text.add(stringBuilder.toString());
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
      // Do nothing.
    }

    static {
      // Lower case opcodes for better representation.
      for (int i = 0; i < Printer.OPCODES.length; i++) {
        Printer.OPCODES[i] = Printer.OPCODES[i].toLowerCase();
      }
    }
  }

  public static void ls(ShellContext shellContext, String args) {
    ScriptEngine engine = shellContext.getEngine();
    ELContext elctx = (ELContext)engine.get(ELContext.class.getName());
    Set<String> lst = new TreeSet<>();

    if (args.indexOf('x') != -1) {
      MethodResolver mr = MethodResolver.getInstance(elctx);
      lst.addAll(mr.listSystemMethods());
    }

    if (args.indexOf('a') != -1) {
      MethodResolver mr = MethodResolver.getInstance(elctx);
      lst.addAll(mr.listGlobalMethods());
    }

    VariableMapperImpl vm = (VariableMapperImpl)elctx.getVariableMapper();
    lst.addAll(vm.getVariableMap().keySet());

    Builtin.print(elctx, lst);
  }

  public static void which(ShellContext shellContext, String args) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    String resname = args.replace('.', '/') + ".class";
    URL res = loader.getResource(resname);
    if (res != null) {
      System.out.println(res);
    } else {
      System.err.println(args + ": not found");
    }
  }

  private static String readFile(String path, String encoding)
    throws IOException
  {
    InputStream stream = new FileInputStream(path);
    Reader reader = (encoding != null) ? new InputStreamReader(stream, encoding)
                                       : new InputStreamReader(stream);

    StringBuilder buf = new StringBuilder();
    char[] cbuf = new char[8192];
    for (int len; (len = reader.read(cbuf)) != -1; ) {
      buf.append(cbuf, 0, len);
    }
    reader.close();
    return buf.toString();
  }
}
