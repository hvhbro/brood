package utils.etc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Friends — список друзей (ники). Эффекты:
 *  - AimBot.selectTarget пропускает друзей (не наводится, не стреляет);
 *  - Dormant-призраки друзей не рисуются;
 *  - ESP друзей — зелёным (бокс + ник).
 *
 * Сравнение регистронезависимое, хранится введённый регистр (для отображения).
 * Ник: 3..16 символов [a-zA-Z0-9_]. Персист: friends.txt в папке конфигов
 * (plain UTF-8, по нику на строку) — грузится лениво, пишется при изменении.
 *
 * Потоки: меню (главный) + тики модулей (агентный) — все методы synchronized.
 * БЕЗ лямбд/:: (invokedynamic запрещён).
 */
public final class Friends {

    private static final String FILE = "friends.txt";
    private static final int MAX_NICK = 16;
    private static final int MAX_FRIENDS = 128;

    private static final ArrayList<String> names = new ArrayList<String>();
    private static boolean loaded;

    private Friends() {}

    /** Нормализация ника для сравнения (lowercase, trim). */
    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** Валидация ника Minecraft: 3..16 [a-zA-Z0-9_]. */
    public static boolean validNick(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.length() < 3 || s.length() > MAX_NICK) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public static synchronized boolean isFriend(String name) {
        if (name == null || name.isEmpty()) return false;
        ensureLoaded();
        String n = norm(name);
        for (int i = 0; i < names.size(); i++) {
            if (norm((String) names.get(i)).equals(n)) return true;
        }
        return false;
    }

    /** @return true если добавлен (false = невалиден/дубликат/переполнен). */
    public static synchronized boolean add(String raw) {
        if (!validNick(raw)) return false;
        ensureLoaded();
        String s = raw.trim();
        String n = norm(s);
        for (int i = 0; i < names.size(); i++) {
            if (norm((String) names.get(i)).equals(n)) return false;
        }
        if (names.size() >= MAX_FRIENDS) return false;
        names.add(s);
        save();
        Log.info("Friends", "added: " + s + " (" + names.size() + ")");
        return true;
    }

    /** @return true если был в списке и удалён. */
    public static synchronized boolean remove(String name) {
        if (name == null) return false;
        ensureLoaded();
        String n = norm(name);
        for (int i = 0; i < names.size(); i++) {
            if (norm((String) names.get(i)).equals(n)) {
                String gone = (String) names.remove(i);
                save();
                Log.info("Friends", "removed: " + gone + " (" + names.size() + ")");
                return true;
            }
        }
        return false;
    }

    public static synchronized String[] list() {
        ensureLoaded();
        return (String[]) names.toArray(new String[names.size()]);
    }

    public static synchronized int count() {
        ensureLoaded();
        return names.size();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (!ConfigManager.ensureDir()) return;
            File f = new File(ConfigManager.dir(), FILE);
            if (!f.isFile()) return;
            FileInputStream fi = new FileInputStream(f);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int n;
                while ((n = fi.read(chunk)) > 0) bo.write(chunk, 0, n);
                String all = new String(bo.toByteArray(), "UTF-8");
                int start = 0;
                for (int i = 0; i <= all.length(); i++) {
                    if (i == all.length() || all.charAt(i) == '\n') {
                        if (i > start) {
                            String s = all.substring(start, i).trim();
                            if (validNick(s) && names.size() < MAX_FRIENDS) {
                                boolean dup = false;
                                String nn = norm(s);
                                for (int k = 0; k < names.size(); k++) {
                                    if (norm((String) names.get(k)).equals(nn)) { dup = true; break; }
                                }
                                if (!dup) names.add(s);
                            }
                        }
                        start = i + 1;
                    }
                }
            } finally {
                try { fi.close(); } catch (Throwable ignore) {}
            }
            Log.info("Friends", "loaded " + names.size() + " from friends.txt");
        } catch (Throwable t) {
            Log.error("Friends", "load failed", t);
        }
    }

    private static void save() {
        try {
            if (!ConfigManager.ensureDir()) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                sb.append((String) names.get(i)).append('\n');
            }
            byte[] b = sb.toString().getBytes("UTF-8");
            FileOutputStream fo = new FileOutputStream(new File(ConfigManager.dir(), FILE));
            try {
                fo.write(b);
                fo.flush();
            } finally {
                try { fo.close(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            Log.error("Friends", "save failed", t);
        }
    }
}
