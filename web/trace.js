/*
 * Draws a trace, read from trace.json. Polls rather than reloading, so the
 * scroll position survives a new transaction arriving.
 */

const POLL_MS = 2000;
let seenSignature = null;
let seen = [];

/* The contract being followed. Outside draw() so it survives each poll's redraw. */
let following = null;

function follow(contract) {
  following = contract === following ? null : contract;
  mark();
}

function mark() {
  document.querySelectorAll('.ev.identified').forEach(line => {
    line.classList.toggle('followed', line.dataset.contract === following);
  });
  document.getElementById('grid').classList.toggle('following', following !== null);
  const note = document.getElementById('following');
  note.textContent = following ? 'following ' + shortId(following) : '';
}

document.addEventListener('click', event => {
  const line = event.target.closest('.ev.identified');
  follow(line ? line.dataset.contract : null);
});

async function load() {
  let trace;
  try {
    trace = await fetch('trace.json?t=' + Date.now()).then(r => r.json());
  } catch (e) {
    document.getElementById('summary').textContent =
      'no trace.json here - run: make trace';
    return;
  }
  const signature = trace.steps.length + ':' + (trace.steps.at(-1) || {}).id;
  if (signature === seenSignature) {
    return;
  }
  seenSignature = signature;
  draw(trace);
}

