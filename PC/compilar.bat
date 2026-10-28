@echo off
gcc receptor.c -o receptor.exe -O2 -s -lws2_32 -lwinmm
if errorlevel 1 (echo ERROR al compilar) else (echo Listo: receptor.exe)
pause
