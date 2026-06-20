package com.semantyca.jesoos.util;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeContextUtil {

    public static String getCurrentMomentDetailed(ZoneId zoneId) {
        ZonedDateTime zonedNow = ZonedDateTime.now(zoneId);
        LocalTime now = zonedNow.toLocalTime();
        boolean isWeekday = zonedNow.getDayOfWeek().getValue() <= 5;
        String dayName = ", it is " + zonedNow.getDayOfWeek().name().toLowerCase();

        if (now.isBefore(LocalTime.of(6, 0))) {
            return "late night hours" + dayName + ", " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(9, 0))) {
            return (isWeekday ? "early morning weekday hours" : "early morning weekend hours")
                    + dayName + ", " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(12, 0))) {
            return (isWeekday ? "late morning weekday hours" : "late morning weekend hours")
                    + dayName + ", " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(13, 0))) {
            return "lunch hours" + dayName + ", " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(14, 0))) {
            return "early afternoon hours" + dayName + ", " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(17, 0))) {
            return (isWeekday ? "weekday afternoon hours" : "weekend afternoon hours")
                    + dayName + ", " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(19, 0))) {
            return (isWeekday ? "weekday early evening hours" : "weekend early evening hours")
                    + dayName + ", " + fuzzyHour(now);
        } else if (now.isBefore(LocalTime.of(21, 0))) {
            return "evening hours" + dayName + ", " + fuzzyHour(now);
        } else {
            return "night hours" + dayName + ", " + fuzzyHour(now);
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