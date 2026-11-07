@echo off
chcp 65001 >nul
setlocal

echo ========================================
echo  MiBackscreen 附加模块诊断
echo ========================================
echo.

where adb >nul 2>&1
if errorlevel 1 (
    echo [错误] 找不到 adb。
    echo 请把 adb.exe 放到本目录，或加入 PATH 后重试。
    pause
    exit /b 1
)

echo [1/4] 检查设备连接...
adb devices
echo.
echo 如果上面没有列出你的设备，请先在手机上确认 USB 调试弹窗。
echo.
pause

echo [2/4] 推送诊断脚本...
adb push "%~dp0check_mount.sh" /data/local/tmp/
if errorlevel 1 (
    echo [错误] 推送失败。
    pause
    exit /b 1
)

echo.
echo [3/4] 执行诊断（手机上会弹出 root 授权，请点允许）...
adb shell su -c "sh /data/local/tmp/check_mount.sh"

echo.
echo [4/4] 取回结果...
adb pull /sdcard/mibackscreen_check.txt "%~dp0mibackscreen_check.txt"
if errorlevel 1 (
    echo 尝试从备用位置取回...
    adb pull /data/local/tmp/mibackscreen_check.txt "%~dp0mibackscreen_check.txt"
)

echo.
echo ========================================
echo  完成！
echo  结果文件保存在本目录：mibackscreen_check.txt
echo  请把这个文件发回。
echo ========================================
pause
