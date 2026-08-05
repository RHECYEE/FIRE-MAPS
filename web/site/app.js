/*
 * Fireline Map, in a browser.
 *
 * Everything that decides where something is comes from the phone app's own
 * Kotlin, compiled to JavaScript and loaded as fireline.js: grid conversions,
 * coordinate parsing, the track detector, the share codec. This file draws and
 * stores. It deliberately owns no geodesy, because two implementations of a
 * grid conversion eventually disagree, and the disagreement is about where
 * somebody is standing.
 *
 * The one exception is tile placement -- working out which 256 pixel square
 * goes where on screen -- which is done here because it runs every frame. It
 * only decides what is drawn, never what a position is: every coordinate shown
 * or stored comes back through Kotlin.
 */

'use strict';

// The Kotlin bundle exports itself under a module object; find it either way.
const K = (() => {
    const module = (typeof web !== 'undefined' && web) || window.web || {};
    const found = module.com && module.com.rhecyee &&
        module.com.rhecyee.firelinemap.web;
    return found ? found.Fireline : null;
})();

// ---------------------------------------------------------------- storage

/**
 * Everything is kept on the device.
 *
 * No account, no server, nothing to sign into -- the same promise the phone
 * app makes, and the reason either is usable at the end of a road. Encoded
 * tracks are small enough that ordinary browser storage is ample; a shift
 * comes to a couple of kilobytes.
 */
const Store = {
    read(key, fallback) {
        try {
            const raw = localStorage.getItem('fireline.' + key);
            return raw ? JSON.parse(raw) : fallback;
        } catch (e) {
            return fallback;
        }
    },
    write(key, value) {
        try {
            localStorage.setItem('fireline.' + key, JSON.stringify(value));
            return true;
        } catch (e) {
            // Storage full or blocked. Said out loud rather than losing a
            // track quietly.
            banner('Could not save — browser storage is full or blocked.', 'bad');
            return false;
        }
    }
};

let incident = Store.read('incident', 'Incident');
let pins = Store.read('pins', []);
let tracks = Store.read('tracks', []);

const savePins = () => Store.write('pins', pins);
const saveTracks = () => Store.write('tracks', tracks);

// ------------------------------------------------------------------- map

const TILE = 256;
const TILE_URL =
    'https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile';

const canvas = document.getElementById('map');
const ctx = canvas.getContext('2d');

const view = {
    latitude: 44.9928,
    longitude: -101.2434,
    zoom: 13,
    following: true
};

let position = null;      // the live fix
let heldTiles = 0;

/** Web Mercator, in pixels at a given zoom. Drawing only. */
function project(latitude, longitude, zoom) {
    const scale = TILE * Math.pow(2, zoom);
    const x = (longitude + 180) / 360 * scale;
    const clamped = Math.max(-85.05112878, Math.min(85.05112878, latitude));
    const sin = Math.sin(clamped * Math.PI / 180);
    const y = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * scale;
    return { x, y };
}

function unproject(x, y, zoom) {
    const scale = TILE * Math.pow(2, zoom);
    const longitude = x / scale * 360 - 180;
    const n = Math.PI - 2 * Math.PI * y / scale;
    const latitude = 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
    return { latitude, longitude };
}

/** Screen pixel for a position, given the current view. */
function toScreen(latitude, longitude) {
    const centre = project(view.latitude, view.longitude, view.zoom);
    const point = project(latitude, longitude, view.zoom);
    return {
        x: point.x - centre.x + canvas.width / (2 * dpr()),
        y: point.y - centre.y + canvas.height / (2 * dpr())
    };
}

function toGeo(screenX, screenY) {
    const centre = project(view.latitude, view.longitude, view.zoom);
    return unproject(
        centre.x + screenX - canvas.width / (2 * dpr()),
        centre.y + screenY - canvas.height / (2 * dpr()),
        view.zoom
    );
}

function dpr() { return window.devicePixelRatio || 1; }

const tileCache = new Map();
const tileFailed = new Set();

/**
 * Fetches a tile, holding it once decoded.
 *
 * The service worker is what makes these survive going offline: it keeps every
 * tile that has been fetched, so ground already looked at is still there with
 * no signal. Nothing is prefetched beyond what has been on screen -- the USGS
 * serves this map for use, not for bulk copying.
 */
