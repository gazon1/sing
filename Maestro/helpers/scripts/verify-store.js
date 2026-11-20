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
// The shared client is initialized by `device-bridge.js` before this script
// runs (flow-scoped via Maestro `output.deviceBridge`).
//
// Runs on GraalJS, Maestro's embedded JS engine.
var store = "shared_prefs/singularity_secure_prefs.xml";
var marker = "not-a-keyset";

var listed = output.deviceBridge.runAs("ls shared_prefs");
var files = listed.split("\n").filter(function (f) { return f.endsWith(".xml"); });

var damaged = [];
for (var i = 0; i < files.length; i++) {
    var content = output.deviceBridge.runAs("cat shared_prefs/" + files[i]);
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
    var storeContent = output.deviceBridge.runAs("cat " + store);
    if (storeContent.indexOf("<map") === -1) {
        throw new Error("the store file is not a preferences file: " + storeContent);
    }
    output.store = name;
}
