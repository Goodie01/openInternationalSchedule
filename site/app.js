'use strict';

const SOURCE_ZONE = 'America/Los_Angeles';
const STORAGE_TZ = 'oiss.tz';
const STORAGE_CLOCK = 'oiss.clock';
const FALLBACK_ZONES = [
    'America/Los_Angeles', 'America/Denver', 'America/Chicago', 'America/New_York', 'America/Sao_Paulo',
    'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'Europe/Moscow', 'Asia/Dubai', 'Asia/Kolkata',
    'Asia/Singapore', 'Asia/Shanghai', 'Asia/Tokyo', 'Australia/Sydney', 'Pacific/Auckland', 'UTC',
];

const els = {
    tz: document.getElementById('tz'),
    tzOptions: document.getElementById('tz-options'),
    tzLocal: document.getElementById('tz-local'),
    tzStatus: document.getElementById('tz-status'),
    clock: document.getElementById('clock'),
    schedule: document.getElementById('schedule'),
    pdfLink: document.getElementById('pdf-link'),
    freshness: document.getElementById('freshness'),
};

const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
let schedule = null;

function isValidZone(zone) {
    if (!zone) return false;
    try {
        new Intl.DateTimeFormat('en-US', {timeZone: zone});
        return true;
    } catch {
        return false;
    }
}

function initialZone() {
    const fromUrl = new URLSearchParams(location.search).get('tz');
    if (isValidZone(fromUrl)) return fromUrl;
    const stored = localStorage.getItem(STORAGE_TZ);
    if (isValidZone(stored)) return stored;
    return browserZone;
}

function hourCycleOption() {
    const clock = els.clock.value;
    if (clock === '12') return {hour12: true};
    if (clock === '24') return {hourCycle: 'h23'};
    return {};
}

function formatters(zone) {
    const hc = hourCycleOption();
    return {
        time: new Intl.DateTimeFormat(undefined, {timeZone: zone, hour: 'numeric', minute: '2-digit', ...hc}),
        sourceTime: new Intl.DateTimeFormat(undefined, {
            timeZone: SOURCE_ZONE, weekday: 'short', hour: 'numeric', minute: '2-digit', ...hc,
        }),
        dayHeading: new Intl.DateTimeFormat(undefined, {timeZone: zone, weekday: 'long', month: 'long', day: 'numeric', year: 'numeric'}),
        dayKey: new Intl.DateTimeFormat('en-CA', {timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit'}),
        stamp: new Intl.DateTimeFormat(undefined, {dateStyle: 'medium', timeStyle: 'short'}),
    };
}

/** e.g. "GMT+1" / "GMT-8" for the zone at the given instant. */
function offsetName(zone, date) {
    const part = new Intl.DateTimeFormat('en-US', {timeZone: zone, timeZoneName: 'shortOffset'})
        .formatToParts(date)
        .find(p => p.type === 'timeZoneName');
    return part ? part.value.replace(/^GMT$/, 'GMT+0') : '';
}

function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
}

function render() {
    if (!schedule) return;
    const zone = els.tz.value;
    const f = formatters(zone);
    const now = Date.now();
    const sameAsSource = offsetName(zone, eventStart()) === offsetName(SOURCE_ZONE, eventStart());

    els.tzStatus.textContent = `Times shown in ${zone} (${offsetName(zone, eventStart())})`
        + (zone === browserZone ? ', which is your device\'s time zone.' : '.')
        + (sameAsSource ? ' This matches the official Los Angeles times.' : '');

    // Group by the viewer's local date: a 9 PM Los Angeles start is already the next morning in Europe and Asia.
    const groups = new Map();
    for (const day of schedule.days) {
        for (const event of day.events) {
            const start = new Date(event.start);
            const key = f.dayKey.format(start);
            if (!groups.has(key)) groups.set(key, {date: start, events: []});
            groups.get(key).events.push(event);
        }
    }

    const fragment = document.createDocumentFragment();

    if (schedule.notes.length) {
        const notes = el('aside', 'notes');
        for (const note of schedule.notes) notes.append(el('p', null, note));
        fragment.append(notes);
    }

    for (const [key, group] of [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))) {
        const section = el('section', 'day');
        section.append(el('h2', null, f.dayHeading.format(group.date)));
        const list = el('ol', 'events');
        group.events.sort((a, b) => new Date(a.start) - new Date(b.start));

        for (const event of group.events) {
            const start = new Date(event.start);
            const end = event.end ? new Date(event.end) : null;
            const item = el('li', 'event');
            if ((end ?? start) < now) item.classList.add('past');
            else if (end && start <= now) item.classList.add('now');

            const when = el('div', 'when');
            let label = f.time.format(start);
            if (end) {
                label += ' – ' + f.time.format(end);
                const days = dayDiff(f.dayKey.format(end), key);
                if (days > 0) label += ` (+${days})`;
            }
            when.append(el('time', 'local', label));
            when.lastChild.dateTime = event.start;
            if (!sameAsSource) {
                when.append(el('span', 'source', `LA: ${f.sourceTime.format(start)}`));
                when.lastChild.title = `As printed: ${event.sourceTime}`;
            }

            const what = el('div', 'what');
            what.append(el('span', 'title', event.title));
            if (event.detail) what.append(el('span', 'detail', event.detail));
            if (item.classList.contains('now')) what.prepend(el('span', 'badge', 'Now'));

            item.append(when, what);
            list.append(item);
        }
        section.append(list);
        fragment.append(section);
    }

    els.schedule.replaceChildren(fragment);
}

