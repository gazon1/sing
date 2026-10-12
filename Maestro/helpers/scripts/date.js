// Reads the host system date (not the emulator clock). JS executes in the
// Maestro CLI process on the host, not inside the Android runtime.
//
// ES5 compatible: no arrow functions, no const/let, no template literals.
// Tested with Rhino 1.7 (Maestro's embedded JS engine).
var m = ['January', 'February', 'March', 'April', 'May', 'June',
         'July', 'August', 'September', 'October', 'November', 'December'];
var d = new Date();
var monthTitle = m[d.getMonth()] + ' ' + d.getFullYear();
var day15Title = m[d.getMonth()] + ' 15, ' + d.getFullYear();
var firstOfMonth = d.getFullYear() + '-' +
    String(d.getMonth() + 1).replace(/^(.)$/, '0$1') + '-01';
var lastDay = String(new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate());

output.monthTitle = monthTitle;
output.day15Title = day15Title;
output.firstOfMonth = firstOfMonth;
output.lastDay = lastDay;
// Unique-per-run suffix for seeded entity titles. Room survives between Maestro
// runs on the same emulator, so a fixed seed title collides with the previous
// run's copy — and a flow that mutates the task (dates it, then asserts it left
// its original bucket) then fails against its own predecessor's leftovers.
output.stamp = String(d.getTime());
