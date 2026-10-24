@echo off
chcp 65001 >nul
title Touch Cell - Criado por Isaque Andrade e Joel Andrade
cd /d "%~dp0"

rem O programa fica na subpasta "programa" (ou ao lado deste arquivo, nas versoes antigas)
set "PASTA=programa"
if not exist "programa\mouse_toque.py" set "PASTA=."
set "APP=%PASTA%\mouse_toque.py"

rem ---- 1) Python que ja vem junto (Touch Cell Portatil) ----
set "ARQ=%PROCESSOR_ARCHITECTURE%"
if defined PROCESSOR_ARCHITEW6432 set "ARQ=%PROCESSOR_ARCHITEW6432%"
set "PYPORT="
if /i "%ARQ%"=="AMD64" if exist "%PASTA%\python\x64\python.exe" set "PYPORT=%PASTA%\python\x64\python.exe"
if /i "%ARQ%"=="ARM64" if exist "%PASTA%\python\x64\python.exe" set "PYPORT=%PASTA%\python\x64\python.exe"
if not defined PYPORT if exist "%PASTA%\python\x86\python.exe" set "PYPORT=%PASTA%\python\x86\python.exe"
if defined PYPORT (
    "%PYPORT%" "%APP%"
    goto fim
)

rem ---- 2) Python instalado no PC ----
python --version >nul 2>&1
if %errorlevel%==0 (
    python "%APP%"
    goto fim
)
py --version >nul 2>&1
if %errorlevel%==0 (
    py "%APP%"
    goto fim
)

echo.
echo  Este PC nao tem Python.
echo  Baixe o Touch Cell Portatil (nao precisa instalar nada):
echo.
echo  https://github.com/Jeanisaque/mouse-toque/releases/latest/download/TouchCell-Portatil.zip
echo.
start https://github.com/Jeanisaque/mouse-toque/releases/latest/download/TouchCell-Portatil.zip

:fim
echo.
pause
