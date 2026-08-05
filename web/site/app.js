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

const C = (() => {
    const module = (typeof web !== 'undefined' && web) || window.web || {};
    const found = module.com && module.com.rhecyee &&
        module.com.rhecyee.firelinemap.web;
    return found ? found.FirelineContours : null;
})();

const L = (() => {
    const module = (typeof web !== 'undefined' && web) || window.web || {};
    const found = module.com && module.com.rhecyee &&
        module.com.rhecyee.firelinemap.web;
    return found ? found.FirelineLand : null;
})();

const T = (() => {
    const module = (typeof web !== 'undefined' && web) || window.web || {};
    const found = module.com && module.com.rhecyee &&
        module.com.rhecyee.firelinemap.web;
    return found ? found.FirelineTools : null;
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

/**
 * Incidents, each with its own pins and tracks.
 *
 * The phone keeps them apart and so does this: switching to a new fire clears
 * the map rather than stacking last week's drop points on top of this week's.
 * They are not deleted -- switching back brings them all straight back -- but
 * nothing from one incident is ever drawn on another, because a drop point
 * from the wrong fire is worse than no drop point at all.
 */
let incidents = Store.read('incidents', null);
let activeIncidentId = Store.read('activeIncident', null);

// Anything stored before incidents existed belongs to the first one.
if (!incidents) {
    const first = {
        id: 'i' + Date.now(),
        name: Store.read('incident', 'Incident'),
        startedAt: Date.now()
    };
    incidents = [first];
    activeIncidentId = first.id;
    Store.write('incidents', incidents);
    Store.write('activeIncident', activeIncidentId);
    const heldPins = Store.read('pins', []);
    const heldTracks = Store.read('tracks', []);
    if (heldPins.length) Store.write('pins.' + first.id, heldPins);
    if (heldTracks.length) Store.write('tracks.' + first.id, heldTracks);
}

function activeIncident() {
    return incidents.find(i => i.id === activeIncidentId) || incidents[0];
}

let incident = activeIncident().name;
let pins = Store.read('pins.' + activeIncidentId, []);
let tracks = Store.read('tracks.' + activeIncidentId, []);

const savePins = () => Store.write('pins.' + activeIncidentId, pins);
const saveTracks = () => Store.write('tracks.' + activeIncidentId, tracks);
const saveIncidents = () => {
    Store.write('incidents', incidents);
    Store.write('activeIncident', activeIncidentId);
};

// ------------------------------------------------------------------- map

const TILE = 256;
const TILE_HOST = 'https://basemap.nationalmap.gov/arcgis/rest/services';
const HYDRO_SERVICE = 'USGSHydroCached';

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

/**
 * A position set by tapping instead of by a receiver.
 *
 * For checking the map indoors, and marked in orange everywhere it appears so
 * it can never be mistaken for a fix. The phone has the same thing for the
 * same reason.
 */
let simulated = null;
let simMode = false;

/** The area a searched coordinate could be in, when digits were missing. */
let searchRegion = null;

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
function tile(z, x, y, service) {
    const from = service || basemap;
    const key = from + '/' + z + '/' + x + '/' + y;
    if (tileCache.has(key)) return tileCache.get(key);
    if (tileFailed.has(key)) return null;

    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = () => {
        heldTiles = tileCache.size;
        refreshStatus();
        draw();
    };
    image.onerror = () => { tileCache.delete(key); tileFailed.add(key); };
    image.src = `${TILE_HOST}/${from}/MapServer/tile/${z}/${y}/${x}`;
    tileCache.set(key, image);
    return image;
}

/**
 * The map is a panel in a column now, not the whole window.
 *
 * So its size comes from the box it sits in, and a touch has to be converted
 * from the page into that box before it means anything. Getting this wrong
 * puts a pin somewhere the finger was not, which is the one mistake this app
 * cannot make.
 */
function resize() {
    const ratio = dpr();
    const box = canvas.parentElement.getBoundingClientRect();
    const width = Math.max(1, Math.round(box.width));
    const height = Math.max(1, Math.round(box.height));
    canvas.width = width * ratio;
    canvas.height = height * ratio;
    canvas.style.width = width + 'px';
    canvas.style.height = height + 'px';
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    draw();
}

/** A page coordinate as a map coordinate. */
function local(clientX, clientY) {
    const box = canvas.getBoundingClientRect();
    return { x: clientX - box.left, y: clientY - box.top };
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
            const at = [
                Math.round(x * size - originX),
                Math.round(y * size - originY),
                Math.ceil(size),
                Math.ceil(size)
            ];
            const image = tile(level, wrapped, y);
            if (image && image.complete && image.naturalWidth) {
                ctx.drawImage(image, at[0], at[1], at[2], at[3]);
            }
            if (hydroOn) {
                const water = tile(level, wrapped, y, HYDRO_SERVICE);
                if (water && water.complete && water.naturalWidth) {
                    ctx.drawImage(water, at[0], at[1], at[2], at[3]);
                }
            }
        }
    }

    drawContours();
    drawTracks();
    drawMeasure();
    drawSearchRegion();
    drawPins();
    drawLandingZone();
    drawMe();
    drawLegend();
}

/**
 * What the colours mean, for the things currently drawn.
 *
 * Enough is on the map now -- three kinds of track line, a measurement, a
 * landing zone, a search area -- that the colours stopped explaining
 * themselves. Only for what is on screen: a key listing things nobody has
 * turned on is more to read, not less.
 */
function drawLegend() {
    if (!chromeVisible) return;
    const entries = [[simulated ? '#E65100' : '#2196F3',
        simulated ? 'Simulated position' : 'You', true]];
    if (live.recorder) entries.push(['#EF5350', 'Recording now', false]);
    if (tracks.some(t => trackQuality(t) === 'recorded')) {
        entries.push([TRACK_COLOURS[0], 'Recorded track', false]);
    }
    if (tracks.some(t => trackQuality(t) === 'imported')) {
        entries.push(['#90A4AE', 'Received track', false]);
    }
    if (measure.on) entries.push(['#FFC400', 'Measurement', false]);
    if (searchRegion) entries.push(['#40C4FF', 'Search area', false]);
    if (plan && plan.airPickupLatitude != null) entries.push(['#D50000', 'Landing zone', false]);
    if (entries.length < 2) return;

    ctx.font = '600 11px system-ui, sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    const width = 14 + Math.max(...entries.map(e => ctx.measureText(e[1]).width)) + 24;
    const height = entries.length * 16 + 10;
    const top = 8;

    ctx.fillStyle = 'rgba(11,23,31,0.82)';
    ctx.fillRect(8, top, width, height);
    entries.forEach((entry, index) => {
        const y = top + 13 + index * 16;
        ctx.fillStyle = entry[0];
        if (entry[2]) {
            ctx.beginPath();
            ctx.arc(16, y, 5, 0, Math.PI * 2);
            ctx.fill();
        } else {
            ctx.fillRect(11, y - 2, 11, 4);
        }
        ctx.fillStyle = 'rgba(255,255,255,0.88)';
        ctx.fillText(entry[1], 27, y);
    });
}

/**
 * Where a partly-known position could be.
 *
 * A coordinate read over a radio arrives with digits missing more often than
 * not. The phone draws the box those digits leave rather than picking a point
 * inside it and pretending, and so does this: a point drawn where the answer
 * merely might be is worse than an outline that says how much is unknown.
 */
function drawSearchRegion() {
    if (!searchRegion) return;
    const nw = toScreen(searchRegion.north, searchRegion.west);
    const se = toScreen(searchRegion.south, searchRegion.east);
    ctx.strokeStyle = '#40C4FF';
    if (searchRegion.exact) {
        ctx.lineWidth = 4;
        ctx.beginPath();
        ctx.arc(nw.x, nw.y, 20, 0, Math.PI * 2);
        ctx.stroke();
        ctx.fillStyle = '#40C4FF';
        ctx.beginPath();
        ctx.arc(nw.x, nw.y, 6, 0, Math.PI * 2);
        ctx.fill();
        return;
    }
    ctx.fillStyle = 'rgba(64,196,255,0.22)';
    ctx.fillRect(nw.x, nw.y, se.x - nw.x, se.y - nw.y);
    ctx.lineWidth = 3.5;
    ctx.strokeRect(nw.x, nw.y, se.x - nw.x, se.y - nw.y);
}

/**
 * The landing zone on the open 206, drawn where it was put.
 *
 * A coordinate typed into a form is a number somebody has to trust. Drawn on
 * the map it can be checked against the ground in a glance, which is the whole
 * reason for dropping it as a pin instead of reading it off a GPS.
 */
