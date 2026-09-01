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
import org.apache.commons.cli.DefaultParser.NonOptionAction;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;

// Command line parsing for the elite shell, backed by Apache Commons CLI.
class Cli {
    // --dump-* are hidden: parsed by ALL but not shown in the usage text.
    private static final Options ALL = buildOptions(true);
    private static final Options VISIBLE = buildOptions(false);

    /**
     * Parses the command line. Returns the parsed options, or null when
     * --help/-h was given (the caller prints the usage and exits 0).
     *
     * @throws CliException if the command line is invalid
     */
    static CommandOptions parse(String[] args) throws CliException {
        CommandLine cmd;
        try {
            // Stop option parsing at the first non-option token (the script
            // file); everything after it is passed through to the script.
            cmd = new DefaultParser().parse(ALL, null, NonOptionAction.STOP, args);
        } catch (ParseException ex) {
            throw new CliException(ex.getMessage());
        }

        if (cmd.hasOption("help")) {
            return null;
        }

        CommandOptions options = new CommandOptions();
        options.interactive = cmd.hasOption("i");
        options.version = cmd.hasOption("version");
        options.script = cmd.getOptionValue("e");
        options.encoding = cmd.getOptionValue("c");
        options.dumpAST = cmd.hasOption("dump-ast");
        options.dumpIR = cmd.hasOption("dump-ir");
        options.dumpBC = cmd.hasOption("dump-bc");
        options.optLevel = optLevel(cmd);
        options.debug = cmd.hasOption("debug");
        options.args = new ArrayList<>(cmd.getArgList());
        return options;
    }

    static void printUsage(PrintStream out) {
        new HelpFormatter().printHelp(new PrintWriter(out, true), 80,
          "elite [options] [<script-file> [args...]]", null, VISIBLE, 1, 3, null);
    }

    private static int optLevel(CommandLine cmd) throws CliException {
        if (!cmd.hasOption("O")) {
            return 2;   // default when -O is absent
        }

        String value = cmd.getOptionValue("O");
        if (value == null) {
            return 2;
        }

        try {
            int level = Integer.parseInt(value);
            if (level >= 0 && level <= 3) {
                return level;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the error below
        }

        throw new CliException("optimization level can only be 0, 1, 2, 3, " +
                               "the current value is " + value);
    }

    private static Options buildOptions(boolean includeDump) {
        Options opts = new Options();
        opts.addOption(Option.builder("h").longOpt("help")
          .desc("print this usage information").build());
        opts.addOption(Option.builder().longOpt("version")
          .desc("print version information").build());
        opts.addOption(Option.builder("i").longOpt("interactive")
          .desc("interactive mode").build());
        opts.addOption(Option.builder("e").hasArg().argName("script")
          .desc("evaluate the expression").build());
        opts.addOption(Option.builder("c").longOpt("encoding").hasArg()
          .argName("encoding")
          .desc("specify the encoding of script files").build());
        if (includeDump) {
            opts.addOption(Option.builder().longOpt("dump-ast")
              .desc("dump program AST").build());
            opts.addOption(Option.builder().longOpt("dump-ir")
              .desc("dump program IR").build());
            opts.addOption(Option.builder().longOpt("dump-bc")
              .desc("dump bytecode").build());
        }
        // optionalArg: bare -O is legal (GCC: -O == -O1);
        // -O2 and -O 2 both bind the value to the option.
        opts.addOption(Option.builder("O").hasArg().optionalArg(true)
          .argName("level").desc("optimization level").build());
        opts.addOption(Option.builder().longOpt("debug")
          .desc("debug mode").build());
        return opts;
    }
}

// Thrown when the command line is invalid; the message is user-facing.
class CliException extends Exception {
    CliException(String message) {
        super(message);
    }
}
