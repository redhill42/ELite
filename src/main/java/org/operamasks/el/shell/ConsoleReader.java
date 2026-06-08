package org.operamasks.el.shell;

import java.io.*;
import java.util.*;

/**
 * Minimal line reader for the ELite REPL.
 * Uses stty -icanon -echo min 1 onlcr for raw input with clean output.
 */
class ConsoleReader implements AutoCloseable {

    private final InputStream in;
    private final PrintStream out;
    private final List<String> history = new ArrayList<>();
    private int histIdx = -1;
    private String savedLine = "";
    private boolean cbreak;
    private String[] sttyRestore;
    private Completor completor;

    public ConsoleReader(InputStream in, PrintStream out) {
        this.in = in; this.out = out;
        enableCbreak();
    }

    public void setCompletor(Completor c) { this.completor = c; }

    private void enableCbreak() {
        try {
            Process p = new ProcessBuilder("stty", "-g")
                .redirectInput(ProcessBuilder.Redirect.INHERIT).start();
            String s = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            if (s != null) {
                sttyRestore = new String[]{"stty", s};
                new ProcessBuilder("stty", "-icanon", "-echo", "min", "1", "onlcr")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT).start().waitFor();
                cbreak = true;
            }
        } catch (Exception e) { /* fallback */ }
    }

    @Override public void close() {
        if (cbreak && sttyRestore != null) try {
            new ProcessBuilder(sttyRestore)
                .redirectInput(ProcessBuilder.Redirect.INHERIT).start().waitFor();
        } catch (Exception e) {}
    }

    public String readLine(String prompt) throws IOException {
        out.print(prompt); out.flush();
        StringBuilder buf = new StringBuilder();
        int cur = 0;
        histIdx = -1;

        for (;;) {
            int ch = in.read();
            if (ch < 0) return null;

            if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && look() == '\n') in.read();
                out.println(); break;
            }
            if (ch == 4 && buf.length() == 0) { out.println(); return null; }

            if (ch == 9 && completor != null) {  // TAB
                doComplete(prompt, buf, cur);
                continue;
            }

            if (ch == 127 || ch == 8) {           // Backspace
                if (cur > 0) { buf.deleteCharAt(--cur); redraw(prompt, buf, cur); }
                continue;
            }

            if (ch == 27 && look() == '[') {      // Arrow keys
                in.read(); int d = in.read();
                if (d == 'A' || d == 'B') histNav(d == 'A' ? -1 : 1, buf);
                else if (d == 'C' && cur < buf.length()) cur++;
                else if (d == 'D' && cur > 0) cur--;
                if (d >= 'A' && d <= 'D') redraw(prompt, buf, cur);
                continue;
            }

            if (ch >= 32 && ch < 127) {
                buf.insert(cur++, (char)ch);
                redraw(prompt, buf, cur);
            }
        }

        String line = buf.toString();
        if (!line.isEmpty() && (history.isEmpty()
                || !history.get(history.size()-1).equals(line)))
            history.add(line);
        return line;
    }

    private void doComplete(String prompt, StringBuilder buf, int cur) {
        String prefix = wordBeforeCursor(buf, cur);
        if (prefix.isEmpty()) return;

        List<String> matches = completor.complete(buf.toString(), cur);
        if (matches == null || matches.isEmpty()) return;

        if (matches.size() == 1) {
            String completion = matches.get(0).substring(prefix.length());
            for (char c : completion.toCharArray()) buf.insert(cur++, c);
            redraw(prompt, buf, cur);
        } else {
            out.println();
            for (String m : matches) { out.print(m); out.print("  "); }
            out.println();
            redraw(prompt, buf, cur);
        }
    }

    private static String wordBeforeCursor(StringBuilder buf, int cur) {
        int start = cur;
        while (start > 0 && Character.isJavaIdentifierPart(buf.charAt(start-1)))
            start--;
        return buf.substring(start, cur);
    }

    private void redraw(String prompt, StringBuilder buf, int cur) {
        out.print('\r');
        out.print("\033[K");
        out.print(prompt);
        out.print(buf);
        if (cur < buf.length())
            out.print("\033[" + (buf.length() - cur) + "D");
        out.flush();
    }

    private void histNav(int dir, StringBuilder buf) {
        if (history.isEmpty()) return;
        if (dir < 0 && histIdx == -1) { savedLine = buf.toString(); histIdx = history.size()-1; }
        else if (dir < 0 && histIdx > 0) histIdx--;
        else if (dir > 0 && histIdx < history.size()-1) histIdx++;
        else if (dir > 0) { histIdx = -1; buf.replace(0, buf.length(), savedLine); return; }
        else return;
        buf.replace(0, buf.length(), history.get(histIdx));
    }

    private int look() throws IOException { in.mark(1); int c = in.read(); if (c>=0) in.reset(); return c; }

    @FunctionalInterface
    interface Completor {
        List<String> complete(String line, int cursor);
    }
}