function drawLandingZone() {
    if (!plan || plan.airPickupLatitude == null || plan.airPickupLongitude == null) return;
    const at = toScreen(plan.airPickupLatitude, plan.airPickupLongitude);

    ctx.beginPath();
    ctx.arc(at.x, at.y, 24, 0, Math.PI * 2);
    ctx.lineWidth = 6;
    ctx.strokeStyle = 'rgba(255,255,255,0.9)';
    ctx.stroke();
    ctx.lineWidth = 3;
    ctx.strokeStyle = '#D50000';
    ctx.stroke();

    ctx.beginPath();
    ctx.arc(at.x, at.y, 6, 0, Math.PI * 2);
    ctx.fillStyle = '#D50000';
    ctx.fill();

    ctx.font = '800 13px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.lineWidth = 4;
    ctx.strokeStyle = 'rgba(0,0,0,0.75)';
    ctx.fillStyle = '#fff';
    // "LZ" always, and the name under it when there is one: the name is what
    // goes over the radio, the mark is what gets flown to.
    ctx.strokeText('LZ', at.x, at.y - 34);
    ctx.fillText('LZ', at.x, at.y - 34);
    if (plan.airPickupName) {
        ctx.strokeText(plan.airPickupName.slice(0, 14), at.x, at.y + 38);
        ctx.fillText(plan.airPickupName.slice(0, 14), at.x, at.y + 38);
    }
}

// ------------------------------------------------------------- contours

/**
 * Contours, traced from the same elevation data the phone uses.
 *
 * The browser does the two things a browser is good at -- fetch a tile and
 * read its pixels -- and hands the numbers to the phone's own code for
 * everything after that: decoding terrarium into metres, choosing an interval
 * off the USGS quadrangle ladder, tracing the lines. A contour is a claim
 * about the shape of the ground, and two apps tracing it differently would put
 * the same ridge in two places.
 *
 * Retraced only when the view has actually moved somewhere new. Tracing is the
 * expensive part and a pan is not a reason to redo it: the lines are held in
 * geography, so panning just draws them again somewhere else.
 */
let contoursOn = Store.read('contours', false);
let contourDetail = Store.read('contourDetail', 'NORMAL');
const contourState = { lines: [], intervalFeet: 0, key: null, busy: false };

const demCache = new Map();

/** One DEM tile, decoded to metres. Held once read; they are not small. */
function demTile(zoom, x, y) {
    const key = zoom + '/' + x + '/' + y;
    if (demCache.has(key)) return demCache.get(key);

    const entry = { heights: null };
    demCache.set(key, entry);

    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = () => {
        const size = C.tileSize;
        const scratch = document.createElement('canvas');
        scratch.width = size;
        scratch.height = size;
        const paint = scratch.getContext('2d', { willReadFrequently: true });
        paint.drawImage(image, 0, 0);
        const data = paint.getImageData(0, 0, size, size).data;
        const heights = new Float64Array(size * size);
        for (let i = 0; i < heights.length; i++) {
            heights[i] = C.elevationOf(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]);
        }
        entry.heights = heights;
        contourState.key = null;   // force a retrace now there is more ground
        refreshContours();
    };
    image.onerror = () => { demCache.delete(key); };
    image.src = C.tileUrl(zoom, x, y);
    return entry;
}

/**
 * Builds the grid under the current view and traces it.
 *
 * Sampled onto a grid of its own rather than handed the tiles directly, so the
 * trace does not have to know about tile seams. Coarse on purpose: contours
 * are a shape, and a grid finer than the data underneath it only traces the
 * elevation model's own noise.
 */
function refreshContours() {
    if (!contoursOn || !C) { contourState.lines = []; return; }

    const level = Math.min(C.maxZoom, Math.max(6, Math.round(view.zoom)));
    const width = canvas.width / dpr();
    const height = canvas.height / dpr();
    const nw = toGeo(0, 0);
    const se = toGeo(width, height);
    const key = level + '|' + nw.latitude.toFixed(3) + '|' + nw.longitude.toFixed(3) +
        '|' + se.latitude.toFixed(3) + '|' + se.longitude.toFixed(3) + '|' + contourDetail;
    if (key === contourState.key) return;
    contourState.key = key;

    const size = C.tileSize;
    const count = Math.pow(2, level);
    const columns = 128;
    const rows = 128;
    const heights = new Array(columns * rows);
    let known = 0;

    for (let row = 0; row < rows; row++) {
        const latitude = nw.latitude + (se.latitude - nw.latitude) * (row / (rows - 1));
        for (let column = 0; column < columns; column++) {
            const longitude = nw.longitude +
                (se.longitude - nw.longitude) * (column / (columns - 1));
            const px = (longitude + 180) / 360 * count * size;
            const sin = Math.sin(Math.max(-85.05, Math.min(85.05, latitude)) * Math.PI / 180);
            const py = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * count * size;
            const tileX = Math.floor(px / size);
            const tileY = Math.floor(py / size);
            if (tileY < 0 || tileY >= count) { heights[row * columns + column] = 0; continue; }
            const held = demTile(level, ((tileX % count) + count) % count, tileY);
            if (!held.heights) { heights[row * columns + column] = 0; continue; }
            const inX = Math.min(size - 1, Math.max(0, Math.floor(px - tileX * size)));
            const inY = Math.min(size - 1, Math.max(0, Math.floor(py - tileY * size)));
            heights[row * columns + column] = held.heights[inY * size + inX];
            known++;
        }
    }

    // Nothing is drawn from a grid that is mostly holes: a contour traced
    // across missing ground is a line nobody walked.
    if (known < columns * rows * 0.6) { contourState.lines = []; return; }

    const traced = JSON.parse(C.trace(
        heights, columns, rows,
        nw.latitude, se.latitude, nw.longitude, se.longitude,
        level, contourDetail
    ));
    contourState.lines = traced.lines || [];
    contourState.intervalFeet = traced.intervalFeet || 0;
    refreshStatus();
    draw();
}

