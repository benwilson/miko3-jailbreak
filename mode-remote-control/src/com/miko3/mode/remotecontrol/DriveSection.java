package com.miko3.mode.remotecontrol;

/**
 * The drive-control section of the served page (R5/R16/R17, U7):
 * hold-to-drive buttons that repeat the drive command every 300ms while
 * held (comfortably under the server's ~750ms watchdog, so holding a
 * button is its own keepalive) and send an explicit stop on release, plus
 * an "Exit mode" control that releases the lease and returns to the
 * launcher (R16) — reachable without physical access to the robot.
 */
final class DriveSection {
    private DriveSection() {
    }

    static final String HTML =
            "<section id=\"drive\">"
            + "<h2>Drive</h2>"
            + "<p id=\"drive-status\" role=\"status\"></p>"
            + "<div class=\"grid\">"
            + "<button id=\"btn-left\" data-linear=\"0\" data-angular=\"-20\">&#8630; Left</button>"
            + "<button id=\"btn-forward\" data-linear=\"20\" data-angular=\"0\">&#8593; Forward</button>"
            + "<button id=\"btn-right\" data-linear=\"0\" data-angular=\"20\">&#8631; Right</button>"
            + "</div>"
            + "<div class=\"grid\">"
            + "<button id=\"btn-back\" data-linear=\"-20\" data-angular=\"0\">&#8595; Back</button>"
            + "</div>"
            + "<button id=\"btn-exit\" class=\"secondary\">Exit mode</button>"
            + "<script>"
            + "(function(){"
            + "var status=document.getElementById('drive-status');"
            + "var repeatTimer=null;"
            + "function drive(linear,angular){"
            + "fetch('/drive?linear='+linear+'&angular='+angular+'&ct='+encodeURIComponent(CLIENT_TOKEN))"
            + ".then(function(r){"
            + "if(r.status===409){status.textContent='Control taken by another connection.';}"
            + "else if(!r.ok){status.textContent='Drive command failed.';}"
            + "else{status.textContent='';}"
            + "})"
            + ".catch(function(){status.textContent='Drive command failed (network error).';});"
            + "}"
            + "function startHold(btn){"
            // A second pointerdown (e.g. multi-touch, or dragging from one button to
            // another) before the first pointerup would otherwise overwrite the single
            // repeatTimer reference with no way left to clear the first interval — it
            // would then keep sending drive commands every 300ms forever, even after
            // the user believes they released. Clearing any existing hold first (which
            // also sends its own stop) guarantees at most one interval is ever live.
            + "stopHold();"
            + "var linear=btn.getAttribute('data-linear');"
            + "var angular=btn.getAttribute('data-angular');"
            + "drive(linear,angular);"
            + "repeatTimer=setInterval(function(){drive(linear,angular);},300);"
            + "}"
            + "function stopHold(){"
            + "if(repeatTimer){clearInterval(repeatTimer);repeatTimer=null;drive(0,0);}"
            + "}"
            + "['btn-left','btn-forward','btn-right','btn-back'].forEach(function(id){"
            + "var btn=document.getElementById(id);"
            + "btn.addEventListener('pointerdown',function(){startHold(btn);});"
            + "btn.addEventListener('pointerup',stopHold);"
            + "btn.addEventListener('pointerleave',stopHold);"
            + "btn.addEventListener('pointercancel',stopHold);"
            + "});"
            + "document.getElementById('btn-exit').addEventListener('click',function(){"
            // Previously just updated the status text and left the operator sitting
            // on this same page — the request succeeded server-side (the robot's own
            // screen did return to the launcher) but nothing told a *remote* browser
            // to go anywhere, so it looked broken from there. Navigates to the
            // launcher either way (success or failure): if /exit genuinely failed,
            // being on the launcher's own page to retry is still more useful than
            // being stuck here.
            + "status.textContent='Exiting...';"
            + "fetch('/exit?ct='+encodeURIComponent(CLIENT_TOKEN))"
            + ".catch(function(){})"
            + ".then(function(){window.location.href='https://'+location.hostname+':8443/';});"
            + "});"
            + "})();"
            + "</script>"
            + "</section>";
}
