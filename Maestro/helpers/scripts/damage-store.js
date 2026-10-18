// Damages every EncryptedSharedPreferences XML file in the app's shared_prefs directory.
// This simulates a store that Tink can no longer decrypt: the keyset is intact but the
// ciphertext is invalid.  A fresh process (launchApp after stopApp) will recover by
// replacing the file; the same process would keep serving the in-memory cache.
var pkg = "com.singularity.todo";

// List files in shared_prefs via run-as (runs as the app's UID).
var res = execSync("run-as " + pkg + " sh -c 'ls shared_prefs'").toString();
var files = res.split("\n").filter(function(f) { return f.endsWith(".xml"); });

if (files.length === 0) {
  throw new Error("no encrypted prefs file to damage; the store was never written");
}

// Overwrite each file with garbage.  This keeps the filename so the recovery path
// (which looks for the same filename) can find it.
for (var i = 0; i < files.length; i++) {
  execSync("run-as " + pkg + " sh -c 'echo not-a-keyset > shared_prefs/" + files[i] + "'");
}

output.files = files.join(", ");
