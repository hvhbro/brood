package utils.etc;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Центральный логгер чита. Все модули пишут сюда.
 * Файл: C:\Logs\noslow_agent.log
 */
public final class Log {
    private static final Object LOCK = new Object();
    private static PrintWriter writer;

    private Log() {}

    public static void info(String tag, String message) {
        write("[" + tag + "] " + message);
    }

    public static void error(String tag, String message, Throwable t) {
        write("[" + tag + "][ERROR] " + message + " exc=" + t);
    }

    private static void write(String line) {
        synchronized (LOCK) {
            try {
                if (writer == null) {
                    writer = new PrintWriter(new FileWriter(
                        "C:" + File.separator + "Logs" + File.separator
                            + "noslow_agent.log", true), true);
                }
                writer.println(line);
            } catch (Throwable ignore) {}
        }
    }
}
