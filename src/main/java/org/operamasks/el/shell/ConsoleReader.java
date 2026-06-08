package org.operamasks.el.shell;

import java.io.*;
import java.util.*;

/**
 * Minimal line reader for the ELite REPL.
 * Provides basic line editing (backspace), multi-line input,
 * tab completion, and history — without any native library dependency.
 */
class ConsoleReader {

    private final InputStream in;
    private final PrintStream out;
    private final List<String> history = new ArrayList<>();
    private int historyIndex = -1;
    private String currentLine = "";
    private int cursor = 0;

    // ANSI escape codes
    private static final String CLEAR_LINE = "\033[2K\r";
    private static final String CURSOR_UP = "\033[1A";
    private static final String RESET = "\033[0m";

    private boolean ansiSupported;

    public ConsoleReader(InputStream in, PrintStream out) {
        this.in = in;
        this.out = out;
        this.ansiSupported = !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Read a line with the given prompt. Returns null on EOF (Ctrl+D).
     */
    public String readLine(String prompt) throws IOException {
        out.print(prompt);
        out.flush();

        StringBuilder buf = new StringBuilder();
        cursor = 0;
        historyIndex = -1;

        while (true) {
            int ch = in.read();
            if (ch == -1) return null; // EOF

            if (ch == '\n' || ch == '\r') {
                // Handle \r\n
                if (ch == '\r') {
                    int next = tryPeek();
                    if (next == '\n') in.read(); // consume \n
                }
                out.println();
                String line = buf.toString();
                if (!line.isEmpty()) {
                    // Don't add consecutive duplicates
                    if (history.isEmpty() || !history.get(history.size()-1).equals(line)) {
                        history.add(line);
                    }
                }
                return line;
            }

            if (ch == 4 && buf.length() == 0) { // Ctrl+D on empty line
                out.println();
                return null;
            }

            if (ch == 127 || ch == 8) { // Backspace
                if (cursor > 0) {
                    buf.deleteCharAt(--cursor);
                    redrawLine(prompt, buf.toString());
                }
                continue;
            }

            if (ch == 9) { // Tab — handled externally by Completor
                // We can't handle tab here; it's handled by the caller
                // Just ignore it in raw input mode
                continue;
            }

            // Arrow keys (escape sequences)
            if (ch == 27) { // ESC
                int next = tryPeek();
                if (next == '[') {
                    in.read(); // consume [
                    int dir = in.read();
                    if (dir == 'A') { // Up
                        navigateHistory(-1, buf);
                    } else if (dir == 'B') { // Down
                        navigateHistory(1, buf);
                    } else if (dir == 'C') { // Right
                        if (cursor < buf.length()) cursor++;
                        redrawLine(prompt, buf.toString());
                    } else if (dir == 'D') { // Left
                        if (cursor > 0) cursor--;
                        redrawLine(prompt, buf.toString());
                    }
                }
                continue;
            }

            // Printable character
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

    private void navigateHistory(int direction, StringBuilder buf) {
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
            return;
        } else {
            return;
        }

        buf.replace(0, buf.length(), history.get(historyIndex));
        cursor = buf.length();
    }

    private void redrawLine(String prompt, String content) {
        if (ansiSupported) {
            out.print('\r');
            out.print(prompt);
            out.print(content);
            out.print(' '); // clear trailing char
            // Move cursor to correct position
            int targetCol = prompt.length() + cursor;
            out.print('\r');
            if (targetCol > 0) {
                out.print("\033[" + targetCol + "C");
            }
        } else {
            // Fallback: just print the line (no cursor positioning)
            out.print('\r');
            out.print(prompt);
            out.print(content);
        }
        out.flush();
    }

    public List<String> getHistory() { return Collections.unmodifiableList(history); }
}
