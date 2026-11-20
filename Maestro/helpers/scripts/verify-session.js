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
// a session is one of those, because the credential store keeps a session in a
// single value.
//
// The commands run on the host through the E2E runner's command bridge: a
// Maestro script has no shell of its own, and `run-as` has to be reached
// from the host.
//
// The shared client is initialized by `device-bridge.js` before this script
// runs (flow-scoped via Maestro `output.deviceBridge`).
//
// Runs on GraalJS, Maestro's embedded JS engine.
var store = "shared_prefs/singularity_secure_prefs.xml";

var listed = output.deviceBridge.runAs("ls shared_prefs");
var files = listed.split("\n").filter(function (f) { return f.endsWith(".xml"); });
var name = store.split("/").pop();

if (files.indexOf(name) === -1) {
    throw new Error("the app did not write its session store: the store is not usable");
}

var content = output.deviceBridge.runAs("cat " + store);
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
