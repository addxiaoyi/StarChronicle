$ErrorActionPreference = 'Stop'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Select-Object ProcessId, ParentProcessId, ExecutablePath, CommandLine |
  ConvertTo-Json -Depth 3