function tile(z, x, y) {
    const key = z + '/' + x + '/' + y;
    if (tileCache.has(key)) return tileCache.get(key);
    if (tileFailed.has(key)) return null;

    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = () => { heldTiles = tileCache.size; draw(); };
    image.onerror = () => { tileCache.delete(key); tileFailed.add(key); };
    image.src = `${TILE_URL}/${z}/${y}/${x}`;
    tileCache.set(key, image);
    return image;
}

function resize() {
    const ratio = dpr();
    canvas.width = window.innerWidth * ratio;
    canvas.height = window.innerHeight * ratio;
    canvas.style.width = window.innerWidth + 'px';
    canvas.style.height = window.innerHeight + 'px';
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    draw();
}

function draw() {
    const width = canvas.width / dpr();
    const height = canvas.height / dpr();
    ctx.clearRect(0, 0, width, height);
    ctx.fillStyle = '#2b3a44';
    ctx.fillRect(0, 0, width, height);

    const level = Math.max(0, Math.min(16, Math.round(view.zoom)));
    const scale = Math.pow(2, view.zoom - level);
    const size = TILE * scale;
    const centre = project(view.latitude, view.longitude, level);
    const originX = centre.x * scale - width / 2;
    const originY = centre.y * scale - height / 2;

    const count = Math.pow(2, level);
    const firstX = Math.floor(originX / size);
    const firstY = Math.floor(originY / size);
    const lastX = Math.floor((originX + width) / size);
    const lastY = Math.floor((originY + height) / size);

    for (let x = firstX; x <= lastX; x++) {
        for (let y = firstY; y <= lastY; y++) {
            if (y < 0 || y >= count) continue;
            const wrapped = ((x % count) + count) % count;
            const image = tile(level, wrapped, y);
            if (!image || !image.complete || !image.naturalWidth) continue;
            ctx.drawImage(
                image,
                Math.round(x * size - originX),
                Math.round(y * size - originY),
                Math.ceil(size),
                Math.ceil(size)
            );
        }
    }

    drawTracks();
    drawPins();
    drawMe();
}

/** Past two minutes of silence the receiver was not reporting, not stopped. */
const GAP_MILLIS = 120000;

function isGap(from, to) {
    if (!from.timeMillis || !to.timeMillis) return false;
    return to.timeMillis - from.timeMillis > GAP_MILLIS;
}

const TRACK_COLOURS = [
    '#AB47BC', '#26A69A', '#FFA726', '#42A5F5',
    '#EC407A', '#9CCC65', '#7E57C2', '#FF7043'
];

/**
 * How much the map is claiming to know, drawn so it can be read at a glance.
 *
 * Solid   -- recorded. A receiver followed this ground and reported it.
 * Dotted  -- inferred. Only the ends are real; the phone was asleep between
 *            them and nothing observed the middle.
 * Dashed  -- imported or hand edited. Somebody else's word for it.
 *
 * The distinction is invisible unless it is drawn, and it decides how much
 * weight a division supervisor should put on a route without opening a menu
 * or reading metadata. It is also what makes deduplication deterministic:
 * recorded geometry always supersedes inferred geometry, so when a real track
 * turns up for a stretch that was only inferred, there is a rule rather than
 * a judgement.
 */
const LINE = {
    recorded: { dash: [], width: 4, alpha: 1 },
    inferred: { dash: [2, 7], width: 4, alpha: 0.85 },
    imported: { dash: [11, 7], width: 3, alpha: 0.9 }
};

/** What a whole track is, before its individual legs are looked at. */
function trackQuality(track) {
    if (track && track.source === 'imported') return 'imported';
    return 'recorded';
}

function drawTracks() {
    tracks.forEach((track, index) => {
        if (!track.points || track.points.length < 2) return;
        const colour = TRACK_COLOURS[index % TRACK_COLOURS.length];
        const base = trackQuality(track);
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';

        for (let i = 0; i < track.points.length - 1; i++) {
            const from = track.points[i];
            const to = track.points[i + 1];
            // A gap inside an otherwise recorded track is inferred, whatever
            // the rest of the track is. That is the hybrid case, and it is the
            // common one on a phone that suspends.
            const style = LINE[isGap(from, to) ? 'inferred' : base];
            ctx.strokeStyle = colour;
            ctx.globalAlpha = style.alpha;
            ctx.lineWidth = style.width;
            ctx.setLineDash(style.dash);
            ctx.beginPath();
            const a = toScreen(from.latitude, from.longitude);
            const b = toScreen(to.latitude, to.longitude);
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(b.x, b.y);
            ctx.stroke();
        }
        ctx.setLineDash([]);
        ctx.globalAlpha = 1;
    });

    if (live.points.length > 1) {
        ctx.strokeStyle = '#EF5350';
        ctx.lineWidth = 5;
        ctx.beginPath();
        live.points.forEach((point, i) => {
            const at = toScreen(point[0], point[1]);
            if (i === 0) ctx.moveTo(at.x, at.y); else ctx.lineTo(at.x, at.y);
        });
        ctx.stroke();
    }
}

