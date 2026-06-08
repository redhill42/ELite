package org.operamasks.el.shell;

import java.io.*;
import java.util.*;

/**
 * Minimal line reader for the ELite REPL.
 * Uses stty cbreak mode on Unix — terminal echo is ON,
 * we only handle special keys (backspace, arrows, history).
 */
class ConsoleReader implements AutoCloseable {

    private final InputStream in;
    private final PrintStream out;
    private final List<String> history = new ArrayList<>();
    private int histIdx = -1;
    private String savedLine = "";
    private boolean cbreak;
    private String[] sttyRestore;

    public ConsoleReader(InputStream in, PrintStream out) {
        this.in = in; this.out = out;
        enableCbreak();
    }

    private void enableCbreak() {
        try {
            Process p = new ProcessBuilder("stty", "-g")
                .redirectInput(ProcessBuilder.Redirect.INHERIT).start();
            String s = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            if (s != null) {
                sttyRestore = new String[]{"stty", s};
                new ProcessBuilder("stty", "-icanon", "min", "1")
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
                out.println();
                break;
            }
            if (ch == 4 && buf.length() == 0) { out.println(); return null; }
            if (ch == 9) continue;                        // TAB — ignored for now

            if (ch == 127 || ch == 8) {                    // Backspace
                if (cur > 0) {
                    buf.deleteCharAt(--cur);
                    if (cur < buf.length()) redraw(prompt, buf, cur);
                    else { out.print("\b \b"); out.flush(); }
                }
                continue;
            }

            if (ch == 27 && look() == '[') {               // Arrow keys
                in.read(); int d = in.read();
                if (d == 'A' || d == 'B') histNav(d == 'A' ? -1 : 1, buf);
                else if (d == 'C' && cur < buf.length()) cur++;
                else if (d == 'D' && cur > 0) cur--;
                if (d == 'A' || d == 'B' || d == 'C' || d == 'D')
                    redraw(prompt, buf, cur);
                continue;
            }

            if (ch >= 32 && ch < 127) {
                if (cur == buf.length()) { buf.append((char)ch); cur++; }
                else { buf.insert(cur++, (char)ch); redraw(prompt, buf, cur); }
            }
        }

        String line = buf.toString();
        if (!line.isEmpty() && (history.isEmpty()
                || !history.get(history.size()-1).equals(line)))
            history.add(line);
        return line;
    }

    /** Clear line, redraw prompt+content, position cursor. */
    private void redraw(String prompt, StringBuilder buf, int cur) {
        out.print('\r');                                        // go to col 0
        out.print("\033[K");                                    // clear to end of line
        out.print(prompt);                                      // prompt
        out.print(buf);                                         // content
        if (cur < buf.length()) {
            out.print("\033[" + buf.length() + "D");            // back to start of content
            if (cur > 0) out.print("\033[" + cur + "C");       // forward to cursor pos
        }
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
}
