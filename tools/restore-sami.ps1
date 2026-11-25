# After reinstalling Sami: pushes the latest PC backup into the app; it is imported on the next app start.
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$src = "$env:USERPROFILE\Documents\Sami-Backup\data\sami-backup_latest.zip"
if (-not (Test-Path $src)) { "No backup found at $src"; exit 1 }
& $adb shell am force-stop com.sami.assistant
& $adb push $src /data/local/tmp/sami-backup.zip | Out-Null
& $adb shell "run-as com.sami.assistant sh -c 'mkdir -p files/restore && cp /data/local/tmp/sami-backup.zip files/restore/sami-backup.zip'"
& $adb shell rm /data/local/tmp/sami-backup.zip
& $adb shell am start -n com.sami.assistant/.MainActivity | Out-Null
"Restored. Re-grant permissions in Sami > Settings (mic, contacts, accessibility, notification access, all files...) and reconnect Gmail."
