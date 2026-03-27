package ac.grim.legacyac.util;

import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

public final class LogMessageFormatter {
    private LogMessageFormatter() {
    }

    public static boolean isBudgetBreakdownEnabled(FileConfiguration config) {
        return config.getBoolean("debug.budget-breakdown",
                config.getBoolean("adaptive-lag.compare-log-enabled", false));
    }

    public static boolean isPipelineSummaryEnabled(FileConfiguration config) {
        return config.getBoolean("debug.pipeline-summary", true);
    }

    public static boolean isFrameContextDiffEnabled(FileConfiguration config) {
        return config.getBoolean("debug.frame-context-diff", false);
    }

    public static int getDebugDetailMaxLength(FileConfiguration config) {
        return Math.max(48, config.getInt("debug.detail-max-length", 160));
    }

    public static int getAlertDetailMaxLength(FileConfiguration config) {
        return Math.max(48, config.getInt("alerts.detail-max-length", 120));
    }

    public static String shorten(String input, int maxLength) {
        if (input == null) {
            return "";
        }
        String compact = input.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxLength) {
            return compact;
        }
        if (maxLength <= 3) {
            return compact.substring(0, maxLength);
        }
        return compact.substring(0, maxLength - 3) + "...";
    }

    public static String debugLine(FileConfiguration config, String playerName, String label, String... keyValues) {
        StringBuilder sb = new StringBuilder();
        sb.append("[GLAC-DEBUG] ").append(playerName).append(" | ").append(label);
        appendKeyValues(sb, keyValues, getDebugDetailMaxLength(config));
        return sb.toString();
    }

    public static String alertLine(FileConfiguration config, boolean console, String playerName, String check, double vl,
            String detail, String budgetTag, String source) {
        String shortDetail = shorten(detail, getAlertDetailMaxLength(config));
        boolean includeSource = config.getBoolean("alerts.include-source", true);
        boolean includeBudget = config.getBoolean("alerts.include-budget", false);

        StringBuilder extra = new StringBuilder();
        if (shortDetail.length() > 0) {
            extra.append(shortDetail);
        }
        if (includeSource && source != null && source.length() > 0) {
            appendSegment(extra, "src=" + source);
        }
        if (includeBudget && budgetTag != null && budgetTag.length() > 0) {
            appendSegment(extra, "budget=" + budgetTag);
        }

        String vlText = String.format(Locale.ROOT, "%.2f", vl);
        if (console) {
            StringBuilder sb = new StringBuilder();
            sb.append("[GLAC] ").append(playerName)
                    .append(" | ").append(check)
                    .append(" | VL=").append(vlText);
            if (extra.length() > 0) {
                sb.append(" | ").append(extra);
            }
            return sb.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.RED).append("[GLAC] ")
                .append(ChatColor.GRAY).append(playerName)
                .append(ChatColor.DARK_GRAY).append(" | ")
                .append(ChatColor.YELLOW).append(check)
                .append(ChatColor.DARK_GRAY).append(" | ")
                .append(ChatColor.GRAY).append("VL ")
                .append(ChatColor.WHITE).append(vlText);
        if (extra.length() > 0) {
            sb.append(ChatColor.DARK_GRAY).append(" | ")
                    .append(ChatColor.GRAY).append(extra.toString());
        }
        return sb.toString();
    }

    private static void appendSegment(StringBuilder builder, String segment) {
        if (segment == null || segment.length() == 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" | ");
        }
        builder.append(segment);
    }

    private static void appendKeyValues(StringBuilder builder, String[] keyValues, int maxLength) {
        if (keyValues == null) {
            return;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = keyValues[i];
            String value = keyValues[i + 1];
            if (key == null || value == null) {
                continue;
            }
            String compactValue = shorten(value, maxLength);
            if (compactValue.length() == 0) {
                continue;
            }
            builder.append(" | ").append(key).append('=').append(compactValue);
        }
    }
}
