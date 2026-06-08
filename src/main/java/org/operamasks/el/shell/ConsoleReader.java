package org.operamasks.el.shell;

import java.io.*;
import java.util.*;

/**
 * Minimal line reader for the ELite REPL.
 * Provides line editing (backspace, arrow keys), history,
 * and multi-line input — zero native dependencies.
 *
 * Uses 'stty' on Unix to enable raw (character-at-a-time) terminal mode.
 */
class ConsoleReader implements AutoCloseable {

    private final InputStream in;
    private final PrintStream out;
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private String currentLine = "";
    private int cursor = 0;
    private boolean rawMode;
    private String[] sttyRestore;
    private boolean ansiSupported;

    public ConsoleReader(InputStream in, PrintStream out) {
        this.in = in;
        this.out = out;
        this.ansiSupported = !System.getProperty("os.name", "").toLowerCase().contains("win");
        enableRawMode();
    }

    private void enableRawMode() {
        if (!ansiSupported) return;
        try {
            Process p = new ProcessBuilder("stty", "-g")
                .redirectInput(ProcessBuilder.Redirect.INHERIT).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String settings = r.readLine();
            p.waitFor();
            if (settings != null) {
                sttyRestore = new String[]{"stty", settings};
                new ProcessBuilder("stty", "-icanon", "-echo", "min", "1")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT).start().waitFor();
                rawMode = true;
            }
        } catch (Exception e) { /* fall back to line-buffered */ }
    }

    private void disableRawMode() {
        if (!rawMode || sttyRestore == null) return;
        try {
            new ProcessBuilder(sttyRestore)
                .redirectInput(ProcessBuilder.Redirect.INHERIT).start().waitFor();
        } catch (Exception e) { /* ignore */ }
        rawMode = false;
    }

    @Override
    public void close() {
        disableRawMode();
    }

    public String readLine(String prompt) throws IOException {
        out.print(prompt);
        out.flush();

        StringBuilder buf = new StringBuilder();
        cursor = 0;
        historyIndex = -1;

        while (true) {
            int ch = in.read();
            if (ch == -1) return null;

            if (ch == '\n' || ch == '\r') {
                if (ch == '\r') {
                    int next = tryPeek();
                    if (next == '\n') in.read();
                }
                out.println();
                String line = buf.toString();
                if (!line.isEmpty() && (history.isEmpty()
                        || !history.get(history.size()-1).equals(line))) {
                    history.add(line);
                }
                return line;
            }

            if (ch == 4 && buf.length() == 0) { out.println(); return null; }

            if (ch == 127 || ch == 8) {
                if (cursor > 0) { buf.deleteCharAt(--cursor); redrawLine(prompt, buf.toString()); }
                continue;
            }

            if (ch == 27) {
                int next = tryPeek();
                if (next == '[') {
                    in.read();
                    int dir = in.read();
                    if (dir == 'A') navigateHistory(-1, buf, prompt);
                    else if (dir == 'B') navigateHistory(1, buf, prompt);
                    else if (dir == 'C') { if (cursor < buf.length()) cursor++; redrawLine(prompt, buf.toString()); }
                    else if (dir == 'D') { if (cursor > 0) cursor--; redrawLine(prompt, buf.toString()); }
                }
                continue;
            }

            if (ch >= 32 && ch < 127) {
                buf.insert(cursor++, (char) ch);
                redrawLine(prompt, buf.toString());
            }
        }
    }

    private int tryPeek() throws IOException {
        in.mark(1);
        int ch = in.read();
        if (ch != -1) in.reset();
        return ch;
    }

    private void navigateHistory(int direction, StringBuilder buf, String prompt) {
        if (history.isEmpty()) return;
        if (direction < 0 && historyIndex == -1) {
            currentLine = buf.toString();
            historyIndex = history.size() - 1;
        } else if (direction < 0 && historyIndex > 0) {
            historyIndex--;
        } else if (direction > 0 && historyIndex < history.size() - 1) {
            historyIndex++;
        } else if (direction > 0 && historyIndex == history.size() - 1) {
            historyIndex = -1;
            buf.replace(0, buf.length(), currentLine);
            cursor = buf.length();
            redrawLine(prompt, buf.toString());
            return;
        } else {
            return;
        }
        buf.replace(0, buf.length(), history.get(historyIndex));
        cursor = buf.length();
        redrawLine(prompt, buf.toString());
    }

    private void redrawLine(String prompt, String content) {
        if (ansiSupported) {
            out.print('\r');
            out.print(prompt);
            out.print(content);
            out.print(' ');
            out.print('\r');
            int targetCol = prompt.length() + cursor;
            if (targetCol > 0) out.print("\033[" + targetCol + "C");
        } else {
            out.print('\r');
            out.print(prompt);
            out.print(content);
        }
        out.flush();
    }

    public List<String> getHistory() { return Collections.unmodifiableList(history); }
}
