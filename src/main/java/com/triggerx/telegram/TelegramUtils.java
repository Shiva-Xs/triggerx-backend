package com.triggerx.telegram;

public final class TelegramUtils {

    private TelegramUtils() {}

    public static String escapeMd(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\").replace("_", "\\_").replace("*", "\\*")
                .replace("[", "\\[").replace("]", "\\]").replace("(", "\\(")
                .replace(")", "\\)").replace("~", "\\~").replace("`", "\\`")
                .replace(">", "\\>").replace("#", "\\#").replace("+", "\\+")
                .replace("-", "\\-").replace("=", "\\=").replace("|", "\\|")
                .replace("{", "\\{").replace("}", "\\}").replace(".", "\\.")
                .replace("!", "\\!");
    }
}
