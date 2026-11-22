param(
    [string]$Cp,
    [string]$HarnessOut,
    [string]$OldOut,
    [string]$NewOut,
    [int]$Rounds = 5
)
$ErrorActionPreference = 'Continue'
$java = 'C:\Program Files\Zulu\zulu-21\bin\java.exe'
# native cases are controls: the change cannot affect them, so their delta is measurement noise
$cases = @('eight-native', 'one-native', 'one-fast', 'eight-fast', 'asm-fast', 'arg1-fast', 'generic-fast', 'multi16-shared')
function Run-Case([string]$out, [string]$case) {
    $lines = & $java -cp "$Cp;$HarnessOut;$out" harness.Bench $case 2>&1
    $line = ($lines | Where-Object { $_ -match 'ns/post' }) -join ' '
    if ($line -match '([0-9]+\.[0-9]+) ns/post') { return [double]$Matches[1] }
    return [double]::NaN
}
foreach ($case in $cases) {
    $old = @()
    $new = @()
    for ($i = 0; $i -lt $Rounds; $i++) {
        if ($i % 2 -eq 0) {
            $old += Run-Case $OldOut $case
            $new += Run-Case $NewOut $case
        } else {
            $new += Run-Case $NewOut $case
            $old += Run-Case $OldOut $case
        }
    }
    $oldSorted = $old | Where-Object { -not [double]::IsNaN($_) } | Sort-Object
    $newSorted = $new | Where-Object { -not [double]::IsNaN($_) } | Sort-Object
    $oldMed = $oldSorted[[int]($oldSorted.Count / 2)]
    $newMed = $newSorted[[int]($newSorted.Count / 2)]
    $delta = if ($oldMed -gt 0) { (($newMed - $oldMed) / $oldMed) * 100 } else { 0 }
    "{0,-16} old {1,7:0.00}  new {2,7:0.00}  delta {3,6:0.0}%   old=[{4}] new=[{5}]" -f `
        $case, $oldMed, $newMed, $delta, (($old | ForEach-Object { '{0:0.00}' -f $_ }) -join ' '), (($new | ForEach-Object { '{0:0.00}' -f $_ }) -join ' ')
}
