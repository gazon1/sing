// Verifies the app's encrypted store after a damage event.
//
// The damage wrote a known marker into the store file. The app's recovery
// erases a store it cannot read, so the marker must be gone: either the file
// is gone — the recovery erased it — or it is a preferences file the app wrote
// and can read again. What must not be true is a store that still holds the
// marker, because that would mean the app went on serving a store it could
// not decrypt.
//
// The store is checked for its shape as well as for the marker. A file that is
// not a preferences file is not a store the app can use, whatever its name.
//
// The commands run on the host through the E2E runner's command bridge: a
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

var damaged = [];
for (var i = 0; i < files.length; i++) {
    var content = runAs("cat shared_prefs/" + files[i]);
    if (content.indexOf(marker) !== -1) {
        damaged.push(files[i]);
    }
}

if (damaged.length > 0) {
    throw new Error("the store still holds the damaged content: " + damaged.join(", "));
}

var name = store.split("/").pop();
if (files.indexOf(name) === -1) {
    // The recovery erased the store it could not read, and the app has not
    // written to it since. That is the recovery doing its job: the data was
    // unreadable, and an unreadable store is not worth keeping.
    output.store = "erased";
} else {
    var storeContent = runAs("cat " + store);
    if (storeContent.indexOf("<map") === -1) {
        throw new Error("the store file is not a preferences file: " + storeContent);
    }
    output.store = name;
}
