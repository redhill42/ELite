/*
 * $Id: Timestamp.java,v 1.4 2009/05/10 16:54:55 danielyuan Exp $
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
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.Date;
import java.util.Locale;
import java.text.DateFormat;

import elite.lang.annotation.Expando;
import elite.lang.annotation.Data;

@Data({"year", "month", "date", "hours", "minutes", "seconds"})
public class Timestamp implements Serializable, Comparable<Timestamp>
{
    private final long time;

    private static final Calendar staticCal = new GregorianCalendar();
    private static final long serialVersionUID = 5445487933804595589L;

    public Timestamp() {
        this(System.currentTimeMillis());
    }

    public Timestamp(long millis) {
        time = millis;
    }

    public Timestamp(Date date) {
        this(date.getTime());
    }

    public Timestamp(int year, int month, int date) {
        this(year, month, date, 0, 0, 0, TimeZone.getDefault());
    }

    public Timestamp(int year, int month, int date, TimeZone tz) {
        this(year, month, date, 0, 0, 0, tz);
    }

    public Timestamp(int year, int month, int date, int hrs, int min, int sec) {
        this(year, month, date, hrs, min, sec, TimeZone.getDefault());
    }

    public Timestamp(int year, int month, int date, int hrs, int min, int sec, TimeZone tz) {
        synchronized (staticCal) {
            TimeZone defaultZone = TimeZone.getDefault();
            staticCal.setTimeZone(tz);
            staticCal.clear();
            staticCal.set(year, month-1, date, hrs, min, sec);
            time = staticCal.getTime().getTime();
            staticCal.setTimeZone(defaultZone);
        }
    }

    public int getYear() {
        return getField(Calendar.YEAR);
    }

    public int getMonth() {
        return getField(Calendar.MONTH) + 1;
    }

    public int getDate() {
        return getField(Calendar.DATE);
    }

    public int getDayOfWeek() {
        return getField(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
    }

    public int getDayOfYear() {
        return getField(Calendar.DAY_OF_YEAR);
    }

    public int getHours() {
        return getField(Calendar.HOUR_OF_DAY);
    }

    public int getMinutes() {
        return getField(Calendar.MINUTE);
    }

    public int getSeconds() {
        return getField(Calendar.SECOND);
    }

    public long getTimeInMillis() {
        return time;
    }

    public Date getTime() {
        return new Date(getTimeInMillis());
    }

    public Timestamp truncate() {
        return new Timestamp(getYear(), getMonth(), getDate());
    }
    
    public int compareTo(Timestamp that) {
        long diff = getTimeInMillis() - that.getTimeInMillis();
        return diff == 0 ? 0 : diff > 0 ? 1 : -1;
    }

    public boolean before(Timestamp that) {
        return getTimeInMillis() < that.getTimeInMillis();
    }

    public boolean after(Timestamp that) {
        return getTimeInMillis() > that.getTimeInMillis();
    }

    public boolean equals(Object obj) {
        return (obj instanceof Timestamp) &&
               getTimeInMillis() == ((Timestamp)obj).getTimeInMillis();
    }

    public int hashCode() {
        return (int)(time ^ (time >>> 32));
    }

    public int dateCompareTo(Timestamp that) {
        int thisYear = getYear();
        int thatYear = that.getYear();
        if (thisYear > thatYear) {
            return 1;
        } else if (thisYear < thatYear) {
            return -1;
        }

        int thisMonth = getMonth();
        int thatMonth = that.getMonth();
        if (thisMonth > thatMonth) {
            return 1;
        } else if (thisMonth < thatMonth) {
            return -1;
        }

        int thisDate = getDate();
        int thatDate = that.getDate();
        if (thisDate > thatDate) {
            return 1;
        } else if (thisDate < thatDate) {
            return -1;
        }

        return 0;
    }

    public boolean dateBefore(Timestamp that) {
        return dateCompareTo(that) < 0;
    }

    public boolean dateAfter(Timestamp that) {
        return dateCompareTo(that) > 0;
    }

    public boolean dateEquals(Timestamp that) {
        return dateCompareTo(that) == 0;
    }

    public int timeCompareTo(Timestamp that) {
        int thisHours = getHours();
        int thatHours = that.getHours();
        if (thisHours > thatHours) {
            return 1;
        } else if (thisHours < thatHours) {
            return -1;
        }

        int thisMinutes = getMinutes();
        int thatMinutes = that.getMinutes();
        if (thisMinutes > thatMinutes) {
            return 1;
        } else if (thisMinutes < thatMinutes) {
            return -1;
        }

        int thisSeconds = getSeconds();
        int thatSeconds = that.getSeconds();
        if (thisSeconds > thatSeconds) {
            return 1;
        } else if (thisSeconds < thatSeconds) {
            return -1;
        }

        return 0;
    }

    public boolean timeBefore(Timestamp that) {
        return timeCompareTo(that) < 0;
    }

    public boolean timeAfter(Timestamp that) {
        return timeCompareTo(that) > 0;
    }

    public boolean timeEquals(Timestamp that) {
        return timeCompareTo(that) == 0;
    }

    @Expando(name="+")
    public Timestamp add(TimeSpan span) {
        return new Timestamp(getTimeInMillis() + span.getTotalMilliSeconds());
    }

    @Expando(name="-")
    public Timestamp subtract(TimeSpan span) {
        return new Timestamp(getTimeInMillis() - span.getTotalMilliSeconds());
    }

    @Expando(name="-")
    public TimeSpan subtract(Timestamp that) {
        return TimeSpan.difference(this, that);
    }

    public Timestamp addYears(int years) {
        return addField(Calendar.YEAR, years);
    }

    public Timestamp addMonths(int months) {
        return addField(Calendar.MONTH, months);
    }

    public Timestamp addDays(int days) {
        return addField(Calendar.DATE, days);
    }

    public Timestamp addHours(int hours) {
        return addField(Calendar.HOUR_OF_DAY, hours);
    }

    public Timestamp addMinutes(int minutes) {
        return addField(Calendar.MINUTE, minutes);
    }

    public Timestamp addSeconds(int seconds) {
        return addField(Calendar.SECOND, seconds);
    }

    public String formatDate() {
        return formatDate(TimeZone.getDefault(), Locale.getDefault());
    }

    public String formatDate(TimeZone tz) {
        return formatDate(tz, Locale.getDefault());
    }

    public String formatDate(Locale lc) {
        return formatDate(TimeZone.getDefault(), lc);
    }

    public String formatDate(TimeZone tz, Locale lc) {
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM, lc);
        format.setTimeZone(tz);
        return format.format(getTime());
    }

    public String formatTime() {
        return formatTime(TimeZone.getDefault(), Locale.getDefault());
    }

    public String formatTime(TimeZone tz) {
        return formatTime(tz, Locale.getDefault());
    }

    public String formatTime(Locale lc) {
        return formatTime(TimeZone.getDefault(), lc);
    }

    public String formatTime(TimeZone tz, Locale lc) {
        DateFormat format = DateFormat.getTimeInstance(DateFormat.MEDIUM, lc);
        format.setTimeZone(tz);
        return format.format(getTime());
    }

    public String format() {
        return format(TimeZone.getDefault(), Locale.getDefault());
    }

    public String format(TimeZone tz) {
        return format(tz, Locale.getDefault());
    }

    public String format(Locale lc) {
        return format(TimeZone.getDefault(), lc);
    }

    public String format(TimeZone tz, Locale lc) {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, lc);
        format.setTimeZone(tz);
        return format.format(getTime());
    }

    public String toString() {
        return format();
    }

    public int getTimeZoneOffset() {
        synchronized (staticCal) {
            staticCal.setTime(getTime());
            return staticCal.get(Calendar.ZONE_OFFSET) + staticCal.get(Calendar.DST_OFFSET);
        }
    }

    public java.sql.Date getSQLDate() {
        return new java.sql.Date(getTimeInMillis());
    }

    public java.sql.Time getSQLTime() {
        return new java.sql.Time(getTimeInMillis());
    }

    public java.sql.Timestamp getSQLTimestamp() {
        return new java.sql.Timestamp(getTimeInMillis());
    }

    private int getField(int field) {
        synchronized (staticCal) {
            staticCal.setTime(getTime());
            return staticCal.get(field);
        }
    }

    private Timestamp addField(int field, int amount) {
        synchronized (staticCal) {
            staticCal.setTime(getTime());
            staticCal.add(field, amount);
            return new Timestamp(staticCal.getTime().getTime());
        }
    }
}
