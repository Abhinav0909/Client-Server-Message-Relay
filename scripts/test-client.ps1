<#
.SYNOPSIS
    Minimal interactive TCP client for manually exercising the relay,
    without needing nc/telnet (neither ships with Windows by default).
    Uses only built-in .NET classes, nothing to install.

.EXAMPLE
    ./scripts/test-client.ps1
    ./scripts/test-client.ps1 -Port 5555

.USAGE
    Type a JSON frame and press Enter, e.g.:
        {"op":"REGISTER","clientId":"alice"}
        {"op":"SEND","msgId":"m1","to":"bob","payload":"hi"}
    Incoming frames (e.g. an async DELIVER from another client) print
    as soon as they arrive, no need to press Enter to see them.

    The server closes the connection after a protocol-level error (e.g.
    a frame that isn't valid JSON at all). That's the server's own
    validation, not a bug in this script. This script surfaces that
    clearly instead of crashing: it prints why the connection ended and
    stops you from typing into a dead socket. Restart the script to
    reconnect.

    Type 'exit' to quit.
#>
param(
    [string]$HostName = "localhost",
    [int]$Port = 5555
)

try {
    $client = New-Object System.Net.Sockets.TcpClient($HostName, $Port)
    $stream = $client.GetStream()
    $writer = New-Object System.IO.StreamWriter($stream)
    $writer.AutoFlush = $true
    $reader = New-Object System.IO.StreamReader($stream)
} catch {
    Write-Host "Could not connect to ${HostName}:${Port} - is the server running?" -ForegroundColor Red
    Write-Host "($($_.Exception.Message))" -ForegroundColor Red
    exit 1
}

# Shared across the main runspace and the listener runspace below (a
# synchronized hashtable is safe to read/write from both). Tracks whether
# the connection is still usable, so the main loop can stop trying to
# write into a socket the server already closed, instead of throwing.
$sharedState = [hashtable]::Synchronized(@{ Alive = $true })

# Blocks on ReadLine() and prints each frame the moment it arrives, instead
# of only noticing it on the next keypress. Runs in its own PowerShell
# runspace, not just a background .NET thread: this same runspace's
# Read-Host call below holds the main runspace's pipeline for as long as
# it's waiting on input, so a scriptblock sharing that runspace would
# never get a chance to run until Read-Host returns, defeating the point.
# A separate runspace has its own independent pipeline and isn't blocked
# by that.
$listenerRunspace = [runspacefactory]::CreateRunspace()
$listenerRunspace.Open()
$listenerRunspace.SessionStateProxy.SetVariable('reader', $reader)
$listenerRunspace.SessionStateProxy.SetVariable('sharedState', $sharedState)
$listenerPs = [powershell]::Create()
$listenerPs.Runspace = $listenerRunspace
[void]$listenerPs.AddScript({
    # Read-Host has already drawn the ">: " prompt and is sitting on that
    # line waiting for input. Printing straight over it can visually
    # collide with whatever's there. Clear the current line first, print
    # on a fresh line, then redraw the prompt so the screen stays coherent
    # (any characters you'd already typed on that line are lost from the
    # display, though not from what Read-Host will still return). Falls
    # back to a plain print if the console host doesn't support cursor
    # control (e.g. redirected output).
    function Write-Line($text, $color) {
        try {
            [Console]::SetCursorPosition(0, [Console]::CursorTop)
            [Console]::Write(' ' * [Console]::WindowWidth)
            [Console]::SetCursorPosition(0, [Console]::CursorTop)
        } catch {
            [Console]::WriteLine()
        }
        [Console]::ForegroundColor = $color
        [Console]::WriteLine($text)
        [Console]::ResetColor()
        try { [Console]::Write('>: ') } catch {}
    }

    while ($true) {
        try {
            $line = $reader.ReadLine()
        } catch {
            # The connection was reset/aborted rather than closed cleanly
            # (e.g. the server process died, or the OS tore down the
            # socket). Report it instead of the listener just going quiet.
            $sharedState.Alive = $false
            Write-Line "[connection lost: $($_.Exception.Message)]" ([ConsoleColor]::Red)
            break
        }
        if ($null -eq $line) {
            # Clean close from the other end. This is what happens after
            # the server sends an ERROR frame for a protocol violation
            # (malformed JSON, oversized frame, etc.). It's the server's
            # own validation working as designed, not a client bug. Any
            # such ERROR frame itself already printed above this, as a
            # normal incoming frame, before the connection closed.
            $sharedState.Alive = $false
            Write-Line "[server closed the connection]" ([ConsoleColor]::Red)
            break
        }
        Write-Line "< $line" ([ConsoleColor]::Cyan)
    }
})
$listenerHandle = $listenerPs.BeginInvoke()

Write-Host "Connected to ${HostName}:${Port}"
Write-Host 'Type a JSON frame and press Enter, e.g. {"op":"REGISTER","clientId":"alice"}'
Write-Host "Incoming frames print automatically. Type 'exit' to quit."
Write-Host ""

while ($true) {
    $userInput = Read-Host ">"
    if ($userInput -eq "exit") { break }
    if ($userInput -eq "") { continue }

    if (-not $sharedState.Alive) {
        Write-Host "Not connected - the server already closed this connection (see the message above)." -ForegroundColor Yellow
        Write-Host "Restart the script to reconnect." -ForegroundColor Yellow
        continue
    }

    try {
        $writer.WriteLine($userInput)
    } catch {
        # The write can succeed once even after the remote end has closed
        # (TCP doesn't notify the local side immediately), so this may not
        # fire on the very first write after a close, but once it does,
        # tell the user plainly instead of showing a raw .NET stack trace.
        $sharedState.Alive = $false
        Write-Host "Connection lost while sending: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "Restart the script to reconnect." -ForegroundColor Yellow
    }
}

try { $client.Close() } catch {}
try { $listenerPs.Stop() } catch {}
try { $listenerPs.Dispose() } catch {}
try { $listenerRunspace.Close() } catch {}
