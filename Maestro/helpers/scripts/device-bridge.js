// Initializes the shared E2E device command bridge on the Maestro `output` object.
//
// Maestro scripts run in GraalJS with no module loader. This file is loaded once
// per flow (via `runScript`) before any consumer uses it. Consumers then call
// `output.deviceBridge.shell(...)` / `output.deviceBridge.runAs(...)`.
//
// The bridge is the runner's loopback HTTP server (`E2E_BRIDGE_URL`); it runs
// allowlisted device commands on the host side. This script only packages the
// shared transport + the app-UID `runAs` shape used by the existing flows.
//
// Runs on GraalJS, Maestro's embedded JS engine.
(function () {
    if (typeof output === "undefined") {
        throw new Error("Maestro output is not available.");
    }
    if (typeof output.deviceBridge !== "undefined" && output.deviceBridge !== null) {
        return;
    }

    var bridge = {};

    // Runs a command on the host through the E2E runner's command bridge.
    //
    // A Maestro script has no shell of its own: the sandbox has no `execSync` and
    // no `java`, so the only way to reach the device is the HTTP bridge the
    // runner starts, whose base URL arrives as the E2E_BRIDGE_URL environment
    // entry of the flow.
    bridge.shell = function (cmd) {
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
    };

    bridge.runAs = function (command) {
        // The bridge matches this shape whole, so the script inside the quotes
        // must not carry a single quote of its own.
        return bridge.shell("run-as com.singularity.todo sh -c '" + command + "'");
    };

    output.deviceBridge = bridge;
})();