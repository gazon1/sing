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
// flag's effect rather than passing on a flag that was never set.
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

// The package whose data directory the flag lives in. It is the app's own, so
// `run-as` is what can write there — and `run-as` only works for a debuggable
// build, which is what the E2E build is.
var pkg = "com.singularity.todo";

// The name of the file, relative to the app's data directory. The stub reads
// the same name; the prefix belongs to the stub, and the flag's own name is
// the only part a flow has to know.
var path = "files/e2e-stub-auth-" + flag;

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

// The flag is on when the file says "true" and off when the file is not
// there at all: the stub reads a missing file as "off", and a file that says
// "false" would say the same thing in a way that has to be cleaned up.
if (value === "true") {
    runAs("echo true > " + path);
} else {
    runAs("rm -f " + path);
}

output.flag = flag;
output.on = value === "true";
