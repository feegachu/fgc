param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('reset', 'load', 'rebuild', 'backup', 'restore')]
    [string]$Action,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Options
)
$ErrorActionPreference = 'Stop'
& python -X utf8 "$PSScriptRoot/demo.py" local $Action @Options
if ($LASTEXITCODE -ne 0) { throw "Demo command failed (exit $LASTEXITCODE)." }
