// Verifies that the app wrote to its encrypted store again.
//
// The store the damage destroyed was erased by the app's recovery, and the
// file comes back only when the app next has something to store. The file
// being there with something in it is therefore the evidence that the store
// is usable again: it did not exist between the damage and the next write, so
// it is the app's own write that put it back.
//
// The entries are counted rather than read. An encrypted store encrypts its
// keys as well as its values, so what is in the file cannot be named from
// outside; what can be counted is how many entries it holds.
//
// The two keysets the store keeps for itself are counted apart: they are
// there whatever the app stores, so counting them would say "the store holds
// something" about an empty store. What is left over is what the app wrote —
// a session is four of those.
//
// The commands run on the host through the E2E runner's command bridge: a
// Maestro script has no shell of its own, and `run-as` has to be reached
// from the host.
//
// Runs on GraalJS, Maestro's embedded JS engine.
var pkg = "com.singularity.todo";
var store = "shared_prefs/singularity_secure_prefs.xml";

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
    throw new Error("the app did not write its session store: the store is not usable");
}

var content = runAs("cat " + store);
if (content.indexOf("<map") === -1) {
    throw new Error("the store file is not a preferences file: " + content);
}

var entries = content.split("<string").length - 1;
var keysets = content.split("__androidx_security_crypto_encrypted_prefs_").length - 1;
var written = entries - keysets;
if (written < 1) {
    throw new Error("the store holds nothing of the app's own: the app did not write to it");
}

output.store = name;
output.entries = written;
