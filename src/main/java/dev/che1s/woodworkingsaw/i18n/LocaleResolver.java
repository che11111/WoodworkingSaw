package dev.che1s.woodworkingsaw.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves bundled language files from explicit configuration or the server Locale. */
final class LocaleResolver {
    static final String AUTO = "auto";
    static final String DEFAULT_LOCALE = "en_us";
    static final List<String> BUNDLED_LOCALES = List.of(
            "en_us", "zh_cn", "ja", "ko", "zh_tw", "de", "fr", "es", "ru"
    );

    private static final Set<String> BUNDLED = Set.copyOf(BUNDLED_LOCALES);
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_.-]+");
    private static final List<String> LOCALE_ENVIRONMENT = List.of(
            "LC_ALL", "LC_MESSAGES", "LANG"
    );

    private LocaleResolver() {
    }

    static String normalize(String configured) {
        if (configured == null) {
            return AUTO;
        }
        String normalized = configured.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if (normalized.endsWith(".yml")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    static boolean isAuto(String configured) {
        return AUTO.equals(normalize(configured));
    }

    static boolean isSafeCustomName(String configured) {
        String normalized = normalize(configured);
        return !normalized.isBlank()
                && !AUTO.equals(normalized)
                && SAFE_NAME.matcher(normalized).matches();
    }

    static String knownLocale(String configured) {
        String normalized = normalize(configured);
        if (BUNDLED.contains(normalized)) {
            return normalized;
        }
        if (normalized.equals("en")) {
            return "en_us";
        }
        if (normalized.equals("zh") || normalized.equals("zh_hans")) {
            return "zh_cn";
        }
        if (normalized.equals("zh_hant")
                || normalized.equals("zh_hk")
                || normalized.equals("zh_mo")) {
            return "zh_tw";
        }
        if (languagePart(normalized).equals("zh")) {
            return normalized.contains("hant")
                    || normalized.contains("tw")
                    || normalized.contains("hk")
                    || normalized.contains("mo")
                    ? "zh_tw"
                    : "zh_cn";
        }
        return switch (languagePart(normalized)) {
            case "en" -> "en_us";
            case "ja" -> "ja";
            case "ko" -> "ko";
            case "de" -> "de";
            case "fr" -> "fr";
            case "es" -> "es";
            case "ru" -> "ru";
            default -> null;
        };
    }

    static String detectServerLocale(Locale locale, Map<String, String> environment) {
        String detected = fromLocale(locale);
        if (detected != null) {
            return detected;
        }
        for (String variable : LOCALE_ENVIRONMENT) {
            detected = fromEnvironment(environment.get(variable));
            if (detected != null) {
                return detected;
            }
        }
        return DEFAULT_LOCALE;
    }

    static String fromLocale(Locale locale) {
        if (locale == null) {
            return null;
        }
        String language = locale.getLanguage();
        if (language == null || language.isBlank()) {
            return null;
        }
        if (language.equalsIgnoreCase("zh")) {
            String script = locale.getScript();
            String country = locale.getCountry();
            if ("Hant".equalsIgnoreCase(script)
                    || country.equalsIgnoreCase("TW")
                    || country.equalsIgnoreCase("HK")
                    || country.equalsIgnoreCase("MO")) {
                return "zh_tw";
            }
            return "zh_cn";
        }
        return knownLocale(language);
    }

    static String fromEnvironment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int encoding = normalized.indexOf('.');
        if (encoding >= 0) {
            normalized = normalized.substring(0, encoding);
        }
        int modifier = normalized.indexOf('@');
        if (modifier >= 0) {
            normalized = normalized.substring(0, modifier);
        }
        normalized = normalized.replace('-', '_');
        if (normalized.equals("c") || normalized.equals("posix")
                || normalized.startsWith("c_")) {
            return null;
        }
        if (normalized.startsWith("chinese_simplified")
                || normalized.startsWith("chinese_china")
                || normalized.startsWith("chinese_singapore")) {
            return "zh_cn";
        }
        if (normalized.startsWith("chinese_traditional")
                || normalized.startsWith("chinese_taiwan")
                || normalized.startsWith("chinese_hong_kong")) {
            return "zh_tw";
        }
        return knownLocale(normalized);
    }

    private static String languagePart(String normalized) {
        int separator = normalized.indexOf('_');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }
}
