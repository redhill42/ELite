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
package org.operamasks.el.shell.command;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Set;
import java.util.TreeSet;

import javax.el.ELContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.operamasks.el.eval.VariableMapperImpl;
import org.operamasks.el.resolver.MethodResolver;
import org.operamasks.el.shell.ShellContext;

import elite.lang.Builtin;

public final class CommandProvider {
    private static final String COMMANDS[] = {
        "?                       print this usage information",
        "@ <filename>            specify a file to execute",
        "ls [x|a]                list system|global methods and all variable's key",
        "quit                    quit shell",
        "which <classname>       find out the absolute path of specify classname"
    };

    // 本来是要做成在类上标注@CommandProvider更容易扩展，后来考虑到没有好的方式进行预扫描，暂时采用这种方式。
    @Command("?")
    public static void help(ShellContext shellContext, String args) {
        for (String s : COMMANDS) {
            System.out.println(s);
        }
    }

    @Command("@")
    public static int exec(ShellContext shellContext, String filename) {
        if (filename == null || filename.length() == 0) {
            System.err.println("file name is null!");
            return 1;
        }
        try {
            ScriptEngine engine = shellContext.getEngine();
            String text = readFile(filename, shellContext.getEncoding());
            engine.put(ScriptEngine.FILENAME, filename);
            engine.eval(text);
        } catch (ScriptException ex) {
            System.err.println(ex.getMessage());
            return 1;
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
            return 1;
        }

        return 0;
    }

    public static void quit(ShellContext shellContext, String args) {
        System.out.println("Bye!");
        shellContext.setCompleted(true);
    }

    public static void ls(ShellContext shellContext, String args) {
        ScriptEngine engine = shellContext.getEngine();
        ELContext elctx = (ELContext) engine.get(ELContext.class.getName());
        Set<String> lst = new TreeSet<String>();

        if (args.indexOf('x') != -1) {
            MethodResolver mr = MethodResolver.getInstance(elctx);
            lst.addAll(mr.listSystemMethods());
        }

        if (args.indexOf('a') != -1) {
            MethodResolver mr = MethodResolver.getInstance(elctx);
            lst.addAll(mr.listGlobalMethods());
        }

        VariableMapperImpl vm = (VariableMapperImpl) elctx.getVariableMapper();
        lst.addAll(vm.getVariableMap().keySet());

        Builtin.print(elctx, lst);
    }

    public static void which(ShellContext shellContext, String args) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        String resname = args.replace('.', '/') + ".class";
        URL res = loader.getResource(resname);
        if (res != null) {
            System.out.println(res.toString());
        } else {
            System.err.println(args + ": not found");
        }
    }

    private static String readFile(String path, String encoding) throws IOException {
        InputStream stream = new FileInputStream(path);
        Reader reader = (encoding != null) ? new InputStreamReader(stream, encoding) : new InputStreamReader(stream);

        StringBuilder buf = new StringBuilder();
        char[] cbuf = new char[8192];
        for (int len; (len = reader.read(cbuf)) != -1;) {
            buf.append(cbuf, 0, len);
        }
        reader.close();
        return buf.toString();
    }
}
