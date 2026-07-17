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

import org.elite.eval.ELEngine;
import org.elite.eval.ELProgram;
import org.elite.ir.BytecodeCompiler;
import org.elite.ir.BytecodeConsumer;
import org.elite.parser.Parser;
import javax.el.ELContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

  public static void main(String[] args) {
    String outputDir = ".";
    String className = null;
    String sourceFile = null;

    // Parse command line.
    int i = 0;
    while (i < args.length) {
      String arg = args[i];
      switch (arg) {
      case "-d":
        if (++i >= args.length) {
          usage("Missing argument for -d");
          return;
        }
        outputDir = args[i++];
        break;
      case "-C":
        if (++i >= args.length) {
          usage("Missing argument for -C");
          return;
        }
        className = args[i++];
        break;
      case "-h":
      case "--help":
        usage(null);
        return;
      default:
        if (arg.startsWith("-")) {
          usage("Unknown option: " + arg);
          return;
        }
        sourceFile = arg;
        i++;
        break;
      }
    }

    if (sourceFile == null) {
      usage("Missing source file");
      return;
    }

    // Read source file.
    String source;
    try {
      source = new String(Files.readAllBytes(Path.of(sourceFile)));
    } catch (java.io.IOException e) {
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
      base = toJavaIdentifier(base);
      className = "elite.program." + base;
    }

    // Compile.
    try {
      ELContext elctx = ELEngine.createELContext();
      Parser parser = new Parser(elctx, source);
      parser.setFileName(sourceFile);
      ELProgram prog = parser.parse();
      prog.setFilename(sourceFile);

      BytecodeConsumer consumer = new AOTBytecodeConsumer(outputDir);
      BytecodeCompiler.compile(prog.compile(elctx), className, consumer);
    } catch (Exception e) {
      System.err.println("xelc: " + e.getMessage());
      e.printStackTrace();
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

  private static void usage(String msg) {
    if (msg != null)
      System.err.println("xelc: " + msg);
    System.err.println(
      "Usage: xelc [options] <source-file>\n\nOptions:\n" +
      "  -C <pkg.ClassName>   Fully qualified output class name\n" +
      "  -d <output-dir>      Output directory for .class files (default: .)" +
      "\n" +
      "  -h, --help           Show this message\n");
    if (msg != null)
      System.exit(1);
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
    public void acceptClosure(String className, byte[] bytecode) {
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