function dayDiff(laterKey, earlierKey) {
    return Math.round((Date.parse(laterKey) - Date.parse(earlierKey)) / 86_400_000);
}

/** A representative instant for offset labels: the first event, so DST is judged for the event itself. */
function eventStart() {
    const first = schedule.days[0]?.events[0];
    return first ? new Date(first.start) : new Date();
}

function applyZone(zone, {push = true} = {}) {
    if (!isValidZone(zone)) {
        els.tzStatus.textContent = `"${zone}" isn't a time zone this browser recognises. Pick one from the list.`;
        return;
    }
    els.tz.value = zone;
    localStorage.setItem(STORAGE_TZ, zone);
    if (push) {
        const url = new URL(location.href);
        if (zone === browserZone) url.searchParams.delete('tz');
        else url.searchParams.set('tz', zone);
        history.replaceState(null, '', url);
    }
    render();
}

function renderFooter() {
    const f = formatters(els.tz.value);
    if (schedule.pdfUrl) els.pdfLink.href = schedule.pdfUrl;
    const parts = [`Last checked ${f.stamp.format(new Date(schedule.generatedAt))}.`];
    if (schedule.sourceUpdated) parts.push(`The official schedule was last updated ${schedule.sourceUpdated}.`);
    els.freshness.textContent = parts.join(' ');
}

function populateZones() {
    const zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : FALLBACK_ZONES;
    const fragment = document.createDocumentFragment();
    for (const zone of new Set([browserZone, SOURCE_ZONE, ...zones])) {
        const option = document.createElement('option');
        option.value = zone;
        fragment.append(option);
    }
    els.tzOptions.replaceChildren(fragment);
}

async function main() {
    populateZones();
    els.clock.value = localStorage.getItem(STORAGE_CLOCK) || 'auto';
    els.tz.value = initialZone();

    els.tz.addEventListener('change', () => applyZone(els.tz.value.trim()));
    els.tz.addEventListener('focus', () => els.tz.select());
    els.tzLocal.addEventListener('click', () => applyZone(browserZone));
    els.clock.addEventListener('change', () => {
        localStorage.setItem(STORAGE_CLOCK, els.clock.value);
        render();
    });
    document.getElementById('controls').addEventListener('submit', e => {
        e.preventDefault();
        applyZone(els.tz.value.trim());
    });

    try {
        const response = await fetch('schedule.json', {cache: 'no-cache'});
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        schedule = await response.json();
    } catch (err) {
        els.schedule.replaceChildren(el('p', 'error',
            `Couldn't load the schedule (${err.message}). Please use the official schedule linked below.`));
        return;
    }
    applyZone(els.tz.value, {push: false});
    renderFooter();
    setInterval(render, 60_000);
}

main();
