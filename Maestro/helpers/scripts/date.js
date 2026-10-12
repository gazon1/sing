// Reads the host system date (not the emulator clock). JS executes in the
// Maestro CLI process on the host, not inside the Android runtime.
//
// Consumed by the three calendar/* flows. Exports here must stay limited to
// what those flows read — a dead export is a thing to keep in sync for nothing.
//
// ES5 compatible: no arrow functions, no const/let, no template literals.
// Tested with Rhino 1.7 (Maestro's embedded JS engine).
var m = ['January', 'February', 'March', 'April', 'May', 'June',
         'July', 'August', 'September', 'October', 'November', 'December'];
var d = new Date();
var monthTitle = m[d.getMonth()] + ' ' + d.getFullYear();
var day15Title = m[d.getMonth()] + ' 15, ' + d.getFullYear();
var lastDay = String(new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate());

output.monthTitle = monthTitle;
output.day15Title = day15Title;
output.lastDay = lastDay;
