export const weekdays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

export function clockMinutes(value) {
  const match = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(value);
  return match ? Number(match[1]) * 60 + Number(match[2]) : null;
}

export function inputTime(minutes) {
  return minutes == null ? "" : `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

function isCalendarDate(value) {
  const date = new Date(value + "T00:00:00Z");
  return Number.isFinite(+date) && date.toISOString().slice(0, 10) === value;
}

export function validateSchedule(schedule) {
  if (schedule == null) return null;
  const { days, open, close, cutoff, closed } = schedule;
  if (!Array.isArray(days) || !days.length || new Set(days).size !== days.length ||
      days.some(day => !Number.isInteger(day) || day < 1 || day > 7) ||
      !Number.isInteger(open) || !Number.isInteger(close) || open < 0 || close > 1439 || close <= open ||
      (cutoff != null && (!Number.isInteger(cutoff) || cutoff < open || cutoff > close)) ||
      typeof closed !== "string" || (closed && (closed.split(",").length > 14 || !closed.split(",").every(isCalendarDate)))) {
    throw new Error("Choose opening days and valid hours. Enter closure dates as YYYY-MM-DD, separated by commas.");
  }
  return schedule;
}

export function nextPickupWindow(schedule, now = Date.now()) {
  if (!schedule) return null;
  // The Philippines observes UTC+8 year-round.
  const here = new Date(now + 8 * 3600000);
  const date = new Date(Date.UTC(here.getUTCFullYear(), here.getUTCMonth(), here.getUTCDate()));
  const minute = here.getUTCHours() * 60 + here.getUTCMinutes();
  const closed = new Set(schedule.closed.split(","));
  const limit = schedule.cutoff ?? schedule.close;
  const time = value => new Intl.DateTimeFormat("en-PH", { timeZone:"UTC", hour:"numeric", minute:"2-digit" }).format(value * 60000);
  for (let offset = 0; offset <= 35; offset++) {
    const day = (date.getUTCDay() + 6) % 7 + 1;
    if (schedule.days.includes(day) && !closed.has(date.toISOString().slice(0, 10)) && (offset > 0 || minute < limit)) {
      const label = offset === 0 ? "Today" : new Intl.DateTimeFormat("en-PH", { timeZone:"UTC", weekday:"short", month:"short", day:"numeric" }).format(date);
      return { window: `${label}, ${time(schedule.open)}–${time(schedule.close)}`,
        cutoff: schedule.cutoff == null ? null : time(schedule.cutoff) };
    }
    date.setUTCDate(date.getUTCDate() + 1);
  }
  return null;
}
