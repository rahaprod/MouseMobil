@echo off
chcp 65001 >nul
python -m pip install --quiet -r "%~dp0requirements.txt"
python "%~dp0mousemobile_server.py"
pause