function draw(trace) {
  const grid = document.getElementById('grid');
  grid.textContent = '';
  document.getElementById('summary').textContent =
    trace.steps.length + ' TX / ' + trace.nodes.length + ' NODES';

/* Node tints, distinct from every event colour so a badge never reads as a status. */
const palette = ['#8ab4c8', '#c8b48a', '#9dc88a', '#c88aa4', '#a48ac8'];
const colours = {};
trace.nodes.forEach((n, i) => colours[n] = palette[i % palette.length]);

function badge(name) {
  const span = document.createElement('span');
  span.className = 'badge';
  span.style.color = colours[name] || '#3fd8ff';
  span.textContent = initials(name);
  span.title = name;
  return span;
}

/* Five characters past the prefix are enough to tell one run's contracts apart. */
function shortId(contract) {
  return '#' + contract.replace(/^00/, '').slice(0, 5);
}

/* Anything that came from a person or from a ledger error is text, not markup. */
function safe(text) {
  return String(text).replace(/[&<>]/g, ch => ({'&': '&amp;', '<': '&lt;', '>': '&gt;'}[ch]));
}

function initials(name) {
  if (name === 'ICSD') return 'IC';
  if (name === 'CentralBank') return 'CB';
  const letter = name.replace('Bank', '');
  return letter.length ? letter.slice(0, 2) : name.slice(0, 2);
}

const previous = seen;
const firstRun = previous.length === 0;

/* Creation payloads by contract id, so a later choice can say what it consumed. */
const payloads = {};
trace.steps.forEach(step =>
  Object.values(step.seen).forEach(events =>
    events.forEach(ev => {
      if (ev.kind === 'created' && ev.contract && ev.detail) {
        payloads[ev.contract] = ev.detail;
      }
    })));

const head = document.createElement('div');
head.className = 'head';
head.appendChild(cell('when', 'when'));
trace.nodes.forEach(node => {
  const box = cell('', '');
  box.appendChild(badge(node));
  box.appendChild(document.createTextNode(node));
  head.appendChild(box);
});
grid.appendChild(head);
grid.className = 'grid';
grid.style.gridTemplateColumns = '110px repeat(' + trace.nodes.length + ', 1fr)';

function cell(kind, text) {
  const div = document.createElement('div');
  div.className = 'cell ' + kind;
  div.textContent = text;
  return div;
}

trace.steps.forEach(step => {
  const actors = step.actor ? step.actor.split(' + ') : [];

  const row = document.createElement('div');
  const refused = step.id === 'refusal';
  row.className =
    'row'
    + (refused ? ' norecord' : '')
    + (!firstRun && !refused && !previous.includes(step.id) ? ' fresh' : '');
  // Seconds and milliseconds on their own line: a whole run fits in a minute.
  const when = cell('when', '');
  const lastColon = step.at.lastIndexOf(':');
  when.innerHTML =
    lastColon < 0
      ? safe(step.at)
      : safe(step.at.slice(0, lastColon))
        + '<span class="ms">' + safe(step.at.slice(lastColon + 1)) + '</span>';
  row.appendChild(when);

  trace.nodes.forEach(node => {
    const seen = step.seen[node] || [];
    const acted = actors.includes(node);
    const box = cell(seen.length === 0 ? '' : acted ? 'acted' : 'witnessed', '');

    // Balance changes last, as the outcome of the events above.
    const deltas = seen.filter(ev => ev.kind === 'delta');
    const events = seen.filter(ev => ev.kind !== 'delta');

    if (acted && refused) {
      const who = document.createElement('div');
      who.className = 'who';
      who.appendChild(badge(node));
      const label = document.createElement('span');
      label.className = 'norecord-note';
      label.textContent = 'tried, refused - no ledger record';
      who.appendChild(label);
      box.appendChild(who);
    }

    if (seen.length === 0) {
      box.appendChild(Object.assign(document.createElement('span'),
        {className: 'none', textContent: '-'}));
    } else {
      events.forEach(ev => {
        const line = document.createElement('span');
        const family =
          /^(CashBalance|AllocatedCash)$/.test(ev.template) ? ' money'
          : /^(SecurityPosition|AllocatedSecurity|Instrument)$/.test(ev.template) ? ' paper'
          : '';
        line.className = 'ev ' + ev.kind + (ev.depth ? ' under' : ' root') + family
          + (ev.contract ? ' identified' : '');
        line.style.marginLeft = (ev.depth * 12) + 'px';
        if (ev.contract) {
          line.dataset.contract = ev.contract;
        }
        const choice = ev.kind === 'exercised' || ev.kind === 'consumed';
        // [authority] and (audience), once, where the transaction was made: a
        // receiver's copy would only repeat them.
        const attribution = !acted ? ''
          : (ev.actor ? ' <span class="who">[' + ev.actor + ']</span>' : '')
            + (ev.watchedBy ? ' <span class="watching">(' + ev.watchedBy + ')</span>' : '');
        // Repeat what the called contract was; its creation may be far up the page.
        const was = choice ? payloads[ev.contract] : null;
        const body = choice
          ? '<span class="call">.' + ev.detail + '</span>'
            + (was ? ' <span class="was">(' + was + ')</span>' : '')
          : ev.kind === 'refused'
            // Kept visibly apart, or template and reason read as one sentence.
            ? '<span class="why"> refused: </span><span class="detail">'
              + safe(ev.detail) + '</span>'
            : (ev.detail ? ' <span class="detail">' + safe(ev.detail) + '</span>' : '');
        line.innerHTML = '<span class="tpl">' + safe(ev.template) + '</span>'
          + body
          + (ev.contract ? ' <span class="cid">' + shortId(ev.contract) + '</span>' : '')
          + attribution;
        box.appendChild(line);
      });
    }

    if (deltas.length) {
      const band = document.createElement('div');
      band.className = 'deltas';
      deltas.forEach(ev => {
        const up = ev.detail.startsWith('+');
        const line = document.createElement('span');
        line.className = 'ev delta ' + (up ? 'up' : 'down');
        line.innerHTML = '<span class="tpl">' + ev.template + '</span> '
          + '<span class="amt ' + (up ? 'up' : 'down') + '">'
          + (up ? '\u25B2 ' : '\u25BC ') + ev.detail + '</span>';
        band.appendChild(line);
      });
      box.appendChild(band);
    }

    row.appendChild(box);
  });

  grid.appendChild(row);
});

/* Net per institution, excluding what registers issued: what trading did. */
const net = trace.net || {};
const totals = document.createElement('div');
totals.className = 'row totals';
totals.appendChild(cell('when', 'net'));
trace.nodes.forEach(node => {
  const box = cell('', '');
  const lines = net[node] || [];
  if (lines.length === 0) {
    box.appendChild(Object.assign(document.createElement('span'),
      {className: 'none', textContent: 'flat'}));
  } else {
    lines.forEach(line => {
      const span = document.createElement('span');
      span.className = 'ev delta ' + (line.startsWith('+') ? 'up' : 'down');
      span.innerHTML = '<span class="amt ' + (line.startsWith('+') ? 'up' : 'down') + '">'
        + (line.startsWith('+') ? '\u25B2 ' : '\u25BC ') + line + '</span>';
      box.appendChild(span);
    });
  }
  totals.appendChild(box);
});
grid.appendChild(totals);

// So the next poll can mark what is new.
seen = trace.steps.map(s => s.id);
mark();

}

load();
setInterval(load, POLL_MS);
