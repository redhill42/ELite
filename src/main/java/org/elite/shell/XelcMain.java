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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.ir.BytecodeCompiler;
import org.elite.ir.BytecodeConsumer;
import org.elite.parser.ParseException;
import org.elite.parser.Parser;
import javax.el.ELContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ELite compiler driver — compiles an .xel source file to a standalone
 * Java class.
 *
 * <h3>Usage</h3>
 * <pre>
 * xelc [options] &lt;source-file&gt;
 *
 * Options:
 *   -C &lt;pkg.ClassName&gt;   Fully qualified output class name
 *                        (default: derived from source file name)
 *   -d &lt;output-dir&gt;      Output directory for .class files
 *                        (default: current directory)
 *   --help, -h           Show this message
 * </pre>
 */
public final class XelcMain {

  private XelcMain() {}

  private static final Options OPTIONS = buildOptions();

  private static Options buildOptions() {
    Options opts = new Options();
    opts.addOption(Option.builder("C").hasArg().argName("class-name")
      .desc("fully qualified output class name" +
            " (default: derived from source file name)").build());
    opts.addOption(Option.builder("d").hasArg().argName("output-dir")
      .desc("output directory for .class files (default: current directory)")
      .build());
    opts.addOption(Option.builder("h").longOpt("help")
      .desc("show this message").build());
    return opts;
  }

  private static void printUsage(PrintStream out) {
    new HelpFormatter().printHelp(new PrintWriter(out, true), 80,
      "xelc [options] <source-file>", null, OPTIONS, 1, 3, null);
  }

  public static void main(String[] args) {
    CommandLine cmd;
    try {
      cmd = new DefaultParser().parse(OPTIONS, args);
    } catch (org.apache.commons.cli.ParseException ex) {
      System.err.println("xelc: " + ex.getMessage());
      System.err.println();
      printUsage(System.err);
      System.exit(1);
      return;
    }

    if (cmd.hasOption("h")) {
      printUsage(System.out);
      return;
    }

    String outputDir = cmd.getOptionValue("d", ".");
    String className = cmd.getOptionValue("C");
    String sourceFile = cmd.getArgList().isEmpty()
      ? null : cmd.getArgList().get(0);

    if (sourceFile == null) {
      System.err.println("xelc: Missing source file");
      System.err.println();
      printUsage(System.err);
      System.exit(1);
      return;
    }

    // Read source file.
    String source;
    try {
      source = new String(Files.readAllBytes(Path.of(sourceFile)));
    } catch (IOException e) {
      System.err.println(
        "xelc: cannot read " + sourceFile + ": " + e.getMessage());
      System.exit(1);
      return;
    }

    // Derive class name from file name if not specified.
    if (className == null) {
      String base = sourceFile;
      int sep = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
      if (sep >= 0)
        base = base.substring(sep + 1);
      if (base.endsWith(".xel"))
        base = base.substring(0, base.length() - 4);
      else if (base.endsWith(".elite"))
        base = base.substring(0, base.length() - 6);
      // Sanitize to valid Java identifier.
      className = toJavaIdentifier(base);
    }

    // Compile.
    try {
      ELContext elctx = ELEngine.createELContext();
      Parser parser = new Parser(elctx, source);
      parser.setFileName(sourceFile);
      ELProgram prog = parser.parse();
      prog.setStandalone(true);
      prog.setFilename(sourceFile);

      BytecodeConsumer consumer = new AOTBytecodeConsumer(outputDir);
      BytecodeCompiler.compile(prog.compile(elctx), className, consumer,
                               prog.getImports());
    } catch (ParseException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }

  private static String toJavaIdentifier(String name) {
    StringBuilder sb = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char ch = name.charAt(i);
      if (i == 0 ? Character.isJavaIdentifierStart(ch)
                 : Character.isJavaIdentifierPart(ch)) {
        sb.append(ch);
      } else {
        sb.append('_');
      }
    }
    return sb.length() > 0 ? sb.toString() : "Program";
  }


  /**
   * Writes compiled bytecode to .class files on disk.
   */
  private static class AOTBytecodeConsumer implements BytecodeConsumer {

    private final String outputDir;

    AOTBytecodeConsumer(String outputDir) {
      this.outputDir = outputDir;
    }

    @Override
    public void acceptProgram(String className, byte[] bytecode) {
      writeClassFile(className, bytecode);
    }

    @Override
    public void acceptClass(String className, byte[] bytecode) {
      writeClassFile(className, bytecode);
    }

    private void writeClassFile(String className, byte[] bc) {
      String path = outputDir + "/" + className.replace('.', '/') + ".class";
      File file = new File(path);
      file.getParentFile().mkdirs();
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(bc);
      } catch (IOException e) {
        throw new RuntimeException("Failed to write " + path, e);
      }
    }
  }
}
