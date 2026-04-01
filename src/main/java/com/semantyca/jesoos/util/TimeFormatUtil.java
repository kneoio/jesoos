package com.semantyca.jesoos.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeFormatUtil {

    public static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final DateTimeFormatter YYYY_MM_DD_HH_MM_SS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFormatUtil() {}

    public static String formatTime(LocalDateTime dt) {
        return dt != null ? dt.format(HH_MM_SS) : "none";
    }

    public static String formatDateTime(LocalDateTime dt) {
        return dt != null ? dt.format(YYYY_MM_DD_HH_MM_SS) : "none";
    }

    public static String formatEpochMillis(long epochMillis) {
        LocalDateTime dt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis),
                ZoneId.systemDefault()
        );
        return formatDateTime(dt);
    }
}
