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
// The shared client is initialized by `device-bridge.js` before this script
// runs (flow-scoped via Maestro `output.deviceBridge`).
//
// Runs on GraalJS, Maestro's embedded JS engine.
var store = "shared_prefs/singularity_secure_prefs.xml";
var marker = "not-a-keyset";

var listed = output.deviceBridge.runAs("ls shared_prefs");
var files = listed.split("\n").filter(function (f) { return f.endsWith(".xml"); });
var name = store.split("/").pop();

if (files.indexOf(name) === -1) {
    throw new Error("the session store is not on the device: the app has not written a session yet");
}

// Overwrite the file, keeping its name: the recovery looks for the same name.
output.deviceBridge.runAs("echo " + marker + " > " + store);

output.damaged = name;
