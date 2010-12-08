/*
 * $Id: TimePeriod.java,v 1.3 2009/05/10 16:54:55 danielyuan Exp $
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
import elite.lang.annotation.Data;

/**
 * Represents a time period during a day. Holds information associated with
 * start time and end time. You can also examine duration between start time
 * and end time.
 */
@Data({"startTime", "endTime"})
public class TimePeriod implements Serializable
{
    private Timestamp startTime;
    private Timestamp endTime;

    private static final long serialVersionUID = 1581418757449722066L;

    /**
     * Constructs time period object with start time and end time.
     *
     * @param startTime start time of time period.
     * @param endTime end time of time period.
     * @exception IllegalArgumentException if end time is not after start time.
     */
    public TimePeriod(Timestamp startTime, Timestamp endTime) {
        if (startTime == null || endTime == null)
            throw new NullPointerException();
        if (!endTime.after(startTime))
            throw new IllegalArgumentException("Start time is later than end time");
        initialize(startTime, endTime, null);
    }

    /**
     * Constructs time period object with start time and a duration.
     *
     * @param startTime start time of time period.
     * @param duration add this duration to start time to get end time.
     * @exception IllegalArgumentException if duration is not greater than zero.
     */
    public TimePeriod(Timestamp startTime, TimeSpan duration) {
        if (startTime == null || duration == null)
            throw new NullPointerException();
        initialize(startTime, null, duration);
    }

    /**
     * Constructs time period object with end time and a duration.
     *
     * @param duration subtract this duration from end time to get start time.
     * @param endTime end time of time period.
     * @exception IllegalArgumentException if duration is not greater than zero.
     */
    public TimePeriod(TimeSpan duration, Timestamp endTime) {
        if (duration == null || endTime == null)
            throw new NullPointerException();
        initialize(null, endTime, duration);
    }

    /**
     * Implementation for constructors.
     */
    private void initialize(Timestamp startTime, Timestamp endTime, TimeSpan duration) {
        if (duration != null) {
            if (duration.getTotalMilliSeconds() <= 0)
                throw new IllegalArgumentException("Duration must be greater than zero.");
            if (startTime == null) {
                startTime = endTime.subtract(duration);
            } else if (endTime == null) {
                endTime = startTime.add(duration);
            }
        }

        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * Returns start time of this time period.
     *
     * @return start time of time period.
     */
    public Timestamp getStartTime() {
        return startTime;
    }

    /**
     * Returns end time of this time period.
     *
     * @return end time of time period.
     */
    public Timestamp getEndTime() {
        return endTime;
    }

    /**
     * Returns the duration of time covered by this time period.
     *
     * @return duration of time.
     */
    public TimeSpan getDuration() {
        return TimeSpan.difference(endTime, startTime);
    }

    /**
     * Returns the duration of date convered by this time period.
     *
     * @return duration of date.
     */
    public TimeSpan getDateDuration() {
        return TimeSpan.dateDifference(endTime, startTime);
    }

    /**
     * Returns the duration of time convered by this time period. Only time
     * portion of timestamps are examined.
     *
     * @return duration of time.
     */
    public TimeSpan getTimeDuration() {
        return TimeSpan.timeDifference(endTime, startTime);
    }

    /**
     * Compares this object against the specified object.
     * The result is <code>true</code> if and only if the argument is not
     * <code>null</code> and is a <code>TimePeriod</code> object taht contains
     * the same start time and end time as this object.
     *
     * @param obj the object to compare with.
     * @return <code>true</code> if the objects are same;
     *         <code>false</code> otherwise.
     */
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof TimePeriod) {
            TimePeriod that = (TimePeriod)obj;
            return startTime.equals(that.getStartTime()) && endTime.equals(that.getStartTime());
        } else {
            return false;
        }
    }

    /**
     * Computes a hashcode for this object.
     *
     * @return a hash code value for this object.
     */
    public int hashCode() {
        return startTime.hashCode() ^ endTime.hashCode();
    }

    /**
     * Returns a String object representing this object.
     *
     * @return a string representation of this object.
     */
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("Start time: ")
           .append(startTime.toString())
           .append(" End Time: ")
           .append(endTime.toString())
           .append(" Duration: ")
           .append(getDuration().toString());
        return buf.toString();
    }
}
