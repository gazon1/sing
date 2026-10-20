// The dates the flows build their expectations from.
//
// The app renders the month, the day and the weekday from the *device's* clock
// and time zone, so a flow's expectation has to come from the same clock. The
// emulator's clock follows the host's, and this script runs in the Maestro
// process on the host, so the host's date is the device's date.
//
// What this script deliberately does NOT produce is a formatted date string.
// "October 2026" and "October 15, 2026" are the app's wording, in the app's
// word order, in the app's language: a flow that asserts them asserts the
// app's copy, and the copy is the app's to change. The day cell's testTag
// carries the same fact as data — `calendar_day_2026_10_15` — and data cannot
// be reworded.
//
// Consumed by the calendar/* flows and by the flows that pick a day in the
// Material3 date picker. Exports here must stay limited to what those flows
// read — a dead export is a thing to keep in sync for nothing.
//
// ES5 compatible: no arrow functions, no const/let, no template literals.
// Tested with Rhino 1.7 (Maestro's embedded JS engine).
function pad2(n) {
    return (n < 10 ? "0" : "") + n;
}

var d = new Date();
var year = d.getFullYear();
// Date's months are 0-based and the cell tag is not, so the month is carried
// as the 1-based number it is written as.
var month = d.getMonth() + 1;

// The last day of the month this script reads. Date.UTC takes a 0-based
// month, and `month` here is already "the month after the one being asked
// about", so the 0th of that month is the last day of the one before it.
var lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();

// The testTag of the day cell of the given day of the current month.
function cell(dayNumber) {
    return "calendar_day_" + year + "_" + pad2(month) + "_" + pad2(dayNumber);
}

output.TODAY_CELL = cell(d.getDate());
output.FIRST_CELL = cell(1);
output.LAST_CELL = cell(lastDay);
output.DAY15_CELL = cell(15);

// The day of the month as a bare number. The Material3 date picker writes the
// day into the accessibility description of a day cell together with the
// month name and the weekday, and those two are the library's copy in the
// device's language. The number is the part the app does not write, so a
// flow can address a day by it.
output.TODAY_DAY = String(d.getDate());
