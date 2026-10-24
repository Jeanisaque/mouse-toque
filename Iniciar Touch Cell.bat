@echo off
chcp 65001 >nul
title Touch Cell - Criado por Isaque Andrade e Joel Andrade
cd /d "%~dp0"

rem ---- 1) Python que ja vem junto (versao portatil) ----
set "ARQ=%PROCESSOR_ARCHITECTURE%"
if defined PROCESSOR_ARCHITEW6432 set "ARQ=%PROCESSOR_ARCHITEW6432%"
set "PYPORT="
if /i "%ARQ%"=="AMD64" if exist "python\x64\python.exe" set "PYPORT=python\x64\python.exe"
if /i "%ARQ%"=="ARM64" if exist "python\x64\python.exe" set "PYPORT=python\x64\python.exe"
if not defined PYPORT if exist "python\x86\python.exe" set "PYPORT=python\x86\python.exe"
if defined PYPORT (
    "%PYPORT%" mouse_toque.py
    goto fim
)

rem ---- 2) Python instalado no PC ----
python --version >nul 2>&1
if %errorlevel%==0 (
    python mouse_toque.py
    goto fim
)
py --version >nul 2>&1
if %errorlevel%==0 (
    py mouse_toque.py
    goto fim
)

echo.
echo  Esta pasta nao tem o Python portatil e o PC nao tem Python.
echo  Baixe o Touch Cell Portatil (nao precisa instalar nada):
echo.
echo  https://github.com/Jeanisaque/mouse-toque/releases/latest/download/TouchCell-Portatil.zip
echo.
start https://github.com/Jeanisaque/mouse-toque/releases/latest/download/TouchCell-Portatil.zip

:fim
echo.
pause
