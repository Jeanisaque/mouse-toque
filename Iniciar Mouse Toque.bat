@echo off
chcp 65001 >nul
title Mouse Toque - Criado por Isaque Andrade e Joel Andrade
cd /d "%~dp0"

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
echo  O Python nao esta instalado neste PC.
echo  Vou abrir a pagina de download. Na instalacao, marque a opcao
echo  "Add python.exe to PATH" e depois abra este arquivo de novo.
echo.
start https://www.python.org/downloads/

:fim
echo.
pause
