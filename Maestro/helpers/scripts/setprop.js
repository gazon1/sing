// Sets an Android system property via adb before the flow starts.
//
// Usage in onFlowStart:
//   onFlowStart:
//     - runScript:
//         name: scripts/setprop.js
//         env:
//           PROP: debug.stub_auth.unconfirmed_signup
//           VALUE: "true"
//
// Requires adb in PATH and a rooted emulator (or emulator with SELinux permissive).
// On CI emulators this typically works; on a real device it may require root.

var prop = env.PROP;
var value = env.VALUE;

if (!prop || value === undefined) {
    output.error = "PROP and VALUE env vars are required";
} else {
    try {
        var cmd = java.lang.Runtime.getRuntime().exec(["adb", "shell", "setprop", prop, value]);
        var exitCode = cmd.waitFor();
        if (exitCode !== 0) {
            var stderr = new java.io.BufferedReader(
                new java.io.InputStreamReader(cmd.getErrorStream())
            ).readLine();
            output.error = "setprop failed with exit " + exitCode + ": " + stderr;
        } else {
            output.ok = true;
        }
    } catch (e) {
        output.error = "setprop threw: " + e.toString();
    }
}
