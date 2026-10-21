// Damages the app's encrypted session store.
//
// The store is the file the app's secure storage writes. Overwriting it leaves
// a file Tink can no longer decrypt, which is the state a reinstall leaves
// behind: the file comes back from a backup, the keystore key does not.
//
// The damage is by content and not by key: deleting the keystore entry needs
// a helper inside the app's UID, and overwriting the file reaches the same
// recovery without one.
//
// `run-as` only works for a debuggable build, so the E2E build is a
// requirement. A build that is not debuggable fails here, with a message,
// rather than damaging nothing and letting the flow pass on its next
// assertion.
//
// The command runs on the host through the E2E runner's command bridge: a
// Maestro script has no shell of its own, and `run-as` has to be reached
// from the host.
//
// Runs on GraalJS, Maestro's embedded JS engine.
var pkg = "com.singularity.todo";
var store = "shared_prefs/singularity_secure_prefs.xml";
var marker = "not-a-keyset";

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

function runAs(command) {
    // The bridge matches this shape whole, so the script inside the quotes
    // must not carry a single quote of its own.
    return shell("run-as " + pkg + " sh -c '" + command + "'");
}

var listed = runAs("ls shared_prefs");
var files = listed.split("\n").filter(function (f) { return f.endsWith(".xml"); });
var name = store.split("/").pop();

if (files.indexOf(name) === -1) {
    throw new Error("the session store is not on the device: the app has not written a session yet");
}

// Overwrite the file, keeping its name: the recovery looks for the same name.
runAs("echo " + marker + " > " + store);

output.damaged = name;
