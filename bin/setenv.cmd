@echo off

rem set JAVA_HOME variable here, for example:
rem set JAVA_HOME=D:\jdk1.5.0

cd ..
set ELITE_HOME=%CD%

if not "%JAVA_HOME%" == "" goto GOT_JAVA_HOME
set JAVA_RUN=java
goto START

:GOT_JAVA_HOME
set JAVA_RUN="%JAVA_HOME%\bin\java"

:START
@REM append the *.jar and *.zip in the LIB_CLASSPATH variable
set LOCALCLASSPATH=
for %%i in ("%ELITE_HOME%\lib\*.jar") do call "%ELITE_HOME%\bin\lcp.cmd" %%i
set LIB_CLASSPATH=%LOCALCLASSPATH%

set LOCALCLASSPATH=
for %%i in ("%ELITE_HOME%\lib\*.zip") do call "%ELITE_HOME%\bin\lcp.cmd" %%i
set LIB_CLASSPATH=%LIB_CLASSPATH%%LOCALCLASSPATH%

set CLASSPATH=%ELITE_HOME%\classes;%LIB_CLASSPATH%;%CLASSPATH%

set PATH=.;%ELITE_HOME%\bin;%JAVA_HOME%\bin;%PATH%

cd bin
