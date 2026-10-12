$ErrorActionPreference = 'Stop'
$mysqlExecutable = 'E:\web\mysql8\mysql-8.0.46-winx64\bin\mysqld.exe'
$mysqlClient = 'E:\web\mysql8\mysql-8.0.46-winx64\bin\mysql.exe'
$mysqlConfig = 'E:/hnExam/.local/mysql8/my.ini'
$mysqlCredentials = 'E:/hnExam/.local/mysql8/app-client.ini'
if (!(Test-Path -LiteralPath 'E:\hnExam\.local\mysql8\data\auto.cnf')) {
    throw 'Existing MySQL data directory not found; initialization was not attempted.'
}
$mysqlListener = Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue
if (!$mysqlListener) {
    Start-Process -FilePath $mysqlExecutable -ArgumentList ('--defaults-file='+$mysqlConfig) -WindowStyle Hidden | Out-Null
}
$mysqlReady = $false
for ($mysqlAttempt=0; $mysqlAttempt -lt 15; $mysqlAttempt++) {
    & $mysqlClient ('--defaults-file='+$mysqlCredentials) --connect-timeout=2 --batch -e 'SELECT VERSION() AS mysql_version, DATABASE() AS database_name;' 2>$null
    if ($LASTEXITCODE -eq 0) { $mysqlReady = $true; break }
    Start-Sleep -Seconds 1
}
if (!$mysqlReady) { throw 'MySQL connection failed. Inspect E:\hnExam\.local\mysql8\server.err. No database was reset.' }
Write-Output 'MySQL is ready at 127.0.0.1:3306. Application data migration was not performed.'
