@echo off
cd /d "%~dp0"

:: Find Python
where python >nul 2>&1
if %errorlevel%==0 (
    set PYTHON=python
) else (
    where py >nul 2>&1
    if %errorlevel%==0 (
        set PYTHON=py -3
    ) else (
        echo Python not found. Please install Python 3.10+ and add it to PATH.
        pause
        exit /b 1
    )
)

if not exist "venv" (
    echo Creating virtual environment...
    %PYTHON% -m venv venv
)
call venv\Scripts\activate.bat
pip install -r requirements.txt --quiet
python app.py
pause
