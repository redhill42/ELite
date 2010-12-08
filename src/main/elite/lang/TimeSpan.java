/*
 * $Id: TimeSpan.java,v 1.3 2009/03/22 08:37:27 danielyuan Exp $
 *
 * Copyright (C) 2006 Operamasks Community.
 * Copyright (C) 2000-2006 Apusic Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see http://www.gnu.org/licenses.
 */

package elite.lang;

import java.io.Serializable;
import elite.lang.annotation.Expando;

public class TimeSpan implements Serializable, Comparable<TimeSpan>
{
    private final long time;

    public static final TimeSpan ZERO = new TimeSpan(0);
    public static final TimeSpan ONE_DAY = new TimeSpan(1, 0, 0, 0);
    public static final TimeSpan ONE_HOUR = new TimeSpan(0, 1, 0, 0);
    public static final TimeSpan ONE_MINUTE = new TimeSpan(0, 0, 1, 0);
    public static final TimeSpan ONE_SECOND = new TimeSpan(0, 0, 0, 1);
    
    public TimeSpan() {
        time = 0;
    }

    public TimeSpan(long time) {
        this.time = time;
    }

    public TimeSpan(int days, int hours, int minutes, int seconds) {
        time = (seconds + 60 * (minutes + 60 * (hours + 24 * days))) * 1000;
    }

    public static TimeSpan difference(Timestamp time1, Timestamp time2) {
        return new TimeSpan(time1.getTimeInMillis() - time2.getTimeInMillis());
    }

    public static TimeSpan dateDifference(Timestamp date1, Timestamp date2) {
        Timestamp adjDate1 = new Timestamp(date1.getYear(), date1.getMonth(), date1.getDate());
        Timestamp adjDate2 = new Timestamp(date2.getYear(), date2.getMonth(), date2.getDate());
        return difference(adjDate1, adjDate2);
    }

    public static TimeSpan timeDifference(Timestamp time1, Timestamp time2) {
        Timestamp adjTime1 = new Timestamp(0, 0, 0, time1.getHours(), time1.getMinutes(), time1.getSeconds());
        Timestamp adjTime2 = new Timestamp(0, 0, 0, time2.getHours(), time2.getMinutes(), time2.getSeconds());
        return difference(adjTime1, adjTime2);
    }

    private static final long DAY_AMOUNT = 86400000L;
    private static final long HOUR_AMOUNT = 3600000L;
    private static final long MINUTE_AMOUNT = 60000L;
    private static final long SECOND_AMOUNT = 1000L;

    public long getDays() {
        return time / DAY_AMOUNT;
    }

    public long getTotalHours() {
        return time / HOUR_AMOUNT;
    }

    public int getHours() {
        return (int)(getTotalHours() - getDays()*24);
    }

    public long getTotalMinutes() {
        return time / MINUTE_AMOUNT;
    }

    public int getMinutes() {
        return (int)(getTotalMinutes() - getTotalHours()*60);
    }

    public long getTotalSeconds() {
        return time / SECOND_AMOUNT;
    }

    public int getSeconds() {
        return (int)(getTotalSeconds() - getTotalMinutes()*60);
    }

    public long getTotalMilliSeconds() {
        return time;
    }

    public int getMilliSeconds() {
        return (int)(getTotalMilliSeconds() - getTotalSeconds()*1000);
    }

    public int compareTo(TimeSpan span) {
        long diff = time - span.getTotalMilliSeconds();
        return diff == 0 ? 0 : diff > 0 ? 1 : -1;
    }

    @Expando(name="+")
    public TimeSpan add(TimeSpan span) {
        return new TimeSpan(time + span.getTotalMilliSeconds());
    }

    @Expando(name="-")
    public TimeSpan subtract(TimeSpan span) {
        return new TimeSpan(time - span.getTotalMilliSeconds());
    }

    @Expando(name="__pos__")
    public TimeSpan __pos__() {
        return this;
    }

    @Expando(name="__neg__")
    public TimeSpan negate() {
        return new TimeSpan(-time);
    }

    public int signum() {
        return time == 0 ? 0 : time > 0 ? 1 : -1;
    }

    public String format(String mask) {
        StringBuilder result = new StringBuilder();

        if (time < 0) {
            result.append("-");
        }

        int length = mask.length();
        for (int i = 0; i < length; i++) {
            char c = mask.charAt(i);
            if (c != '%') {
                result.append(c);
            } else {
                c = mask.charAt(++i);
                switch (c) {
                case 'd':
                    result.append(Math.abs(getDays()));
                    break;

                case 'h':
                    result.append(Math.abs(getHours()));
                    break;

                case 'm':
                    result.append(Math.abs(getMinutes()));
                    break;

                case 's':
                    result.append(Math.abs(getSeconds()));
                    break;

                default:
                    result.append('%');
                    result.append(c);
                    break;
                }
            }
        }
        return result.toString();
    }

    public boolean equals(Object obj) {
        return (obj instanceof TimeSpan) && (time == ((TimeSpan)obj).getTotalMilliSeconds());
    }

    public int hashCode() {
        return (int)(time ^ (int)(time >>> 32));
    }

    public String toString() {
        return format("%d %h:%m:%s");
    }
}
