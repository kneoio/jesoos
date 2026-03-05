package com.semantyca.djinn.util;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeContextUtil {

    public static String getCurrentMomentDetailed(ZoneId zoneId) {
        ZonedDateTime zonedNow = ZonedDateTime.now(zoneId);
        LocalTime now = zonedNow.toLocalTime();
        boolean isWeekday = zonedNow.getDayOfWeek().getValue() <= 5;

        if (now.isBefore(LocalTime.of(6, 0))) {
            return "late night hours, " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(9, 0))) {
            return isWeekday ? "early morning weekday hours, " + fuzzyHour(now)
                    : "early morning weekend hours, " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(12, 0))) {
            return isWeekday ? "late morning weekday hours, " + fuzzyHour(now)
                    : "late morning weekend hours, " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(13, 0))) {
            return "lunch hours, " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(14, 0))) {
            return "early afternoon hours, " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(17, 0))) {
            return isWeekday ? "weekday afternoon hours, " + fuzzyHour(now)
                    : "weekend afternoon hours, " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(19, 0))) {
            return isWeekday ? "weekday early evening hours, " + fuzzyHour(now)
                    : "weekend early evening hours, " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(21, 0))) {
            return "evening hours, " + fuzzyHour(now);
        } else {
            return "night hours, " + fuzzyHour(now);
        }
    }

    private static String fuzzyHour(LocalTime now) {
        int hour = now.getHour();
        int minute = now.getMinute();

        if (minute < 15) {
            return "around " + hour + " o’clock";
        } else if (minute < 40) {
            return "about half past " + hour;
        } else {
            return "almost " + (hour == 23 ? "midnight" : (hour + 1) + " o’clock");
        }
    }



}