/*
 * $Id: PackageCollector.java,v 1.1 2009/03/22 08:37:26 danielyuan Exp $
 *
 * Copyright (C) 2000-2009 Apusic Systems, Inc.
 * All rights reserved
 */

package org.operamasks.el.resolver;

import java.util.List;

public interface PackageCollector
{
    public List<String> findPackages();
}
