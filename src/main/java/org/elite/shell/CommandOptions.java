package org.elite.shell;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import java.util.ArrayList;
import java.util.List;

class CommandOptions {
    @Parameter(names = {"--help", "-h"},
               description = "print this usage information", help = true)
    public boolean help = false;

    @Parameter(names = "-i", description = "interactive mode")
    public boolean interactive = false;

    @Parameter(names = "-e", description = "evaluate the expression")
    public String script = null;

    @Parameter(names = {"--encoding", "-c"},
               description = "specify the encoding of script files")
    public String encoding = null;

    @Parameter(names = "--dump-ast", description = "dump program AST")
    public boolean dumpAST = false;

    @Parameter(names = "--dump-ir", description = "dump program IR")
    public boolean dumpIR = false;

    @Parameter(names = "--dump-bc", description = "dump generated Java bytecode")
    public boolean dumpBC = false;

    @Parameter(names = "-O", description = "optimization level",
               validateWith = OptLevelValidator.class)
    public int optLevel = 2;

    @Parameter(names = "--debug", description = "debug mode")
    public boolean debug = false;

    @Parameter(variableArity = true)
    public List<String> args = new ArrayList<>();

    public boolean hasDump() {
        return dumpAST || dumpIR || dumpBC;
    }

    public static class OptLevelValidator implements IParameterValidator {
        @Override
        public void validate(String name, String value)
            throws ParameterException
        {
            boolean valid;
            try {
                int level = Integer.parseInt(value);
                valid = level >= 0 && level <= 3;
            } catch (NumberFormatException ex) {
                valid = false;
            }

            if (!valid) {
                throw new ParameterException("optimization level can only be 0, 1, 2, 3, " +
                                             "the current value is " + value);
            }
        }
    }
}
