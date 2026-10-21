// Sets an Android system property on the device, before the flow starts.
//
// Usage in onFlowStart:
//   onFlowStart:
//     - runScript:
//         file: scripts/setprop.js
//         env:
//           PROP: debug.stub_auth.unconfirmed_signup
//           VALUE: "true"
//
// The property is set through the E2E runner's command bridge: a Maestro
// script has no shell of its own, and the bridge is the only way out of the
// sandbox.
//
// The flow's env entries arrive as bare globals, so PROP and VALUE are read
// as globals and not as env.PROP.
//
// The command bridge is defined in the E2E runner, so running the flow with
// `maestro test` directly cannot set a property; the flow then fails on the
// property's effect rather than passing on a property that was never set.
// The env entries are read into differently named locals on purpose: a `var`
// of the same name would be hoisted, and the hoisted value would shadow the
// global the entry arrived as.
var prop = typeof PROP === "undefined" ? null : PROP;
var value = typeof VALUE === "undefined" ? null : VALUE;

if (!prop || value === null) {
    throw new Error("PROP and VALUE env vars are required");
}

// Runs a command on the host through the E2E runner's command bridge.
//
// A Maestro script has no shell of its own: the sandbox has no `execSync` and
// no `java`, so the only way to reach the device is the HTTP bridge the
// runner starts, whose base URL arrives as the E2E_BRIDGE_URL environment
// entry of the flow.
//
// The helper is repeated in each script that needs it because the sandbox has
// no module loader: a shared helper could not be imported.
function shell(cmd) {
    if (typeof E2E_BRIDGE_URL === "undefined" || E2E_BRIDGE_URL === "") {
        throw new Error(
            "E2E_BRIDGE_URL is not set: this script needs the E2E runner's command bridge. " +
            "Run the flow through scripts/run-maestro-e2e.py, not `maestro test` directly."
        );
    }
    var res = http.get(E2E_BRIDGE_URL + "/shell?cmd=" + encodeURIComponent(cmd));
    var data = JSON.parse(res.body);
    if (!res.ok) {
        // 400: the bridge refused the command before it ran. 500: it ran and
        // failed. The two say different things — one is a wrong command, the
        // other a wrong state on the device — so the message says which.
        throw new Error(
            data.error ||
            ("the command failed with exit " + data.code + ": " + (data.stderr || "").trim())
        );
    }
    return data.stdout || "";
}

shell("setprop " + prop + " " + value);

output.ok = true;
output.prop = prop;