function drawContours() {
    if (!contoursOn || !contourState.lines.length) return;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    contourState.lines.forEach(line => {
        const points = line.points;
        if (points.length < 4) return;
        // Index lines heavier and lighter in colour: they are the ones that
        // carry the numbers, and on a busy slope they are what makes the rest
        // countable.
        ctx.strokeStyle = line.index ? 'rgba(180,120,60,0.95)' : 'rgba(150,100,50,0.65)';
        ctx.lineWidth = line.index ? 1.8 : 1;
        ctx.beginPath();
        for (let i = 0; i < points.length; i += 2) {
            const at = toScreen(points[i], points[i + 1]);
            if (i === 0) ctx.moveTo(at.x, at.y); else ctx.lineTo(at.x, at.y);
        }
        ctx.stroke();
    });
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
    if (simulated) {
        const spot = toScreen(simulated.latitude, simulated.longitude);
        ctx.fillStyle = '#E65100';
        ctx.strokeStyle = '#fff';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(spot.x, spot.y, 9, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
        return;
    }
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

/**
 * A pin being dragged.
 *
 * Held here rather than in the pin sheet because a drag starts on the map: the
 * operator puts a finger on the pin and moves it, which is the obvious gesture
 * and the one they will try first. The MOVE button in the pin sheet does the
 * same job for anyone who taps rather than drags.
 */
let draggingPin = null;

canvas.addEventListener('pointerdown', event => {
    canvas.setPointerCapture(event.pointerId);
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    lastPan = { x: event.clientX, y: event.clientY };
    movedSincePress = 0;
    if (pointers.size === 2) { pinchFrom = spread(); draggingPin = null; return; }
    // Only when nothing else wants the tap: measuring and placing both use it.
    if (!measure.on && !pinArmed && movingPin === null) {
        const at = local(event.clientX, event.clientY);
        draggingPin = pinAt(at.x, at.y);
    }
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

        // A pin under the finger moves instead of the map. The threshold
        // stops a slightly unsteady tap from nudging a pin somebody meant
        // only to open.
        if (draggingPin !== null && movedSincePress > 8) {
            const at = local(event.clientX, event.clientY);
            const where = toGeo(at.x, at.y);
            pins[draggingPin].latitude = where.latitude;
            pins[draggingPin].longitude = where.longitude;
            draw();
            return;
        }
        panBy(-dx, -dy);
    }
});

function endPointer(event) {
    const had = pointers.size;
    pointers.delete(event.pointerId);
    if (pointers.size < 2) pinchFrom = null;
    if (pointers.size === 0) {
        lastPan = null;
        if (draggingPin !== null && movedSincePress > 8) {
            savePins();
            banner(pins[draggingPin].title + ' moved.', 'good');
            draggingPin = null;
            draw();
            return;
        }
        draggingPin = null;
        // A tap, not a drag. The threshold is generous because a gloved
        // finger never lands perfectly still.
        if (had === 1 && movedSincePress < 12) {
            const at = local(event.clientX, event.clientY);
            onTap(at.x, at.y);
        }
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
    refreshContours();
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

function here() {
    return simulated || position;
}

function showCoordinates() {
    const label = document.getElementById('coords');
    const at = here();
    if (!at) {
        label.textContent = simMode ? 'Tap the map to set a test position'
            : 'Waiting for GPS…';
        return;
    }
    label.textContent = formatted(at.latitude, at.longitude);
    document.getElementById('accuracy').textContent = simulated
        ? 'SIMULATED — NOT A FIX'
        : (at.accuracy ? '±' + Math.round(at.accuracy) + ' m' : '—');
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

document.getElementById('copyCoords').onclick = async () => {
    const at = here();
    if (!at) { banner('No position to copy yet.', 'warn'); return; }
    const text = formatted(at.latitude, at.longitude);
    try { await navigator.clipboard.writeText(text); banner('Copied.', 'good'); }
    catch (e) { banner('Could not reach the clipboard.', 'bad'); }
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
    view.zoom = Math.min(16, view.zoom + 1); draw(); refreshContours();
};
document.getElementById('zoomOut').onclick = () => {
    view.zoom = Math.max(3, view.zoom - 1); draw(); refreshContours();
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
    live.recorder = K.recorder(stopSeconds);
    live.points = [];
    live.startedAt = Date.now();
    saveLive();
    holdScreenAwake();
    document.getElementById('recordTool').classList.add('rec');
    document.getElementById('recordTool').innerHTML = '<i>\u25c9</i>Recording';
    const note = document.getElementById('recordNote');
    note.textContent = 'Recording on movement · pauses after ' +
        (STOP_CHOICES.find(c => c[0] === stopSeconds) || [0, ''])[1] + ' stopped';
    note.classList.remove('hidden');
    showTravel();
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
    document.getElementById('recordTool').innerHTML = '<i>\u25c9</i>Auto Record';
    document.getElementById('recordNote').classList.add('hidden');
    document.getElementById('travel').classList.add('hidden');

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

// ------------------------------------------------------------- measuring

/**
 * Measuring, as a run of taps.
 *
 * Distance by default and area when closed, which are the two questions
 * actually asked: how much line is left to cut, and how big is the black.
 * Every figure comes back from the phone's own session -- the browser does not
 * do the arithmetic, it only shows it.
 */
const measure = { on: false, area: false, points: [], elevations: [], pending: 0 };

/**
 * Ground elevation for a measured point.
 *
 * The same public-domain USGS service the phone uses, asked for through the
 * shared code so both read a reply the same way -- including the sentinel the
 * service returns where it has no coverage, which taken as a number is an
 * elevation a thousand kilometres underground and would carry straight into a
 * slope. Needs a connection; without one the figure stays absent and the panel
 * says so rather than showing a grade nobody can stand on.
 */
async function lookUpElevation(index, latitude, longitude) {
    if (!T) return;
    measure.pending++;
    showMeasure();
    try {
        const reply = await fetch(T.elevationUrl(latitude, longitude));
        if (!reply.ok) return;
        const value = T.elevationFrom(await reply.text());
        if (value != null && measure.points[index]) measure.elevations[index] = value;
    } catch (e) {
        // Offline, or the service is down. Left absent on purpose.
    } finally {
        measure.pending--;
        showMeasure();
    }
}

document.getElementById('measureTool').onclick = () => {
    measure.on = !measure.on;
    if (!measure.on) { measure.points = []; measure.elevations = []; }
    if (measure.on) disarmResources();
    document.getElementById('measureTool').classList.toggle('on', measure.on);
    document.getElementById('measurePanel').classList.toggle('hidden', !measure.on);
    draw();
    showMeasure();
};

function addMeasurePoint(latitude, longitude) {
    const index = measure.points.length;
    measure.points.push([latitude, longitude]);
    measure.elevations[index] = null;
    lookUpElevation(index, latitude, longitude);
    draw();
    showMeasure();
}

/**
 * The measuring readout, as a panel above the map rather than a sheet.
 *
 * A sheet covers the ground being measured, which is the one thing that has to
 * stay visible while points are being tapped onto it. The phone puts this in a
 * strip at the top for exactly that reason, so this does too.
 */
function showMeasure() {
    const holder = document.getElementById('measurePanel');
    if (!measure.on) { holder.innerHTML = ''; return; }
    if (!T) { banner('The measuring tool did not load.', 'bad'); return; }

    const flat = [];
    measure.points.forEach(p => { flat.push(p[0]); flat.push(p[1]); });
    const out = JSON.parse(T.measureWithElevations(
        JSON.stringify(flat),
        JSON.stringify(measure.elevations.slice(0, measure.points.length)),
        measure.area
    ));
    const last = (out.legs || [])[(out.legs || []).length - 1];

    holder.innerHTML = `
        <div class="row">
          <button class="mode${measure.area ? '' : ' on'}" id="modeLine">LINE</button>
          <button class="mode${measure.area ? ' on' : ''}" id="modeArea">POLYGON</button>
          <span class="spacer"></span>
          <button class="flat" id="measureUndo">UNDO</button>
          <button class="flat" id="measureClear">CLEAR</button>
        </div>
        ${out.ready ? `
          <div class="total">${measure.area ? 'Perimeter' : 'Total'} ${out.distance}
            &nbsp;·&nbsp; ${out.chains} &nbsp;·&nbsp; ${out.points} points</div>
          ${out.area ? `<div class="area">Area ${out.area}</div>` : ''}
          ${last ? `<div class="leg">Last leg ${last.distance} ${last.chains}
            &nbsp;bearing ${last.bearing}${
              last.slope ? '&nbsp; slope ' + last.slope : ''}</div>` : ''}
          <div class="leg">${
            out.gain ? 'Gain ' + out.gain + ' &nbsp;·&nbsp; Loss ' + out.loss +
              (out.slope ? ' &nbsp;·&nbsp; overall ' + out.slope : '')
            : measure.pending ? 'Looking up ground elevation…'
            : 'Slope unavailable — needs a connection for elevation'}</div>`
        : `<div class="leg">${measure.area
            ? 'POLYGON — tap three or more points to enclose an area.'
            : 'LINE — tap two points, or keep tapping to follow a road.'}</div>`}
    `;

    document.getElementById('modeLine').onclick = () => {
        measure.area = false; showMeasure(); draw();
    };
    document.getElementById('modeArea').onclick = () => {
        measure.area = true; showMeasure(); draw();
    };
    document.getElementById('measureUndo').onclick = () => {
        measure.points.pop();
        measure.elevations.pop();
        draw();
        showMeasure();
    };
    document.getElementById('measureClear').onclick = () => {
        measure.points = [];
        measure.elevations = [];
        draw();
        showMeasure();
    };
}

function drawMeasure() {
    if (!measure.on || measure.points.length === 0) return;
    ctx.strokeStyle = '#FFC400';
    ctx.fillStyle = 'rgba(255,196,0,0.16)';
    ctx.lineWidth = 3;
    ctx.setLineDash([6, 5]);
    ctx.beginPath();
    measure.points.forEach((p, i) => {
        const at = toScreen(p[0], p[1]);
        if (i === 0) ctx.moveTo(at.x, at.y); else ctx.lineTo(at.x, at.y);
    });
    if (measure.area && measure.points.length >= 3) { ctx.closePath(); ctx.fill(); }
    ctx.stroke();
    ctx.setLineDash([]);

    measure.points.forEach(p => {
        const at = toScreen(p[0], p[1]);
        ctx.fillStyle = '#FFC400';
        ctx.beginPath();
        ctx.arc(at.x, at.y, 5, 0, Math.PI * 2);
        ctx.fill();
    });
}

// ------------------------------------------------------------------ pins

let pinArmed = false;

/**
 * The symbol is chosen before the tap, not after it.
 *
 * The phone shows a palette the moment Resources is armed, so the sequence is
 * pick-then-place: one decision made standing still, then a tap on the ground.
 * Asking for the symbol afterwards means holding a half-made pin through a
 * dialog while somebody is talking on the radio.
 */
let selectedSymbol = Store.read('symbol', 'drop_point');

function disarmResources() {
    pinArmed = false;
    document.getElementById('pinTool').classList.remove('on');
    document.getElementById('palette').classList.add('hidden');
}

document.getElementById('pinTool').onclick = () => {
    pinArmed = !pinArmed;
    if (pinArmed) {
        measure.on = false;
        document.getElementById('measureTool').classList.remove('on');
        document.getElementById('measurePanel').classList.add('hidden');
        draw();
    }
    document.getElementById('pinTool').classList.toggle('on', pinArmed);
    document.getElementById('palette').classList.toggle('hidden', !pinArmed);
    if (pinArmed) showPalette();
};

function showPalette() {
    const holder = document.getElementById('palette');
    holder.innerHTML = '';
    SYMBOLS.forEach(([id, label, glyph]) => {
        const button = document.createElement('button');
        button.textContent = glyph + ' ' + label;
        button.dataset.symbol = id;
        if (id === selectedSymbol) button.classList.add('on');
        button.onclick = () => {
            selectedSymbol = id;
            Store.write('symbol', id);
            showPalette();
        };
        holder.appendChild(button);
    });
}

/** The pin under a finger, if any. Generous, because gloves are not precise. */
function pinAt(mapX, mapY) {
    let best = null;
    let bestDistance = 34;
    pins.forEach((pin, index) => {
        const at = toScreen(pin.latitude, pin.longitude);
        const away = Math.hypot(at.x - mapX, at.y - mapY);
        if (away < bestDistance) { bestDistance = away; best = index; }
    });
    return best;
}

function onTap(mapX, mapY) {
    const where = toGeo(mapX, mapY);

    if (placingLandingZone) {
        // The one tap the 206 is waiting on, so it comes before every tool.
        placingLandingZone = false;
        plan.airPickupLatitude = where.latitude;
        plan.airPickupLongitude = where.longitude;
        savePlan();
        banner('Landing zone set. It is marked LZ on the map.', 'good');
        draw();
        showPlan();
        return;
    }

    if (simMode) {
        simulated = { latitude: where.latitude, longitude: where.longitude };
        showCoordinates();
        refreshSimBanner();
        draw();
        return;
    }

    if (measure.on) { addMeasurePoint(where.latitude, where.longitude); return; }

    if (movingPin !== null) {
        // A pin being relocated takes the next tap wherever it lands.
        pins[movingPin].latitude = where.latitude;
        pins[movingPin].longitude = where.longitude;
        savePins();
        const moved = movingPin;
        movingPin = null;
        banner('Moved.', 'good');
        draw();
        showPin(moved);
        return;
    }

    if (pinArmed) {
        // The palette stays armed. On a phone the next thing after dropping a
        // drop point is usually another drop point, and re-arming between each
        // one is a tap per pin for nothing.
        placePin(where.latitude, where.longitude);
        return;
    }

    // A pin under the finger is the more specific question than the ground
    // beneath it, so it is asked first.
    const hit = pinAt(mapX, mapY);
    if (hit !== null) { showPin(hit); return; }

    const report = K && K.tracksAt(
        where.latitude, where.longitude, JSON.stringify({ tracks })
    );
    const parsed = report && JSON.parse(report);
    if (parsed && parsed.count > 0) { showOverlap(parsed); return; }

    // Last, because a pin or a track under the finger is the more specific
    // question than the ground beneath it.
    if (landOn) showLandStatus(where.latitude, where.longitude);
}

/** Set while a pin is waiting for a tap to say where it goes. */
let movingPin = null;

/**
 * One pin, and everything that can be done to it.
 *
 * Its position in every format, because which one is wanted depends on who is
 * asking: a crew wants degrees and minutes, aviation wants a grid.
 */
function showPin(index) {
    const pin = pins[index];
    if (!pin) return;
    const grid = K ? K.formatMgrs(pin.latitude, pin.longitude, 5) : null;
    const utm = K ? K.formatUtm(pin.latitude, pin.longitude) : null;

    openSheet(pin.title, `
        <div class="figure"><span>Degrees and minutes</span></div>
        <p class="note" style="font-size:15px">${
            K ? K.formatDdm(pin.latitude, pin.longitude) : ''}</p>
        ${grid ? `<div class="figure"><span>Grid</span><b>${grid}</b></div>` : ''}
        ${utm ? `<div class="figure"><span>UTM</span><b>${utm}</b></div>` : ''}
        <h4>Name</h4>
        <input id="pinTitle" value="${escapeHtml(pin.title)}">
        <h4>Note</h4>
        <input id="pinNote" value="${escapeHtml(pin.note || '')}"
               placeholder="Turnaround for tenders…">
        <h4>Symbol</h4>
        <div id="symbols"></div>
        <button class="wide" id="pinSave">SAVE</button>
        <button class="wide quiet" id="pinMove">MOVE — then tap where it goes</button>
        <button class="wide quiet" id="pinCopy">COPY THE POSITION</button>
        <button class="wide danger" id="pinDelete">DELETE THIS PIN</button>
    `);

    let chosen = pin.symbolId || 'other';
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
        pin.title = document.getElementById('pinTitle').value.trim() || pin.title;
        pin.note = document.getElementById('pinNote').value.trim() || null;
        pin.symbolId = chosen;
        savePins();
        closeSheet();
        draw();
    };
    document.getElementById('pinMove').onclick = () => {
        movingPin = index;
        closeSheet();
        banner('Tap where ' + pin.title + ' should go.', 'good');
    };
    document.getElementById('pinCopy').onclick = async () => {
        const text = pin.title + '  ' + K.formatDdm(pin.latitude, pin.longitude) +
            (grid ? '  ' + grid : '');
        try { await navigator.clipboard.writeText(text); banner('Copied.', 'good'); }
        catch (e) { banner('Could not reach the clipboard.', 'bad'); }
    };
    document.getElementById('pinDelete').onclick = () => {
        // No confirmation: a pin is one tap to put back, and a dialog in
        // gloves costs more than the mistake does.
        pins.splice(index, 1);
        savePins();
        closeSheet();
        draw();
        banner('Deleted.', 'warn');
    };
}

/**
 * Names the pin that was just dropped.
 *
 * The symbol was already chosen on the palette, so this asks one question. The
 * suggested name counts up from what is already on the map -- "DP 3" after two
 * drop points -- because the numbering is the thing people actually want and
 * typing it every time is how it gets skipped.
 */
function placePin(latitude, longitude) {
    const symbol = SYMBOLS.find(s => s[0] === selectedSymbol) || SYMBOLS[0];
    const already = pins.filter(p => p.symbolId === selectedSymbol).length;
    const suggested = symbol[2] + ' ' + (already + 1);

    openSheet(symbol[1], `
        <p class="note">${formatted(latitude, longitude)}</p>
        <input id="pinName" value="${escapeHtml(suggested)}" autocomplete="off">
        <h4>Note</h4>
        <input id="pinNote" placeholder="Turnaround for tenders…" autocomplete="off">
        <button class="wide" id="pinSave">DROP IT</button>
        <button class="wide quiet" id="pinCancel">NOT HERE</button>
    `);

    document.getElementById('pinSave').onclick = () => {
        const name = document.getElementById('pinName').value.trim();
        const note = document.getElementById('pinNote').value.trim();
        pins.push({
            id: 'w' + Date.now(),
            title: name || suggested,
            latitude, longitude,
            symbolId: selectedSymbol,
            note: note || null,
            createdAt: Date.now()
        });
        savePins();
        closeSheet();
        draw();
    };
    document.getElementById('pinCancel').onclick = closeSheet;
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


// -------------------------------------------------------------------- 206

/**
 * The medical plan, which is the one thing here that has to work first time.
 *
 * The form is filled in from what the app already knows -- incident, position,
 * who is reporting -- because nobody types a coordinate with a patient on the
 * ground. Everything it produces comes from the phone's own readout, in the
 * phone's own order: whoever is copying it writes the same things in the same
 * sequence every time, and a browser that reordered them would cost the person
 * copying more than it saved.
 */
let plan = Store.read('medical', null);

function blankPlan() {
    return {
        createdAt: Date.now(),
        incidentName: incident,
        latitude: here() ? here().latitude : null,
        longitude: here() ? here().longitude : null,
        accuracyMeters: simulated ? null : (position ? position.accuracy : null),
        reporterName: Store.read('author', '') || null,
        priority: 'RED',
        patientCount: 1,
        transport: 'GROUND',
        resources: [],
        natureOfInjury: null,
        patientAssessment: null,
        lzHazards: null,
        airPickupName: null,
        airPickupLatitude: null,
        airPickupLongitude: null,
        format: 'MIR'
    };
}

function savePlan() { Store.write('medical', plan); }

/** Set while the next map tap is to become the landing zone. */
let placingLandingZone = false;

document.getElementById('medicalTool').onclick = () => {
    if (!plan) { plan = blankPlan(); savePlan(); }
    showPlan();
};

/**
 * The nearest drop point or helispot already on the map.
 *
 * Offered because it is somewhere the responding unit already has, and can
 * drive to without anybody reading a coordinate aloud. Only when it is close
 * enough to be the same place.
 */
function nearestDropPoint() {
    if (!plan || plan.latitude == null || !K) return null;
    let best = null, bestAway = 1609;
    pins.forEach(pin => {
        if (pin.symbolId !== 'drop_point' && pin.symbolId !== 'helispot') return;
        const away = K.distanceMeters(plan.latitude, plan.longitude,
            pin.latitude, pin.longitude);
        if (away < bestAway) { bestAway = away; best = pin; }
    });
    return best ? { pin: best, away: bestAway } : null;
}

function showPlan() {
    if (!T || !K) { banner('The 206 did not load.', 'bad'); return; }
    const out = JSON.parse(T.medicalPlan(JSON.stringify(plan)));
    const choices = JSON.parse(T.medicalChoices());
    const near = nearestDropPoint();

    const chips = (list, current, group) => list.map(c =>
        `<button class="chip" data-group="${group}" data-id="${c.id}"
            style="margin:3px;background:${
                (Array.isArray(current) ? current.includes(c.id) : current === c.id)
                ? '#1565C0' : '#25404F'}">${c.label}</button>`).join('');

    openSheet('206 — Medical plan', `
        ${out.missing.length ? `<p class="note warn">Still needed before this goes
            out: ${out.missing.join(', ')}.</p>`
          : '<p class="note good">Ready to transmit.</p>'}

        <h4>Priority</h4><div>${chips(choices.priority, plan.priority, 'priority')}</div>
        <h4>Patients</h4><div>${chips(
            [1,2,3,4].map(n => ({ id: String(n), label: n === 4 ? '4+' : String(n) })),
            String(plan.patientCount), 'patients')}</div>
        <h4>Transport</h4><div>${chips(choices.transport, plan.transport, 'transport')}</div>

        ${out.needsAir ? `
          <h4>Where does it land</h4>
          <p class="note">If the aircraft cannot land on the patient, name the
            helispot or drop point, or drop a pin for the LZ. The readout then says
            the patient is carried to it, which is what tells dispatch a ground unit
            is needed too.</p>
          <input id="planLanding" value="${escapeHtml(plan.airPickupName || '')}"
                 placeholder="Helispot or drop point — H-3, DP-7">
          <p class="note">The name on the IAP, not a coordinate — that is what
            goes over the radio. Drop a pin for the position.</p>
          <button class="wide quiet" id="planDropLz">${
            (plan.airPickupLatitude != null) ? 'LZ PIN SET — MOVE IT'
            : 'DROP A PIN FOR THE LZ'}</button>
          ${(plan.airPickupLatitude != null || plan.airPickupName) ? `
            <button class="wide quiet" id="planNoLz">IT LANDS AT THE PATIENT</button>` : ''}
          <h4>LZ hazards</h4>
          <input id="planHazards" value="${escapeHtml(plan.lzHazards || '')}"
                 placeholder="Wires north, snags east…">` : ''}

        <h4>Resources</h4><div>${chips(choices.resources, plan.resources, 'resources')}</div>

        <h4>Nature of injury</h4>
        <input id="planNature" value="${escapeHtml(plan.natureOfInjury || '')}">
        <h4>Patient assessment</h4>
        <input id="planAssessment" value="${escapeHtml(plan.patientAssessment || '')}">

        <h4>Radio name</h4>
        <p class="note"><b>${escapeHtml(out.radioName)} Medical</b> — this is the
          incident name, not a callsign.</p>

        ${near ? `<p class="note warn">Nearest drop point:
            ${escapeHtml(near.pin.title)}, ${
              Math.round(near.away * 3.280839895)} ft away — give this as the
            location if it is close enough. It is already on their map.</p>` : ''}

        <button class="wide" id="planRead">READ IT OUT</button>
        <button class="wide quiet" id="planCopy">COPY THE SCRIPT</button>
        <button class="wide danger" id="planClear">CLEAR THIS 206</button>
    `);

    document.querySelectorAll('#sheetBody .chip[data-group]').forEach(button => {
        button.onclick = () => {
            const id = button.dataset.id;
            switch (button.dataset.group) {
                case 'priority': plan.priority = id; break;
                case 'patients': plan.patientCount = +id; break;
                case 'transport': plan.transport = id; break;
                case 'resources':
                    plan.resources = plan.resources.includes(id)
                        ? plan.resources.filter(r => r !== id)
                        : plan.resources.concat([id]);
                    break;
            }
            capturePlanText();
            savePlan();
            showPlan();
        };
    });

    document.getElementById('planDropLz') && (
        document.getElementById('planDropLz').onclick = () => {
            capturePlanText(); savePlan();
            placingLandingZone = true;
            // The form covers the map, so it goes away for the one tap and
            // comes straight back with the answer in it.
            closeSheet();
            banner('Tap the map where the aircraft can land. ' +
                'The 206 comes back with it filled in.', 'good');
        });

    // Clears the carry entirely: no name, no pin, so the readout goes back to
    // saying the aircraft lands on the patient.
    document.getElementById('planNoLz') && (
        document.getElementById('planNoLz').onclick = () => {
            plan.airPickupName = null;
            plan.airPickupLatitude = null;
            plan.airPickupLongitude = null;
            savePlan();
            draw();
            showPlan();
        });

    document.getElementById('planRead').onclick = () => {
        capturePlanText(); savePlan();
        openSheet('Read this out', `
            ${out.missing.length ? `<p class="note warn">Gaps: ${
                out.missing.join(', ')}.</p>` : ''}
            <div class="readout">${escapeHtml(
                JSON.parse(T.medicalPlan(JSON.stringify(plan))).script)}</div>
            <button class="wide quiet" id="backToPlan">BACK TO THE FORM</button>`);
        document.getElementById('backToPlan').onclick = showPlan;
    };

    document.getElementById('planCopy').onclick = async () => {
        capturePlanText(); savePlan();
        const script = JSON.parse(T.medicalPlan(JSON.stringify(plan))).script;
        try { await navigator.clipboard.writeText(script); banner('Copied.', 'good'); }
        catch (e) { banner('Could not reach the clipboard.', 'bad'); }
    };

    document.getElementById('planClear').onclick = () => {
        plan = null; Store.write('medical', null); closeSheet();
        banner('206 cleared.', 'warn');
    };
}

/** Reads the typed fields back before anything redraws the form. */
function capturePlanText() {
    const take = id => {
        const element = document.getElementById(id);
        return element ? (element.value.trim() || null) : undefined;
    };
    const nature = take('planNature');
    const assessment = take('planAssessment');
    const hazards = take('planHazards');
    const landing = take('planLanding');
    if (nature !== undefined) plan.natureOfInjury = nature;
    if (assessment !== undefined) plan.patientAssessment = assessment;
    if (hazards !== undefined) plan.lzHazards = hazards;
    if (landing !== undefined) plan.airPickupName = landing;
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

// ---------------------------------------------------------- folding chrome

/**
 * The controls fold away when nothing is being done with them.
 *
 * The reason is the map. Every panel takes a strip of screen, and the screen
 * is how far ahead the operator can see; on a phone in a truck that is the
 * difference between reading the next drainage and not. Touching anything
 * brings them straight back.
 *
 * An armed tool holds them open. Nothing is more irritating than a
 * measurement's readout vanishing halfway through taking it.
 */
const CHROME_CHOICES = [[0, 'Never'], [10, '10 s'], [20, '20 s'], [45, '45 s'], [90, '90 s']];

let chromeSeconds = Store.read('chromeSeconds', 20);
let chromeVisible = true;
let chromeTimer = null;

function toolArmed() {
    return measure.on || pinArmed || placingLandingZone || simMode;
}

function isSheetOpen() {
    return !document.getElementById('sheet').classList.contains('hidden');
}

function setChrome(visible) {
    if (chromeVisible === visible) return;
    chromeVisible = visible;
    document.getElementById('chrome').classList.toggle('hidden', !visible);
    document.getElementById('topbar').classList.toggle('hidden', !visible);
    resize();
}

function touched() {
    setChrome(true);
    clearTimeout(chromeTimer);
    if (!chromeSeconds) return;
    chromeTimer = setTimeout(() => {
        if (!toolArmed() && !isSheetOpen()) setChrome(false);
    }, chromeSeconds * 1000);
}

['pointerdown', 'keydown'].forEach(kind =>
    document.addEventListener(kind, touched, { passive: true }));

// ------------------------------------------------------------- incidents

/**
 * What is under the map, in a line.
 *
 * The phone's status row says which sheet is in use and whether anything is
 * wrong with it. There is no sheet here, so it says which basemap is drawn and
 * how much of it is held for going offline -- the same question, which is
 * "what will still be here when the signal goes".
 */
/**
 * Says, and keeps saying, that the dot is not a fix.
 *
 * The phone carries this as a banner rather than a line in the readout because
 * a simulated position looks exactly like a real one on the map, and somebody
 * who did not turn it on has no reason to doubt it.
 */
function refreshSimBanner() {
    const bar = document.getElementById('simBanner');
    if (!simMode) { bar.classList.add('hidden'); return; }
    bar.textContent = simulated
        ? 'SIMULATED POSITION — NOT A GPS FIX · turn sim off in Settings to return to GPS'
        : 'SIM MODE — tap the map to set a test position';
    bar.className = 'banner' + (simulated ? ' bad' : '');
    bar.classList.remove('hidden');
}

function refreshStatus() {
    const row = document.getElementById('status');
    const chosen = BASEMAPS.find(b => b[0] === basemap);
    row.className = 'card ok';
    row.textContent = (chosen ? chosen[1] : basemap) +
        (hydroOn ? ' + water' : '') +
        (contoursOn ? ' + contours' + (contourState.intervalFeet
            ? ' at ' + contourState.intervalFeet + ' ft' : '') : '') +
        ' · ' + heldTiles + ' tile' + (heldTiles === 1 ? '' : 's') + ' held';
    row.classList.remove('hidden');
}

function refreshTopBar() {
    document.getElementById('incidentName').textContent = incident;
    document.getElementById('incidentSub').textContent =
        incidents.length > 1 ? 'TAP TO CHANGE INCIDENT'
        : 'TAP TO NAME OR ADD AN INCIDENT';
}

document.getElementById('incidentBox').onclick = showIncidents;

function switchIncident(id) {
    if (id === activeIncidentId) { closeSheet(); return; }
    // Nothing is thrown away -- the other incident's pins stay under its own
    // key -- but nothing of it is drawn here either.
    activeIncidentId = id;
    saveIncidents();
    incident = activeIncident().name;
    pins = Store.read('pins.' + id, []);
    tracks = Store.read('tracks.' + id, []);
    // The 206 belongs to the incident it was raised on.
    plan = null;
    Store.write('medical', null);
    assembly = { parts: {}, checksum: null, total: 0 };
    refreshTopBar();
    closeSheet();
    draw();
    banner('Now on ' + incident + '.', 'good');
}

function showIncidents() {
    const rows = incidents.map(entry => {
        const held = Store.read('pins.' + entry.id, []).length;
        const ran = Store.read('tracks.' + entry.id, []).length;
        return `
        <div class="item">
          <div class="top">
            <strong>${escapeHtml(entry.name)}</strong>
            ${entry.id === activeIncidentId
                ? '<b class="good">OPEN</b>'
                : `<button class="chip" data-open="${entry.id}">OPEN</button>`}
          </div>
          <div class="meta">${held} pin${held === 1 ? '' : 's'} ·
            ${ran} track${ran === 1 ? '' : 's'} ·
            started ${new Date(entry.startedAt).toLocaleDateString()}</div>
          <div class="top" style="margin-top:6px">
            <button class="chip" data-rename="${entry.id}">RENAME</button>
            ${incidents.length > 1
                ? `<button class="chip" data-drop="${entry.id}">DELETE</button>` : ''}
          </div>
        </div>`;
    }).join('');

    openSheet('Incidents', `
        <p class="note">Each incident keeps its own pins and tracks. Opening a
          different one clears the map — nothing is lost, and switching back
          brings it all straight back.</p>
        ${rows}
        <h4>Start another</h4>
        <input id="newIncident" placeholder="Burnt Creek 2026" autocomplete="off">
        <button class="wide" id="addIncident">START THIS INCIDENT</button>
    `);

    document.querySelectorAll('[data-open]').forEach(button => {
        button.onclick = () => switchIncident(button.dataset.open);
    });

    document.querySelectorAll('[data-rename]').forEach(button => {
        button.onclick = () => {
            const entry = incidents.find(i => i.id === button.dataset.rename);
            openSheet('Rename', `
                <input id="renameTo" value="${escapeHtml(entry.name)}">
                <button class="wide" id="renameSave">SAVE</button>`);
            document.getElementById('renameSave').onclick = () => {
                const to = document.getElementById('renameTo').value.trim();
                if (to) {
                    entry.name = to.slice(0, 60);
                    saveIncidents();
                    if (entry.id === activeIncidentId) {
                        incident = entry.name;
                        refreshTopBar();
                    }
                }
                showIncidents();
            };
        };
    });

    document.querySelectorAll('[data-drop]').forEach(button => {
        button.onclick = () => {
            const entry = incidents.find(i => i.id === button.dataset.drop);
            const held = Store.read('pins.' + entry.id, []).length +
                Store.read('tracks.' + entry.id, []).length;
            // Confirmed, unlike a pin: this throws away a whole incident's
            // work, and it cannot be tapped back.
            openSheet('Delete ' + entry.name + '?', `
                <p class="note bad">${held} pin${held === 1 ? '' : 's'} and track${
                    held === 1 ? '' : 's'} go with it. This cannot be undone.</p>
                <button class="wide danger" id="dropYes">DELETE IT</button>
                <button class="wide quiet" id="dropNo">KEEP IT</button>`);
            document.getElementById('dropNo').onclick = showIncidents;
            document.getElementById('dropYes').onclick = () => {
                Store.write('pins.' + entry.id, null);
                Store.write('tracks.' + entry.id, null);
                incidents = incidents.filter(i => i.id !== entry.id);
                if (entry.id === activeIncidentId) {
                    activeIncidentId = incidents[0].id;
                    saveIncidents();
                    switchIncident(incidents[0].id);
                    // switchIncident short-circuits when the id matches, so
                    // load it here regardless.
                    incident = activeIncident().name;
                    pins = Store.read('pins.' + activeIncidentId, []);
                    tracks = Store.read('tracks.' + activeIncidentId, []);
                    refreshTopBar();
                    draw();
                } else {
                    saveIncidents();
                }
                showIncidents();
            };
        };
    });

    document.getElementById('addIncident').onclick = () => {
        const name = document.getElementById('newIncident').value.trim();
        if (!name) { banner('Give it a name first.', 'warn'); return; }
        const entry = { id: 'i' + Date.now(), name: name.slice(0, 60), startedAt: Date.now() };
        incidents.push(entry);
        saveIncidents();
        switchIncident(entry.id);
    };
}

// ---------------------------------------------------------------- layers

/**
 * What is drawn under everything else.
 *
 * All of these are National Map cached services, which are public domain and
 * served for use. Nothing is prefetched beyond what has been on screen: the
 * service worker keeps what was actually looked at, which is what makes the
 * map work at the end of a road, and bulk-copying somebody's tile server is
 * both rude and against the terms it is served under.
 */
const BASEMAPS = [
    ['USGSTopo', 'Topographic', 'Contours, roads and names. The default sheet.'],
    ['USGSImageryTopo', 'Imagery with topo', 'Aerial photography with the topo drawn over it.'],
    ['USGSImageryOnly', 'Imagery', 'Aerial photography alone — fuel and canopy.'],
    ['USGSShadedReliefOnly', 'Shaded relief', 'Landform only. Clearest read of the ground.']
];

let basemap = Store.read('basemap', 'USGSTopo');
let hydroOn = Store.read('hydro', false);

document.getElementById('layersTool').onclick = showLayers;

function showLayers() {
    const rows = BASEMAPS.map(([id, label, why]) => `
        <button class="toggle" data-base="${id}">
          <span class="label">${label}<small>${why}</small></span>
          <span class="state${basemap === id ? ' on' : ''}">${
            basemap === id ? 'ON' : '—'}</span>
        </button>`).join('');

    openSheet('Layers', `
        <h4>Basemap</h4>
        ${rows}
        <h4>Overlay</h4>
        <button class="toggle" id="contourToggle">
          <span class="label">Contour lines<small>Traced from public-domain USGS
            elevation data, at the interval a paper quad would use for this zoom
            and this much relief.${contoursOn && contourState.intervalFeet
              ? ' Currently ' + contourState.intervalFeet + ' ft.' : ''}</small></span>
          <span class="state${contoursOn ? ' on' : ''}">${contoursOn ? 'ON' : 'OFF'}</span>
        </button>
        ${contoursOn ? `<div>${['FINE', 'NORMAL', 'COARSE'].map(name =>
            `<button class="chip" data-detail="${name}" style="margin:3px;background:${
              contourDetail === name ? '#1565C0' : '#25404F'}">${name}</button>`).join('')}
          </div>
          <p class="note">${escapeHtml(C ? C.attribution : '')}</p>` : ''}
        <button class="toggle" id="landToggle">
          <span class="label">Land status<small>Tap anywhere to ask who administers
            that ground. Needs a connection; there is no offline answer.</small></span>
          <span class="state${landOn ? ' on' : ''}">${landOn ? 'ON' : 'OFF'}</span>
        </button>
        <button class="toggle" id="hydroToggle">
          <span class="label">Water<small>Streams and bodies, drawn over the
            basemap. Useful on imagery, where drainages are hard to read.</small></span>
          <span class="state${hydroOn ? ' on' : ''}">${hydroOn ? 'ON' : 'OFF'}</span>
        </button>

        <h4>Held offline</h4>
        <p class="note">${heldTiles} tile${heldTiles === 1 ? '' : 's'} in memory this
          session. Everything already looked at stays available with no signal —
          pan over the ground you will be working before you lose service.</p>
        <button class="wide quiet" id="forgetTiles">FORGET CACHED TILES</button>

        ${qualityLegend()}
        <h4>Map symbols</h4>
        <p class="note">${SYMBOLS.map(s => s[2] + ' ' + s[1]).join(' · ')}</p>
    `);

    document.querySelectorAll('[data-base]').forEach(button => {
        button.onclick = () => {
            basemap = button.dataset.base;
            Store.write('basemap', basemap);
            forgetTiles();
            showLayers();
        };
    });

    document.getElementById('contourToggle').onclick = () => {
        contoursOn = !contoursOn;
        Store.write('contours', contoursOn);
        contourState.key = null;
        if (!contoursOn) contourState.lines = [];
        refreshContours();
        draw();
        showLayers();
    };

    document.querySelectorAll('[data-detail]').forEach(button => {
        button.onclick = () => {
            contourDetail = button.dataset.detail;
            Store.write('contourDetail', contourDetail);
            contourState.key = null;
            refreshContours();
            showLayers();
        };
    });

    document.getElementById('landToggle').onclick = () => {
        landOn = !landOn;
        Store.write('land', landOn);
        showLayers();
    };

    document.getElementById('hydroToggle').onclick = () => {
        hydroOn = !hydroOn;
        Store.write('hydro', hydroOn);
        draw();
        showLayers();
    };

    document.getElementById('forgetTiles').onclick = () => {
        forgetTiles();
        if (window.caches) caches.delete('fireline-tiles-v1').catch(() => {});
        banner('Cached tiles dropped.', 'warn');
        showLayers();
    };
}

function forgetTiles() {
    tileCache.clear();
    tileFailed.clear();
    heldTiles = 0;
    refreshStatus();
    draw();
}

// ----------------------------------------------------------- land status

/**
 * Who administers the ground under a tap.
 *
 * Three public services, asked at once and read by the phone's own parsers.
 * None of them needs a key or an agreement, which is the test every source in
 * this app has to pass -- anything behind an account would not be usable by
 * the people it is built for.
 *
 * No landowner is named, here or on the phone. The federal layer is
 * administrative and names units rather than people, and private ground reads
 * as "Private" and stops there. Naming an owner needs a commercial agreement
 * that explicitly authorises display, caching and export; until there is one
 * this does not ask for it and has nowhere to put it.
 */
let landOn = Store.read('land', false);

async function showLandStatus(latitude, longitude) {
    if (!L) return;
    openSheet('Land status', '<p class="note">Looking up…</p>');

    const get = async url => {
        try {
            const reply = await fetch(url);
            return reply.ok ? await reply.text() : null;
        } catch (e) {
            return null;
        }
    };

    const [owner, unit, county] = await Promise.all([
        get(L.ownerUrl(latitude, longitude)),
        get(L.protectedUnitUrl(latitude, longitude)),
        get(L.countyUrl(latitude, longitude))
    ]);

    if (owner === null && unit === null && county === null) {
        openSheet('Land status', `<p class="note bad">No answer. This needs a
            connection; there is no offline land record.</p>`);
        return;
    }

    const out = JSON.parse(L.statusOf(owner, unit, county));
    const row = (label, value) =>
        value ? `<div class="figure"><span>${label}</span><b>${escapeHtml(value)}</b></div>`
        : '';

    openSheet('Land status', `
        <p class="note">${K ? escapeHtml(K.formatDdm(latitude, longitude)) : ''}</p>
        ${out.empty ? `<p class="note warn">No public land record here, which
            usually means private ground rather than a failed lookup.</p>`
          : `<h4>${escapeHtml(out.headline || 'Ground')}</h4>
             ${row('Agency', out.agency)}
             ${row('Unit', out.unit)}
             ${row('Designation', out.designation)}
             ${row('Surface', out.surface)}`}
        ${row('County', out.county)}
        ${row('FIPS', out.fips)}
        <h4>What this is</h4>
        <p class="note">BLM Surface Management Agency, generalised for national
          mapping, with named units from the USGS Protected Areas Database and
          county from US Census TIGERweb. It says who administers the ground.
          It is <b>not</b> a land status record and no landowner is named.</p>
    `);
}

// --------------------------------------------------------------- settings

const STOP_CHOICES = [[60, '1 min'], [300, '5 min'], [900, '15 min'], [1800, '30 min']];

let stopSeconds = Store.read('stopSeconds', 300);

document.getElementById('settingsTool').onclick = showSettings;

function showSettings() {
    const author = Store.read('author', '');
    const qualification = Store.read('qualification', '');
    const chips = STOP_CHOICES.map(([value, label]) =>
        `<button class="chip" data-stop="${value}" style="margin:3px;background:${
            stopSeconds === value ? '#1565C0' : '#25404F'}">${label}</button>`).join('');

    openSheet('Settings', `
        <h4>Who is reporting</h4>
        <p class="note">Filled into the 206 and attached to anything sent, so the
          person receiving it knows whose track they are looking at.</p>
        <input id="setAuthor" value="${escapeHtml(author)}" placeholder="Name">
        <input id="setQual" value="${escapeHtml(qualification)}"
               placeholder="Qualification — EMT, Paramedic, REMS">

        <h4>End a track after</h4>
        <p class="note">Stationary this long and the track closes. Short splits one
          shift into fragments at every gate; long merges genuinely separate trips.</p>
        <div>${chips}</div>

        <h4>Hide the controls after</h4>
        <p class="note">Every panel takes a strip of screen, and the screen is how
          far ahead you can see. Touching anything brings them back, and an armed
          tool holds them open.</p>
        <div>${CHROME_CHOICES.map(([value, label]) =>
            `<button class="chip" data-chrome="${value}" style="margin:3px;background:${
                chromeSeconds === value ? '#1565C0' : '#25404F'}">${label}</button>`
        ).join('')}</div>

        <h4>Test position</h4>
        <button class="toggle" id="simToggle">
          <span class="label">Simulate a position<small>Tap the map to stand
            somewhere, for checking the map indoors. Drawn in orange everywhere it
            appears so it can never be read as a fix.</small></span>
          <span class="state${simMode ? ' on' : ''}">${simMode ? 'ON' : 'OFF'}</span>
        </button>

        <h4>Own terrain</h4>
        <p class="note">This browser draws on the USGS National Map and its own
          contours — there is no product sheet to import here. Change what is
          underneath in Layers.</p>

        <h4>Recording in a browser</h4>
        <p class="note">This is the one place the page is genuinely worse than the
          phone, and it is worth saying plainly: iOS suspends a web app the moment
          it is backgrounded or the screen locks. The screen is held awake while
          recording, but if the page is suspended anyway the unobserved stretch is
          drawn as a dotted gap rather than a line across ground nobody walked.</p>

        <h4>Install it</h4>
        <p class="note">Add to Home Screen from the browser's share menu. It then
          opens full screen and keeps working with no signal.</p>

        <button class="wide" id="saveSettings">SAVE</button>
    `);

    // Anything typed is kept before the sheet redraws. Tapping a threshold
    // used to rebuild the form and quietly empty the name box above it, so a
    // reporter who filled it in first lost it by touching anything else.
    const captureSettings = () => {
        const name = document.getElementById('setAuthor');
        const qualification = document.getElementById('setQual');
        if (name) Store.write('author', name.value.trim());
        if (qualification) Store.write('qualification', qualification.value.trim());
    };

    document.querySelectorAll('[data-stop]').forEach(button => {
        button.onclick = () => {
            captureSettings();
            stopSeconds = +button.dataset.stop;
            Store.write('stopSeconds', stopSeconds);
            showSettings();
        };
    });

    document.querySelectorAll('[data-chrome]').forEach(button => {
        button.onclick = () => {
            captureSettings();
            chromeSeconds = +button.dataset.chrome;
            Store.write('chromeSeconds', chromeSeconds);
            touched();
            showSettings();
        };
    });

    document.getElementById('simToggle').onclick = () => {
        captureSettings();
        simMode = !simMode;
        if (!simMode) {
            // Back to the receiver, and the orange dot goes with it.
            simulated = null;
            showCoordinates();
            draw();
        }
        refreshSimBanner();
        showSettings();
    };

    document.getElementById('saveSettings').onclick = () => {
        captureSettings();
        closeSheet();
        banner('Saved.', 'good');
    };
}

// ----------------------------------------------------------------- search

/**
 * Going to a coordinate somebody read out.
 *
 * The parsing is the phone's, so a grid that works on one works on the other.
 * The keypad carries every mark a position can arrive with, because a phone
 * keyboard buries the degree sign three taps deep and the alternative is
 * somebody typing a position wrong while a radio waits.
 */
document.getElementById('searchTool').onclick = showSearch;

/*
 * Every mark a position can arrive with, on one screen.
 *
 * A phone keyboard buries the degree sign three taps deep, and the
 * alternative is somebody typing a position wrong while a radio waits.
 *
 * X is the important one. It stands for a digit that was not caught, and the
 * result then covers every value that digit could have been -- so a position
 * half heard becomes an area to search rather than a guess presented as a fix.
 */
const KEYS = [
    '1', '2', '3', '°', 'N', 'S',
    '4', '5', '6', '′', 'E', 'W',
    '7', '8', '9', '″', '−', '+',
    '.', '0', ',', ' ', 'X', '⌫'
];

function showSearch() {
    openSheet('Go to a coordinate', `
        <p class="note">Degrees and minutes, decimal degrees, UTM or MGRS. Colons,
          slashes, semicolons and brackets are all read as separators, so paste it
          however it arrived.</p>
        <input id="searchText" placeholder="N 45 12.345 W 117 38.220" autocomplete="off">
        <p class="note" id="searchState">—</p>
        <div id="keys"></div>
        <p class="note">Use <b>X</b> for a digit you did not catch — "45 12.3XX"
          searches every position it could have been, drawn as a box rather than
          a point somebody would take for a fix.</p>
        <button class="wide" id="searchGo">GO THERE</button>
    `);

    const field = document.getElementById('searchText');
    const state = document.getElementById('searchState');

    const check = () => {
        const parsed = K && K.parseCoordinate(field.value);
        if (!parsed) {
            state.textContent = field.value.trim()
                ? 'Not a position yet — keep typing.' : '—';
            state.className = 'note';
            return null;
        }
        const across = K.distanceMeters(
            parsed.south, parsed.west, parsed.south, parsed.east);
        const down = K.distanceMeters(
            parsed.south, parsed.west, parsed.north, parsed.west);
        state.textContent = parsed.format + ' · ' +
            K.formatDdm(parsed.latitude, parsed.longitude) +
            (parsed.exact ? ''
              : ' · anywhere in ' + Math.round(across) + ' × ' + Math.round(down) +
                ' m — digits missing');
        state.className = parsed.exact ? 'note good' : 'note warn';
        return parsed;
    };

    const go = () => {
        const parsed = check();
        if (!parsed) { banner('That is not a position yet.', 'warn'); return; }
        view.following = false;
        document.getElementById('follow').classList.remove('on');
        view.latitude = parsed.latitude;
        view.longitude = parsed.longitude;
        view.zoom = parsed.exact ? 15 : 12;
        searchRegion = {
            north: parsed.north, south: parsed.south,
            west: parsed.west, east: parsed.east,
            exact: parsed.exact
        };
        closeSheet();
        draw();
        banner('Centred on ' + K.formatDdm(parsed.latitude, parsed.longitude), 'good');
    };

    field.oninput = check;
    field.onkeydown = event => { if (event.key === 'Enter') go(); };

    document.getElementById('searchGo').onclick = go;

    const holder = document.getElementById('keys');
    KEYS.forEach(key => {
        const button = document.createElement('button');
        button.textContent = key === ' ' ? 'SPC' : key;
        if (key === '⌫') button.className = 'dim';
        button.onclick = () => {
            if (key === '⌫') field.value = field.value.slice(0, -1);
            else field.value += key;
            check();
        };
        holder.appendChild(button);
    });
}

// ----------------------------------------------------------------- import

document.getElementById('importTool').onclick = () => {
    openSheet('Import', `
        <h4>A map somebody sent</h4>
        <p class="note">Fireline parts paste into Share → Receive. That carries every
          pin and every track, and is the way to get somebody else's map onto this
          one.</p>
        <button class="wide" id="toShare">OPEN SHARE</button>

        <h4>Product sheets</h4>
        <p class="note">Importing a georeferenced PDF is the phone's job — it needs
          to read the geospatial dictionary out of the file, which this page has no
          way to do. The browser draws on the USGS National Map instead, which
          covers the same ground and needs nothing imported.</p>
    `);
    document.getElementById('toShare').onclick = showShare;
};

// ----------------------------------------------------------- travel panel

/**
 * The live recording readout, worded by the shared code.
 *
 * Ticks on a timer rather than only on a fix, because the elapsed clock has to
 * keep moving while the receiver is quiet -- a frozen clock reads as a frozen
 * app, and the next thing somebody does is stop the recording to check.
 */
function showTravel() {
    const holder = document.getElementById('travel');
    if (!live.recorder) { holder.classList.add('hidden'); return; }
    holder.classList.remove('hidden');

    const figures = JSON.parse(live.recorder.stats(Date.now()));
    const accent = figures.accent === 'RECORDING' ? 'rec'
        : figures.accent === 'PAUSED' ? 'pause' : 'idle';
    const stat = (label, value) =>
        `<div class="stat"><span>${label}</span><b>${value}</b></div>`;

    holder.innerHTML = `
        <div class="state ${accent}">${escapeHtml(figures.state)}</div>
        ${figures.recording ? `
          <div class="stats">
            ${stat('ELAPSED', figures.elapsed)}
            ${stat('DISTANCE', figures.distance)}
            ${stat('POINTS', figures.points)}
          </div>
          <div class="stats">
            ${stat('MOVING', figures.moving)}
            ${stat('AVG', figures.averageSpeed)}
            ${stat('MOVING AVG', figures.movingSpeed)}
          </div>
          <div class="stats">
            ${stat('CHAINS', figures.chains)}
            ${figures.paused ? stat('STOPPED', figures.paused) : ''}
          </div>`
        : `${figures.waiting ? `<div class="said">${escapeHtml(figures.waiting)}</div>` : ''}
           ${figures.diagnostics ? `<div class="diag">${escapeHtml(figures.diagnostics)}</div>` : ''}`}
    `;
}

setInterval(() => { if (live.recorder) showTravel(); }, 1000);

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
window.addEventListener('orientationchange', () => setTimeout(resize, 120));

/*
 * The map has to give ground when a panel opens above it.
 *
 * Measuring, the travel readout and the symbol palette all appear in the
 * column, and each one makes the map shorter. Resizing only on a window resize
 * left the canvas at its old height, overflowing the column and sitting on top
 * of the tool row -- so the first tap after arming a tool went to the map
 * instead of the button under the finger.
 */
if (window.ResizeObserver) {
    new ResizeObserver(() => resize()).observe(document.getElementById('mapHolder'));
}
refreshTopBar();
refreshStatus();
refreshSimBanner();
showPalette();
refreshContours();
touched();
resize();
// The safe-area insets land a frame late on iOS, so the map is measured again
// once the column has actually settled. Skipping this leaves the canvas a few
// pixels tall on the first paint.
requestAnimationFrame(resize);
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