const SYMBOLS = [
    ['drop_point', 'Drop point', 'DP'], ['helispot', 'Helispot', 'H'],
    ['icp', 'ICP', 'ICP'], ['staging', 'Staging', 'STG'],
    ['engine', 'Engine', 'ENG'], ['dozer', 'Dozer', 'DOZ'],
    ['hand_crew', 'Hand crew', 'CRW'], ['medic', 'Medic', 'MED'],
    ['medical_incident', 'Medical', '911'], ['hazard', 'Hazard', '!'],
    ['water', 'Water', 'H2O'], ['camp', 'Camp', 'CMP'],
    ['snag', 'Snag', 'SNG'], ['other', 'Point', '•']
];

function glyphOf(id) {
    const found = SYMBOLS.find(s => s[0] === id);
    return found ? found[2] : '•';
}

function drawPins() {
    ctx.font = '700 12px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    pins.forEach(pin => {
        const at = toScreen(pin.latitude, pin.longitude);
        const glyph = glyphOf(pin.symbolId);
        const width = Math.max(28, ctx.measureText(glyph).width + 16);
        ctx.fillStyle = 'rgba(20,35,46,0.92)';
        ctx.fillRect(at.x - width / 2, at.y - 12, width, 24);
        ctx.fillStyle = '#FFC400';
        ctx.fillText(glyph, at.x, at.y);
        ctx.fillStyle = 'rgba(255,255,255,0.85)';
        ctx.font = '600 11px system-ui, sans-serif';
        ctx.fillText(pin.title, at.x, at.y + 22);
        ctx.font = '700 12px system-ui, sans-serif';
    });
}

