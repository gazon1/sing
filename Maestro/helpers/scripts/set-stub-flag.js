// Turns one of the E2E build's auth-stub flags on or off.
//
// Usage in onFlowStart:
//   - runScript:
//         file: scripts/set-stub-flag.js
//         env:
//           FLAG: signin_failure
//           VALUE: "true"
//
// and in onFlowComplete, with VALUE "false", to hand the flag back.
//
// ## Why a file and not a system property
//
// The stub used to read `debug.stub_auth.*` through
// `Runtime.getRuntime().exec(arrayOf("getprop", ...))`. The app process is not
// allowed to run that binary, so the read failed, the flag was read as "off",
// and every flow that drove the stub through a flag failed on the app's
// behaviour instead of on the reason. The flag is a file inside the app's own
// data directory now: the app reads it by path, and this script writes it
// there through `run-as`.
//
// The write goes through the E2E runner's command bridge: a Maestro script has
// no shell of its own, and the bridge is the only way out of the sandbox.
//
// The command bridge is defined in the E2E runner, so running the flow with
// `maestro test` directly cannot set a flag; the flow then fails on the
// flag's effect rather than passing on a flag that was never set. The shared
// bridge client is initialized by `device-bridge.js` before this script runs.
//
// The flow's env entries arrive as bare globals, so FLAG and VALUE are read
// as globals and not as env.FLAG.
//
// The env entries are read into differently named locals on purpose: a `var`
// of the same name would be hoisted, and the hoisted value would shadow the
// global the entry arrived as.
var flag = typeof FLAG === "undefined" ? null : FLAG;
var value = typeof VALUE === "undefined" ? null : VALUE;

if (!flag || value === null) {
    throw new Error("FLAG and VALUE env vars are required");
}

// The package whose data directory the flag lives in. The shared bridge runs
// `run-as` as the app UID (com.singularity.todo).

// The name of the file, relative to the app's data directory. The stub reads
// the same name; the prefix belongs to the stub, and the flag's own name is
// the only part a flow has to know.
var path = "files/e2e-stub-auth-" + flag;

// The shared client is initialized by `device-bridge.js` before this script
// runs (flow-scoped via Maestro `output.deviceBridge`).
//
// The flag is on when the file says "true" and off when the file is not
// there at all: the stub reads a missing file as "off", and a file that says
// "false" would say the same thing in a way that has to be cleaned up.
if (value === "true") {
    output.deviceBridge.runAs("echo true > " + path);
} else {
    output.deviceBridge.runAs("rm -f " + path);
}

output.flag = flag;
output.on = value === "true";
