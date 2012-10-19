#!/bin/sh

BINDIR=`dirname $0`
. "$BINDIR/setenv"
$JAVA_RUN -cp $CP org.operamasks.el.shell.Main $@
