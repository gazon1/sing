// Verifies that after a store-recovery event the app has recreated its
// EncryptedSharedPreferences file (the old damaged one is gone, a new one exists).
var pkg = "com.singularity.todo";

var res = execSync("run-as " + pkg + " sh -c 'ls shared_prefs'").toString();
var files = res.split("\n").filter(function(f) { return f.endsWith(".xml"); });

// The damaged file was named something like "singularity_secure_prefs.xml".
// Recovery must have replaced it, not merely deleted it.
var damaged = files.filter(function(f) { return f.indexOf("singularity_secure_prefs") !== -1; });

if (damaged.length === 0) {
  throw new Error("the recovery removed the file instead of replacing it");
}

output.store = damaged.join(", ");