function drawMe() {
    if (!position) return;
    const at = toScreen(position.latitude, position.longitude);
    if (position.accuracy) {
        const metres = position.accuracy;
        const perPixel = 156543.03392 *
            Math.cos(position.latitude * Math.PI / 180) / Math.pow(2, view.zoom);
        const radius = metres / perPixel;
        if (radius > 4 && radius < 400) {
            ctx.fillStyle = 'rgba(33,150,243,0.18)';
            ctx.beginPath();
            ctx.arc(at.x, at.y, radius, 0, Math.PI * 2);
            ctx.fill();
        }
    }
    ctx.fillStyle = '#2196F3';
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 3;
    ctx.beginPath();
    ctx.arc(at.x, at.y, 9, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
}

// -------------------------------------------------------------- gestures

let pointers = new Map();
let lastPan = null;
let pinchFrom = null;
let movedSincePress = 0;

canvas.addEventListener('pointerdown', event => {
    canvas.setPointerCapture(event.pointerId);
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    lastPan = { x: event.clientX, y: event.clientY };
    movedSincePress = 0;
    if (pointers.size === 2) pinchFrom = spread();
});

canvas.addEventListener('pointermove', event => {
    if (!pointers.has(event.pointerId)) return;
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

    if (pointers.size === 2 && pinchFrom) {
        const now = spread();
        if (now > 0 && pinchFrom > 0) {
            const next = view.zoom + Math.log2(now / pinchFrom);
            view.zoom = Math.max(3, Math.min(16, next));
            pinchFrom = now;
            movedSincePress += 20;
            draw();
        }
        return;
    }

    if (lastPan) {
        const dx = event.clientX - lastPan.x;
        const dy = event.clientY - lastPan.y;
        movedSincePress += Math.abs(dx) + Math.abs(dy);
        lastPan = { x: event.clientX, y: event.clientY };
        panBy(-dx, -dy);
    }
});

function endPointer(event) {
    const had = pointers.size;
    pointers.delete(event.pointerId);
    if (pointers.size < 2) pinchFrom = null;
    if (pointers.size === 0) {
        lastPan = null;
        // A tap, not a drag. The threshold is generous because a gloved
        // finger never lands perfectly still.
        if (had === 1 && movedSincePress < 12) onTap(event.clientX, event.clientY);
    }
}

canvas.addEventListener('pointerup', endPointer);
canvas.addEventListener('pointercancel', endPointer);

function spread() {
    const all = [...pointers.values()];
    if (all.length < 2) return 0;
    return Math.hypot(all[0].x - all[1].x, all[0].y - all[1].y);
}

function panBy(dx, dy) {
    view.following = false;
    document.getElementById('follow').classList.remove('on');
    const centre = project(view.latitude, view.longitude, view.zoom);
    const moved = unproject(centre.x + dx, centre.y + dy, view.zoom);
    view.latitude = moved.latitude;
    view.longitude = moved.longitude;
    draw();
}

// ------------------------------------------------------------------- GPS

function startLocating() {
    if (!navigator.geolocation) {
        banner('This browser will not give a position.', 'bad');
        return;
    }
    navigator.geolocation.watchPosition(fix => {
        position = {
            latitude: fix.coords.latitude,
            longitude: fix.coords.longitude,
            accuracy: fix.coords.accuracy,
            speed: fix.coords.speed,
            at: fix.timestamp
        };
        if (view.following) {
            view.latitude = position.latitude;
            view.longitude = position.longitude;
        }
        onFix();
        showCoordinates();
        draw();
    }, error => {
        banner('No position: ' + error.message, 'bad');
    }, { enableHighAccuracy: true, maximumAge: 2000, timeout: 30000 });
}

const FORMATS = ['DDM', 'DD', 'UTM', 'MGRS'];
let formatIndex = 0;

function showCoordinates() {
    const label = document.getElementById('coords');
    if (!position) { label.textContent = 'Waiting for GPS…'; return; }
    label.textContent = formatted(position.latitude, position.longitude);
    document.getElementById('accuracy').textContent =
        position.accuracy ? '±' + Math.round(position.accuracy) + ' m' : '—';
    document.getElementById('held').textContent = 'tiles ' + heldTiles;
}

/** Every format comes from the phone's own conversions. */
function formatted(latitude, longitude) {
    if (!K) return latitude.toFixed(5) + ', ' + longitude.toFixed(5);
    switch (FORMATS[formatIndex]) {
        case 'DDM': return K.formatDdm(latitude, longitude);
        case 'DD': return latitude.toFixed(5) + '  ' + longitude.toFixed(5);
        case 'UTM': return K.formatUtm(latitude, longitude) || '—';
        case 'MGRS': return K.formatMgrs(latitude, longitude, 5) || '—';
    }
}

document.getElementById('format').onclick = () => {
    formatIndex = (formatIndex + 1) % FORMATS.length;
    document.getElementById('format').textContent = FORMATS[formatIndex];
    showCoordinates();
};

document.getElementById('follow').onclick = () => {
    view.following = !view.following;
    document.getElementById('follow').classList.toggle('on', view.following);
    if (view.following && position) {
        view.latitude = position.latitude;
        view.longitude = position.longitude;
    }
    draw();
};

document.getElementById('zoomIn').onclick = () => {
    view.zoom = Math.min(16, view.zoom + 1); draw();
};
document.getElementById('zoomOut').onclick = () => {
    view.zoom = Math.max(3, view.zoom - 1); draw();
};

// ------------------------------------------------------------- recording

/**
 * Recording, held awake.
 *
 * This is the one place the browser is genuinely worse than the phone, and it
 * is said plainly rather than discovered. iOS suspends a web app the moment it
 * is backgrounded or the screen locks, and no permission changes that -- so
 * the screen is held on with a wake lock while recording, and if the page is
 * suspended anyway the gap is marked rather than drawn across.
 */
const live = { recorder: null, points: [], startedAt: 0, wakeLock: null, hiddenAt: 0 };

/**
 * A recording in progress, written down as it happens.
 *
 * This is not a nicety. iOS kills a web app whenever it is backgrounded, and
 * it does so without warning and without running any code -- so a recording
 * held only in memory is a recording that ends the moment somebody takes a
 * phone call. Every fix is written straight to storage, which costs nothing
 * (a shift is a couple of kilobytes) and means a kill loses at most one
 * update rather than the whole shift.
 *
 * Recovered on the next start rather than resumed silently: the gap between
 * being killed and being reopened is unobserved travel, and the operator has
 * to be told that rather than shown a line across it.
 */
function saveLive() {
    if (!live.recorder) { Store.write('live', null); return; }
    Store.write('live', {
        startedAt: live.startedAt,
        points: live.points,
        savedAt: Date.now()
    });
}

function recoverLive() {
    const held = Store.read('live', null);
    if (!held || !held.points || held.points.length < 2) {
        Store.write('live', null);
        return;
    }
    // Kept as a track rather than resumed. Whatever happened between the app
    // being killed and being opened again was not recorded, and rolling it
    // into the same track would hide that.
    const track = {
        id: 'rec' + held.startedAt,
        name: 'Travel ' + new Date(held.startedAt).toLocaleString() + ' (recovered)',
        points: held.points.map(p => ({
            latitude: p[0], longitude: p[1], timeMillis: p[2] || 0
        })),
        note: 'Recovered after the app was closed while recording'
    };
    tracks.push(track);
    saveTracks();
    Store.write('live', null);
    banner(
        'Recovered a recording that was interrupted — ' + track.points.length +
        ' positions kept.', 'good'
    );
}

async function holdScreenAwake() {
    try {
        if ('wakeLock' in navigator) {
            live.wakeLock = await navigator.wakeLock.request('screen');
            live.wakeLock.addEventListener('release', () => { live.wakeLock = null; });
        }
    } catch (e) { /* Refused; the warning below already covers it. */ }
}

function releaseScreen() {
    if (live.wakeLock) { live.wakeLock.release().catch(() => {}); live.wakeLock = null; }
}

function startRecording() {
    if (!K) { banner('The map engine did not load.', 'bad'); return; }
    live.recorder = K.recorder(300);
    live.points = [];
    live.startedAt = Date.now();
    saveLive();
    holdScreenAwake();
    document.getElementById('recordTool').classList.add('rec');
    document.getElementById('recordTool').textContent = 'Stop';
    banner('Recording. Keep this page open and the screen on.', 'good');
}

function onFix() {
    if (!live.recorder || !position) return;
    live.recorder.onFix(
        position.latitude, position.longitude, position.at,
        position.accuracy || 10, position.speed == null ? -1 : position.speed
    );
    // The time goes in too. Without it a recovered track is a line with no
    // clock -- no elapsed, no speed, and nothing for the merge readout.
    live.points.push([position.latitude, position.longitude, position.at]);
    saveLive();
}

function stopRecording() {
    const recorder = live.recorder;
    live.recorder = null;
    releaseScreen();
    document.getElementById('recordTool').classList.remove('rec');
    document.getElementById('recordTool').textContent = 'Record';

    const finished = recorder && recorder.finish();
    live.points = [];
    Store.write('live', null);
    if (!finished) {
        banner('Too short to keep.', 'warn');
        draw();
        return;
    }
    const parsed = JSON.parse(finished);
    const track = parsed.tracks[0];
    track.name = 'Travel ' + new Date(live.startedAt).toLocaleString();
    tracks.push(track);
    saveTracks();
    banner('Track saved.', 'good');
    draw();
}

document.getElementById('recordTool').onclick = () => {
    if (live.recorder) stopRecording(); else startRecording();
};

/**
 * A suspended page is a gap, and it is marked as one.
 *
 * Drawing a straight line across ground nobody walked is worse than an obvious
 * hole in the track, because only one of the two is visible as a mistake.
 */
document.addEventListener('visibilitychange', () => {
    if (!live.recorder) return;
    if (document.hidden) {
        live.hiddenAt = Date.now();
    } else {
        const away = Date.now() - live.hiddenAt;
        if (live.hiddenAt && away > GAP_MILLIS) {
            // Drawn as a dashed gap, and said out loud, because a straight
            // line across unobserved ground looks exactly like one that was
            // followed.
            banner(
                'Away ' + Math.round(away / 60000) + ' min — that stretch was not ' +
                'recorded. It will show as a dashed gap with an estimated distance.',
                'warn'
            );
        }
        holdScreenAwake();
    }
});

// ------------------------------------------------------------------ pins

let pinArmed = false;

document.getElementById('pinTool').onclick = () => {
    pinArmed = !pinArmed;
    document.getElementById('pinTool').classList.toggle('on', pinArmed);
    banner(pinArmed ? 'Tap the map to drop a pin.' : '', pinArmed ? 'good' : null);
};

function onTap(clientX, clientY) {
    const where = toGeo(clientX, clientY);
    if (pinArmed) {
        pinArmed = false;
        document.getElementById('pinTool').classList.remove('on');
        placePin(where.latitude, where.longitude);
        return;
    }
    const report = K && K.tracksAt(
        where.latitude, where.longitude, JSON.stringify({ tracks })
    );
    const parsed = report && JSON.parse(report);
    if (parsed && parsed.count > 0) showOverlap(parsed);
}

function placePin(latitude, longitude) {
    openSheet('Drop a pin', `
        <p class="note">${formatted(latitude, longitude)}</p>
        <input id="pinName" placeholder="Name (DP 12, Helispot 3…)" autocomplete="off">
        <h4>Symbol</h4>
        <div id="symbols"></div>
        <button class="wide" id="pinSave">DROP IT</button>
    `);
    let chosen = 'drop_point';
    const holder = document.getElementById('symbols');
    SYMBOLS.forEach(([id, label]) => {
        const button = document.createElement('button');
        button.className = 'chip';
        button.style.margin = '3px';
        button.textContent = label;
        button.onclick = () => {
            chosen = id;
            [...holder.children].forEach(c => c.style.background = '#25404F');
            button.style.background = '#1565C0';
        };
        if (id === chosen) button.style.background = '#1565C0';
        holder.appendChild(button);
    });
    document.getElementById('pinSave').onclick = () => {
        const name = document.getElementById('pinName').value.trim();
        pins.push({
            id: 'w' + Date.now(),
            title: name || 'Point',
            latitude, longitude,
            symbolId: chosen,
            createdAt: Date.now()
        });
        savePins();
        closeSheet();
        draw();
    };
}

// -------------------------------------------------------------- overlap

function showOverlap(report) {
    const figure = (label, value) =>
        value ? `<div class="figure"><span>${label}</span><b>${value}</b></div>` : '';
    const rows = report.passes.map(pass => `
        <div class="item">
          <div class="top">
            <strong>${escapeHtml(pass.name)}</strong>
            <b class="${pass.average ? 'good' : ''}">${pass.average || 'no times'}</b>
          </div>
          <div class="meta">${[
              pass.atMillis ? new Date(pass.atMillis).toLocaleString() : null,
              pass.distance, pass.elapsed
          ].filter(Boolean).join(' · ')}</div>
          <div class="meta ${pass.stopped ? 'warn' : ''}">${pass.offMeters} m off the tap${
              pass.stopped ? ' · stopped here' : (pass.spotSpeed ? ' · ' + pass.spotSpeed + ' here' : '')
          }</div>
        </div>`).join('');

    openSheet(report.count + (report.count === 1 ? ' pass' : ' passes') + ' through here', `
        ${figure('Average speed', report.averageSpeed)}
        ${figure('Average time', report.averageTime)}
        ${figure('Total distance', report.totalDistance)}
        ${figure('Total time', report.totalTime)}
        ${figure('Speed at this spot', report.spotSpeed)}
        ${report.stoppedCount ? `<p class="note">${report.stoppedCount} of ${report.count} were stopped here and are left out of the spot speed.</p>` : ''}
        ${rows}
    `);
}

// --------------------------------------------------------------- sharing

let assembly = { parts: {}, checksum: null, total: 0 };

document.getElementById('shareTool').onclick = () => showShare();

function currentPackage() {
    return {
        incidentName: incident,
        author: Store.read('author', '') || null,
        createdAt: Date.now(),
        pins,
        tracks
    };
}

function showShare() {
    const parts = K ? K.encodeParts(JSON.stringify(currentPackage())) : [];
    const have = Object.keys(assembly.parts).length;
    const missing = assembly.total
        ? [...Array(assembly.total).keys()].map(i => i + 1).filter(i => !assembly.parts[i])
        : [];

    openSheet('Share', `
        <h4>Send this map</h4>
        <p class="note">Every pin and every track, as ${parts.length}
            message${parts.length === 1 ? '' : 's'}. They paste each one into their
            copy and the whole map redraws.</p>
        <button class="wide" id="sendPart">SEND${parts.length > 1 ? ' PART 1 OF ' + parts.length : ''}</button>
        <button class="wide quiet" id="copyPart">COPY${parts.length > 1 ? ' PART 1 OF ' + parts.length : ' IT'}</button>

        <h4>Receive</h4>
        <p class="note">Paste one part at a time. Order does not matter, and extra
            text around it is ignored.</p>
        <textarea id="pasteBox" placeholder="FL1;…"></textarea>
        <button class="wide" id="addPart">ADD THIS PART</button>
        <p class="note" id="assemblyState">${
            have === 0 ? 'Nothing pasted yet'
            : missing.length === 0 ? 'All ' + assembly.total + ' parts — ready'
            : 'Have ' + have + ' of ' + assembly.total + ' — still need ' + missing.join(', ')
        }</p>
        <div id="applyHolder"></div>

        <h4>This incident</h4>
        <input id="incidentName" value="${escapeHtml(incident)}" placeholder="Incident name">
        <button class="wide quiet" id="saveIncident">SAVE NAME</button>
    `);

    let next = 0;
    const label = which =>
        parts.length > 1 ? ' PART ' + (which + 1) + ' OF ' + parts.length : ' IT';

    document.getElementById('sendPart').onclick = async () => {
        const body = incident + ' — Fireline map' +
            (parts.length > 1 ? ' (part ' + (next + 1) + ' of ' + parts.length + ')' : '') +
            '\nPaste into Fireline Map, Share, Receive.\n\n' + parts[next];
        try {
            if (navigator.share) await navigator.share({ text: body });
            else { await navigator.clipboard.writeText(body); banner('Copied.', 'good'); }
        } catch (e) { /* Dismissed. */ }
        if (next < parts.length - 1) {
            next++;
            document.getElementById('sendPart').textContent = 'SEND' + label(next);
            document.getElementById('copyPart').textContent = 'COPY' + label(next);
        }
    };

    document.getElementById('copyPart').onclick = async () => {
        try {
            await navigator.clipboard.writeText(parts[next]);
            banner('Copied — paste it into a message.', 'good');
        } catch (e) { banner('Could not reach the clipboard.', 'bad'); }
    };

    document.getElementById('addPart').onclick = () => {
        const text = document.getElementById('pasteBox').value;
        const part = K && K.readPart(text);
        if (!part) { banner('That is not a Fireline part — it should start FL1;', 'bad'); return; }
        // A part from a different send replaces what is here rather than
        // splicing two messages into a track nobody walked.
        if (assembly.checksum !== part.checksum) {
            assembly = { parts: {}, checksum: part.checksum, total: part.total };
        }
        assembly.parts[part.index] = part.slice;
        assembly.total = part.total;
        document.getElementById('pasteBox').value = '';
        showShare();
    };

    document.getElementById('saveIncident').onclick = () => {
        incident = document.getElementById('incidentName').value.trim() || 'Incident';
        Store.write('incident', incident);
        banner('Saved.', 'good');
    };

    if (assembly.total && missing.length === 0) {
        const body = [...Array(assembly.total).keys()]
            .map(i => assembly.parts[i + 1]).join('');
        const ok = K && K.verify(body, assembly.checksum);
        const holder = document.getElementById('applyHolder');
        if (!ok) {
            holder.innerHTML = `<p class="note bad">Those parts do not add up —
                something was cut short or came from a different message.</p>
                <button class="wide quiet" id="clearParts">CLEAR</button>`;
        } else {
            holder.innerHTML = `<button class="wide" id="applyParts">DRAW IT ON THIS MAP</button>
                <button class="wide quiet" id="clearParts">CLEAR</button>`;
            document.getElementById('applyParts').onclick = () => applyParts(body);
        }
        document.getElementById('clearParts').onclick = () => {
            assembly = { parts: {}, checksum: null, total: 0 };
            showShare();
        };
    }
}

/**
 * Adds what arrived, minus exact copies of records already held.
 *
 * The filtering is done by the shared codec, not here. A record that encodes
 * to exactly the bytes of one already held is the same thing arriving twice;
 * two pins near each other are not, and are never merged -- that is a
 * judgement for a person on a radio. Running the phone's own rule means the
 * two cannot drift apart about which pin to throw away.
 */
function applyParts(body) {
    const decoded = K && K.decodeParts(body);
    if (!decoded) { banner('Those parts could not be read.', 'bad'); return; }

    const filtered = JSON.parse(
        K.withoutDuplicates(decoded, JSON.stringify(currentPackage()))
    );
    const fresh = filtered.kept;
    const incoming = JSON.parse(decoded);

    (fresh.pins || []).forEach((pin, index) => {
        pins.push(Object.assign({}, pin, { id: 'r' + Date.now() + '-' + index }));
    });
    (fresh.tracks || []).forEach((track, index) => {
        tracks.push(Object.assign({}, track, {
            id: 'r' + Date.now() + '-t' + index,
            // Somebody else's word for it, drawn dashed. A recorded track of
            // your own always outranks it.
            source: 'imported',
            note: 'Received from ' + (incoming.author || incoming.incidentName)
        }));
    });

    savePins();
    saveTracks();
    assembly = { parts: {}, checksum: null, total: 0 };
    closeSheet();
    draw();
    banner(
        filtered.describe + ' from ' + (incoming.author || incoming.incidentName),
        (fresh.pins || []).length + (fresh.tracks || []).length ? 'good' : 'warn'
    );
}

// ------------------------------------------------------------------ list

/** What the line styles mean. Shown from the list, where tracks are read. */
function qualityLegend() {
    return `
      <h4>Track quality</h4>
      <p class="note">
        <b>Solid</b> — recorded. A receiver followed this ground.<br>
        <b>Dotted</b> — inferred. Only the ends are real; nothing observed the
        middle, and its distance is an estimate.<br>
        <b>Dashed</b> — received from somebody else.
      </p>`;
}

document.getElementById('listTool').onclick = () => {
    const trackRows = tracks.map((track, index) => {
        // What was recorded and what was only inferred, worded by the shared
        // code so the browser cannot quietly drop "estimated".
        const state = K ? JSON.parse(K.provenanceOf(
            JSON.stringify({ tracks: [track] })
        )) : null;
        const lines = state ? state.summary.map(escapeHtml).join('<br>') : '';
        const warn = state && state.provenance !== 'RECORDED';
        return `
        <div class="item">
          <div class="top">
            <strong>${escapeHtml(track.name || 'Track')}</strong>
            <button class="chip" data-track="${index}">DELETE</button>
          </div>
          <div class="meta${warn ? ' warn' : ''}">${lines}</div>
          ${(state && state.gaps.length) ? state.gaps.map(gap =>
              `<div class="meta warn">${escapeHtml(gap.describe)}</div>`).join('') : ''}
          <div class="meta">${(track.points || []).length} positions${
              track.note ? ' · ' + escapeHtml(track.note) : ''}</div>
        </div>`;
    }).join('') || '<p class="note">No tracks yet.</p>';

    const pinRows = pins.map((pin, index) => `
        <div class="item">
          <div class="top">
            <strong>${escapeHtml(pin.title)}</strong>
            <button class="chip" data-pin="${index}">DELETE</button>
          </div>
          <div class="meta">${formatted(pin.latitude, pin.longitude)}</div>
        </div>`).join('') || '<p class="note">No pins yet.</p>';

    openSheet(incident,
        `<h4>Tracks</h4>${trackRows}${qualityLegend()}<h4>Pins</h4>${pinRows}`);

    document.querySelectorAll('[data-track]').forEach(button => {
        button.onclick = () => {
            tracks.splice(+button.dataset.track, 1);
            saveTracks(); closeSheet(); draw();
        };
    });
    document.querySelectorAll('[data-pin]').forEach(button => {
        button.onclick = () => {
            pins.splice(+button.dataset.pin, 1);
            savePins(); closeSheet(); draw();
        };
    });
};

// ----------------------------------------------------------------- chrome

function openSheet(title, html) {
    document.getElementById('sheetTitle').textContent = title;
    document.getElementById('sheetBody').innerHTML = html;
    document.getElementById('sheet').classList.remove('hidden');
}

function closeSheet() {
    document.getElementById('sheet').classList.add('hidden');
}

document.getElementById('sheetClose').onclick = closeSheet;
document.getElementById('sheet').onclick = event => {
    if (event.target.id === 'sheet') closeSheet();
};

let bannerTimer = null;
function banner(text, kind) {
    const element = document.getElementById('banner');
    if (!text) { element.classList.add('hidden'); return; }
    element.textContent = text;
    element.className = 'banner' + (kind ? ' ' + kind : '');
    clearTimeout(bannerTimer);
    bannerTimer = setTimeout(() => element.classList.add('hidden'), 6000);
}

function escapeHtml(text) {
    return String(text == null ? '' : text)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

// ------------------------------------------------------------------ start

// Before anything else: a recording the app was killed in the middle of.
recoverLive();

window.addEventListener('resize', resize);
resize();
showCoordinates();
startLocating();
document.getElementById('follow').classList.add('on');

if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => {
        banner('Offline support did not start.', 'warn');
    });
}

if (!K) {
    banner('The map engine did not load — reload the page.', 'bad');
}
