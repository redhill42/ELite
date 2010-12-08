@ECHO OFF
REM Copyright (c) 2001-2009 The Operamasks Community.  All rights reserved.
if "%OS%" == "Windows_NT" setlocal

call ./setenv.cmd

SET ARGS=
:GET_ARGS
IF ""%1"" == """" GOTO RUN
SET ARGS=%ARGS% %1
SHIFT
GOTO GET_ARGS

:RUN
%JAVA_RUN%  org.operamasks.el.shell.Main %ARGS%

if "%OS%" == "Windows_NT" endlocal