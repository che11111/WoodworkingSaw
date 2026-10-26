package dev.che1s.woodworkingsaw.i18n;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从可编辑的语言文件加载插件文案，缺失时回退到内置文案。 */
public final class LanguageManager {
    private static final String[] LOCALES = {"zh_CN", "en_US"};
    private static final Pattern ARGUMENT = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");
    private static final Pattern HEX_COLOR = Pattern.compile("&#([0-9a-fA-F]{6})");

    private final JavaPlugin plugin;
    private volatile String locale = "zh_CN";
    private volatile Map<String, String> text = Map.of();

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void saveDefaults() {
        File folder = new File(plugin.getDataFolder(), "lang");
        for (String locale : LOCALES) {
            File file = new File(folder, locale + ".yml");
            if (file.exists()) {
                continue;
            }
            try (InputStream stream = plugin.getResource("lang/" + locale + ".yml")) {
                if (stream == null) {
                    continue;
                }
                if (!folder.exists() && !folder.mkdirs()) {
                    continue;
                }
                Files.copy(stream, file.toPath());
            } catch (Exception ignored) {
            }
        }
    }

    public void load(String requested) {
        String target = normalize(requested);
        if (target == null) {
            target = "zh_CN";
        }
        Map<String, String> merged = new HashMap<>();
        YamlConfiguration bundled = readResource("lang/" + target + ".yml");
        for (String key : bundled.getKeys(true)) {
            if (bundled.isString(key)) {
                merged.put(key, bundled.getString(key));
            }
        }
        File file = new File(plugin.getDataFolder(), "lang/" + target + ".yml");
        if (file.exists()) {
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
            for (String key : disk.getKeys(true)) {
                if (disk.isString(key)) {
                    merged.put(key, disk.getString(key));
                }
            }
        }
        this.locale = target;
        this.text = Map.copyOf(merged);
    }

    public String locale() {
        return locale;
    }

    public String text(String key, String... arguments) {
        return format(text.getOrDefault(key, key), arguments);
    }

    public String message(String key, String... arguments) {
        String[] values = new String[arguments.length + 2];
        System.arraycopy(arguments, 0, values, 0, arguments.length);
        values[arguments.length] = "prefix";
        values[arguments.length + 1] = text.getOrDefault("messages.prefix", "");
        return translateColors(format(text.getOrDefault(key, key), values));
    }

    public String actionBar(String key, String... arguments) {
        return translateColors(format(text.getOrDefault(key, key), arguments));
    }

    public static String translateColors(String text) {
        Matcher matcher = HEX_COLOR.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("\u00a7x");
            for (char c : matcher.group(1).toCharArray()) {
                replacement.append('\u00a7').append(c);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    private static String format(String template, String... arguments) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < arguments.length; i += 2) {
            values.put(arguments[i], arguments[i + 1]);
        }
        Matcher matcher = ARGUMENT.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    values.containsKey(matcher.group(1)) ? Objects.toString(values.get(matcher.group(1)), "") : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private YamlConfiguration readResource(String name) {
        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled resource: " + name);
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return yaml;
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load bundled resource: " + name, error);
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim().replace('-', '_');
        for (String locale : LOCALES) {
            if (locale.equalsIgnoreCase(normalized)) {
                return locale;
            }
        }
        return null;
    }
}
