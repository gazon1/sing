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

// The testTag of the timeline section of a day.
//
// The timeline's section ids carry the date they cover ("day:2026-09-16"), and
// the tag is that id as a slug. A flow that wrote the date down would be a flow
// that fails tomorrow, so the id is computed from the same clock the app reads.
function plansDay(date) {
    return "plans_section_day_" + date.getFullYear() + "_" + pad2(date.getMonth() + 1) + "_" + pad2(date.getDate());
}

// The testTag of the timeline section of the week a date belongs to.
//
// The week starts on Monday, the same as the app's relative week buckets, and
// JavaScript's Sunday is 0 — so Sunday is the last day of the week, not the
// first.
function plansWeek(date) {
    var weekday = date.getDay();
    var back = (weekday === 0) ? 6 : weekday - 1;
    var monday = new Date(date.getTime());
    monday.setDate(date.getDate() - back);
    return "plans_section_week_" + monday.getFullYear() + "_" + pad2(monday.getMonth() + 1) + "_" + pad2(monday.getDate());
}

// The testTag of the timeline section of the month a date belongs to.
//
// The month section's id is the calendar month without a leading zero on the
// month number ("month:2026-9"), and the tag is that id as a slug.
function plansMonth(date) {
    return "plans_section_month_" + date.getFullYear() + "_" + (date.getMonth() + 1);
}

var tomorrowDate = new Date(d.getTime() + 24 * 60 * 60 * 1000);
output.PLANS_TODAY_SECTION = plansDay(d);
output.PLANS_TOMORROW_SECTION = plansDay(tomorrowDate);
output.PLANS_WEEK_SECTION = plansWeek(d);
output.PLANS_MONTH_SECTION = plansMonth(d);
