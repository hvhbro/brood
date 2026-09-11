package utils.etc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.zip.CRC32;

import modules.api.Module;
import modules.api.Modules;

/**
 * Конфиг-система: сохранение/загрузка состояния всех модулей.
 *
 * Папка: %LOCALAPPDATA%\RustMe (= C:\Users\Admin\AppData\Local\RustMe),
 * создаётся автоматически при любой операции (ensureDir).
 *
 * ФОРМАТ ФАЙЛА (имя.rm): шифруется целиком (crypt — xorshift32-кейстрим
 * с фиксированным сидом, XOR — функция обратима сама в себя). После
 * дешифровки: магия "RMCF" + версия (1 байт) + CRC32 payload (8 hex-символов)
 * + payload — построчный текст:
 *   M;<module>;<state 0|1>;<bindKey>
 *   F;<module>;<setting>;<float>     — FloatSetting (покрывает Bool/Mode — они наследники)
 *   U;<module>;<multi>;<0101...>     — мультибокс, по символу на опцию
 *   C;<module>;<color>;<argb int>
 * Загрузка в 2 прохода: сначала значения настроек, потом состояния модулей —
 * чтобы onEnable видел уже загруженные значения. CRC защищает от битых/чужих
 * файлов (молча не применяем — кидаем исключение, меню показывает ошибку).
 *
 * БЕЗ лямбд/:: (invokedynamic в защищённой JVM запрещён).
 */
public final class ConfigManager {

    private static final String EXT = ".rm";
    private static final byte VERSION = 1;
    /** Сид кейстрима (произвольная константа "RMC1"). */
    private static final int SEED = 0x524D4331;

    private ConfigManager() {}

