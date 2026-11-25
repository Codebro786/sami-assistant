# Backs up all Sami data from the phone to $env:USERPROFILE\Documents\Sami-Backup\data\ (private: contains keys, UPI IDs, document photos).
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$dest = "$env:USERPROFILE\Documents\Sami-Backup\data"
New-Item -ItemType Directory -Force $dest | Out-Null
& $adb shell am broadcast -n com.sami.assistant/.system.BackupReceiver | Out-Null
Start-Sleep 3
$stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
cmd /c "`"$adb`" exec-out run-as com.sami.assistant cat files/backup/sami-backup.zip > `"$dest\sami-backup_$stamp.zip`""
Copy-Item "$dest\sami-backup_$stamp.zip" "$dest\sami-backup_latest.zip" -Force
$size = (Get-Item "$dest\sami-backup_latest.zip").Length
"Backup saved: $dest\sami-backup_$stamp.zip ($([math]::Round($size/1KB)) KB)"
