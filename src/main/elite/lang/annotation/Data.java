/*
 * $Id: Data.java,v 1.1 2009/05/11 03:08:10 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package elite.lang.annotation;

import java.lang.annotation.*;

@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Data
{
    // declares data slots used for pattern matching
    String[] value();
}