    /** Папка конфигов. */
    public static File dir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base != null && base.trim().length() > 0) {
            return new File(base.trim(), "RustMe");
        }
        return new File("C:\\Users\\Admin\\AppData\\Local\\RustMe");
    }

    /** Создаёт папку, если её нет. true = папка существует. */
    public static boolean ensureDir() {
        try {
            File d = dir();
            if (!d.isDirectory()) d.mkdirs();
            return d.isDirectory();
        } catch (Throwable t) {
            Log.error("Config", "ensureDir failed", t);
            return false;
        }
    }

    /** Имя файла → безопасное имя (ASCII-имена из меню; максимум 24 символа). */
    public static String sanitize(String raw) {
        if (raw == null) return "default";
        String s = raw.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 24; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == '.' || last == ' ' || last == '_') sb.setLength(sb.length() - 1);
            else break;
        }
        String out = sb.toString().trim();
        if (out.length() == 0) return "default";
        String up = out.toUpperCase();
        if ("CON".equals(up) || "PRN".equals(up) || "AUX".equals(up) || "NUL".equals(up)
            || up.equals("COM1") || up.equals("COM2") || up.equals("COM3") || up.equals("COM4")
            || up.equals("LPT1") || up.equals("LPT2") || up.equals("LPT3")) {
            out = "cfg_" + out;
        }
        return out;
    }

    /** Список конфигов (без расширения), по алфавиту. */
    public static String[] list() {
        ArrayList<String> out = new ArrayList<String>();
        try {
            if (ensureDir()) {
                File[] fs = dir().listFiles();
                if (fs != null) {
                    for (int i = 0; i < fs.length; i++) {
                        File f = fs[i];
                        String n = f.getName();
                        if (f.isFile() && n.endsWith(EXT)) {
                            out.add(n.substring(0, n.length() - EXT.length()));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.error("Config", "list failed", t);
        }
        String[] arr = out.toArray(new String[out.size()]);
        java.util.Arrays.sort(arr, String.CASE_INSENSITIVE_ORDER);
        return arr;
    }

    /** Сохраняет состояние всех модулей (кроме Menu) в шифрованный файл. */
    public static void save(String rawName) throws Exception {
        String name = sanitize(rawName);
        if (!ensureDir()) throw new IllegalStateException("no config dir");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < Modules.all().size(); i++) {
            Module m = (Module) Modules.all().get(i);
            if ("Menu".equals(m.name)) continue;
            wLine(bos, "M;" + m.name + ";" + (m.isState() ? "1" : "0") + ";" + m.bindKey);
            java.util.List sts = m.settings();
            for (int j = 0; j < sts.size(); j++) {
                Object o = sts.get(j);
                if (o instanceof Module.MultiSetting) {
                    Module.MultiSetting ms = (Module.MultiSetting) o;
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < ms.selected.length; k++) {
                        sb.append(ms.selected[k] ? '1' : '0');
                    }
                    wLine(bos, "U;" + m.name + ";" + ms.name + ";" + sb);
                } else if (o instanceof Module.ColorSetting) {
                    Module.ColorSetting cs = (Module.ColorSetting) o;
                    wLine(bos, "C;" + m.name + ";" + cs.name + ";" + cs.argb);
                } else if (o instanceof Module.FloatSetting) {
                    Module.FloatSetting fs = (Module.FloatSetting) o;
                    wLine(bos, "F;" + m.name + ";" + fs.name + ";" + fs.value);
                }
            }
        }
        byte[] payload = bos.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);
        String cks = String.format("%08x", Long.valueOf(crc.getValue()));
        byte[] out = new byte[13 + payload.length];
        out[0] = 'R'; out[1] = 'M'; out[2] = 'C'; out[3] = 'F';
        out[4] = VERSION;
        System.arraycopy(cks.getBytes("UTF-8"), 0, out, 5, 8);
        System.arraycopy(payload, 0, out, 13, payload.length);
        crypt(out);
        FileOutputStream fo = new FileOutputStream(new File(dir(), name + EXT));
        try {
            fo.write(out);
            fo.flush();
        } finally {
            try { fo.close(); } catch (Throwable ignore) {}
        }
        Log.info("Config", "saved '" + name + "' (" + payload.length + " bytes payload)");
    }

    /** Загружает конфиг и применяет к модулям. Возвращает число модулей. */
    public static int load(String rawName) throws Exception {
        String name = sanitize(rawName);
        if (!ensureDir()) throw new IllegalStateException("no config dir");
        File f = new File(dir(), name + EXT);
        byte[] buf = readAll(f);
        crypt(buf);
        if (buf.length < 13) throw new IllegalStateException("file too small");
        if (buf[0] != 'R' || buf[1] != 'M' || buf[2] != 'C' || buf[3] != 'F') {
            throw new IllegalStateException("bad magic");
        }
        if (buf[4] != VERSION) throw new IllegalStateException("bad version");
        String want = new String(buf, 5, 8, "UTF-8");
        byte[] payload = new byte[buf.length - 13];
        System.arraycopy(buf, 13, payload, 0, payload.length);
        CRC32 crc = new CRC32();
        crc.update(payload);
        String got = String.format("%08x", Long.valueOf(crc.getValue()));
        if (!want.equalsIgnoreCase(got)) throw new IllegalStateException("crc mismatch");

        ArrayList lines = splitLines(payload);
        ArrayList mLines = new ArrayList();
        for (int i = 0; i < lines.size(); i++) {
            String ln = ((String) lines.get(i)).trim();
            if (ln.length() < 3) continue;
            String[] p = ln.split(";", -1);
            if ("M".equals(p[0])) {
                mLines.add(p);
            } else if (p.length >= 4) {
                applySetting(p[0].charAt(0), p[1], p[2], p[3]);
            }
        }
        int applied = 0;
        for (int i = 0; i < mLines.size(); i++) {
            String[] p = (String[]) mLines.get(i);
            if (p.length < 4) continue;
            Module m = Modules.get(p[1]);
            if (m == null || "Menu".equals(m.name)) continue;
            m.bindKey = parseInt(p[3], -1);
            m.setState("1".equals(p[2]));
            applied++;
        }
        Log.info("Config", "loaded '" + name + "' (" + applied + " modules)");
        return applied;
    }

    /** Удаляет конфиг. false = файла не было. */
    public static boolean delete(String rawName) {
        try {
            String name = sanitize(rawName);
            File f = new File(dir(), name + EXT);
            if (f.isFile()) return f.delete();
        } catch (Throwable t) {
            Log.error("Config", "delete failed", t);
        }
        return false;
    }

    /** Открывает папку конфигов в проводнике. */
    public static void openDir() {
        try {
            ensureDir();
            String path = dir().getAbsolutePath();
            try {
                new ProcessBuilder(new String[] { "explorer.exe", path }).start();
            } catch (Throwable first) {
                Runtime.getRuntime().exec(new String[] { "explorer.exe", path });
            }
            Log.info("Config", "opened dir: " + path);
        } catch (Throwable t) {
            Log.error("Config", "openDir failed", t);
        }
    }

    /** Пресеты ColorPicker (presets.dat, то же XOR-шифрование). null = нет файла. */
    public static int[] loadPresets() {
        try {
            File f = new File(dir(), "presets.dat");
            if (!f.isFile()) return null;
            byte[] buf = readAll(f);
            crypt(buf);
            if (buf.length < 4 || buf.length % 4 != 0) return null;
            int n = buf.length / 4;
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                int o = i * 4;
                out[i] = ((buf[o] & 0xFF) << 24) | ((buf[o + 1] & 0xFF) << 16)
                    | ((buf[o + 2] & 0xFF) << 8) | (buf[o + 3] & 0xFF);
            }
            return out;
        } catch (Throwable t) {
            Log.error("Config", "loadPresets failed", t);
            return null;
        }
    }

    /** Сохраняет пресеты ColorPicker. */
    public static void savePresets(int[] presets) {
        try {
            if (!ensureDir() || presets == null) return;
            byte[] buf = new byte[presets.length * 4];
            for (int i = 0; i < presets.length; i++) {
                int o = i * 4;
                buf[o] = (byte) (presets[i] >>> 24);
                buf[o + 1] = (byte) (presets[i] >>> 16);
                buf[o + 2] = (byte) (presets[i] >>> 8);
                buf[o + 3] = (byte) presets[i];
            }
            crypt(buf);
            FileOutputStream fo = new FileOutputStream(new File(dir(), "presets.dat"));
            try {
                fo.write(buf);
                fo.flush();
            } finally {
                try { fo.close(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            Log.error("Config", "savePresets failed", t);
        }
    }

    // ===== внутреннее =====

    private static void wLine(ByteArrayOutputStream bos, String s) throws Exception {
        byte[] b = s.getBytes("UTF-8");
        bos.write(b, 0, b.length);
        bos.write('\n');
    }

    private static void applySetting(char kind, String mod, String sname, String val) {
        Module m = Modules.get(mod);
        if (m == null) return;
        Object s = findSetting(m, sname);
        if (s == null) return;
        try {
            if (kind == 'F' && s instanceof Module.FloatSetting) {
                Module.FloatSetting fs = (Module.FloatSetting) s;
                float v = Float.parseFloat(val.trim());
                fs.value = Math.max(fs.min, Math.min(fs.max, v));
            } else if (kind == 'U' && s instanceof Module.MultiSetting) {
                Module.MultiSetting ms = (Module.MultiSetting) s;
                for (int k = 0; k < ms.selected.length && k < val.length(); k++) {
                    ms.selected[k] = val.charAt(k) == '1';
                }
            } else if (kind == 'C' && s instanceof Module.ColorSetting) {
                ((Module.ColorSetting) s).argb = (int) Long.parseLong(val.trim());
            }
        } catch (Throwable ignore) {
            // одна кривая настройка не должна валить весь конфиг
        }
    }

    private static Object findSetting(Module m, String sname) {
        java.util.List sts = m.settings();
        for (int j = 0; j < sts.size(); j++) {
            Object o = sts.get(j);
            if (o instanceof Module.Setting && ((Module.Setting) o).name.equals(sname)) {
                return o;
            }
        }
        return null;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignore) {
            return def;
        }
    }

    private static ArrayList splitLines(byte[] b) throws Exception {
        ArrayList out = new ArrayList();
        int start = 0;
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '\n') {
                if (i > start) out.add(new String(b, start, i - start, "UTF-8"));
                start = i + 1;
            }
        }
        if (start < b.length) out.add(new String(b, start, b.length - start, "UTF-8"));
        return out;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = fi.read(chunk)) > 0) bo.write(chunk, 0, n);
            return bo.toByteArray();
        } finally {
            try { fi.close(); } catch (Throwable ignore) {}
        }
    }

    /** Шифрование/дешифрование (XOR — обратимо само в себя): xorshift32. */
    private static void crypt(byte[] b) {
        int s = SEED;
        for (int i = 0; i < b.length; i++) {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            b[i] ^= (byte) (s >>> 24);
        }
    }
}
