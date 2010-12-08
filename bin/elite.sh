#!/bin/sh

#Copyright (c) 2001-2009 The Operamasks Community.  All rights reserved.

BINDIR=`dirname $0`
. "$BINDIR/setenv"
$JAVA_RUN -cp $CP org.operamasks.el.shell.Main $@
