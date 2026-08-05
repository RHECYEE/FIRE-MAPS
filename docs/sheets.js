/*
 * Product sheets in the browser.
 *
 * An incident's operations map is a georeferenced PDF, and without this the
 * page could show where somebody was but not on the map their briefing came
 * from -- which is most of the job.
 *
 * Three parts, and only the middle one is the browser's own work:
 *
 *   1. Get the bytes. A file off the phone, or a URL off the incident's server.
 *   2. Inflate the PDF's compressed object streams, and render page one.
 *   3. Read the geospatial viewport out of the result.
 *
 * Step three is the phone's own Kotlin, unchanged. A sheet that lines up on one
 * app and is a few hundred metres out on the other is worse than no sheet.
 */

'use strict';

const S = (() => {
    const module = (typeof web !== 'undefined' && web) || window.web || {};
    const found = module.com && module.com.rhecyee &&
        module.com.rhecyee.firelinemap.web;
    return found ? found.FirelineSheets : null;
})();

// ------------------------------------------------------------------ store

/**
 * Sheets live in IndexedDB, not localStorage.
 *
 * A product sheet is megabytes. localStorage is a few and is synchronous, so
 * putting one there would both fail and block the map while failing.
 */
const SheetStore = {
    db: null,

    open() {
        if (this.db) return Promise.resolve(this.db);
        return new Promise((resolve, reject) => {
            const request = indexedDB.open('fireline-sheets', 1);
            request.onupgradeneeded = () => {
                if (!request.result.objectStoreNames.contains('sheets')) {
                    request.result.createObjectStore('sheets', { keyPath: 'id' });
                }
            };
            request.onsuccess = () => { this.db = request.result; resolve(this.db); };
            request.onerror = () => reject(request.error);
        });
    },

    async put(record) {
        const db = await this.open();
        return new Promise((resolve, reject) => {
            const tx = db.transaction('sheets', 'readwrite');
            tx.objectStore('sheets').put(record);
            tx.oncomplete = () => resolve(record);
            tx.onerror = () => reject(tx.error);
        });
    },

    async all(incidentId) {
        const db = await this.open();
        return new Promise((resolve, reject) => {
            const out = [];
            const cursor = db.transaction('sheets').objectStore('sheets').openCursor();
            cursor.onsuccess = event => {
                const at = event.target.result;
                if (!at) { resolve(out); return; }
                // Sheets belong to the incident they were imported on, the same
                // as pins and tracks. Another fire's operations map drawn under
                // this one's pins is worse than no map.
                if (!incidentId || at.value.incidentId === incidentId) out.push(at.value);
                at.continue();
            };
            cursor.onerror = () => reject(cursor.error);
        });
    },

    async get(id) {
        const db = await this.open();
        return new Promise((resolve, reject) => {
            const request = db.transaction('sheets').objectStore('sheets').get(id);
            request.onsuccess = () => resolve(request.result || null);
            request.onerror = () => reject(request.error);
        });
    },

    async remove(id) {
        const db = await this.open();
        return new Promise((resolve, reject) => {
            const tx = db.transaction('sheets', 'readwrite');
            tx.objectStore('sheets').delete(id);
            tx.oncomplete = resolve;
            tx.onerror = () => reject(tx.error);
        });
    }
};

// ---------------------------------------------------------------- reading

/** The file's bytes as Latin-1, which maps bytes to characters one to one. */
function latin1Of(bytes) {
    // Chunked: spreading a multi-megabyte array into one call blows the stack.
    let out = '';
    const step = 32768;
    for (let at = 0; at < bytes.length; at += step) {
        out += String.fromCharCode.apply(null, bytes.subarray(at, at + step));
    }
    return out;
}

/**
 * Inflates one of the PDF's compressed object streams.
 *
 * The browser's own decompression, which is the part of reading a GeoPDF that
 * genuinely needs a platform. Unlike the phone's inflater this one refuses
 * trailing bytes, so the stream is cut at its `endstream` keyword first.
 */
async function inflateStream(bytes, latin1, start) {
    const marker = latin1.indexOf('endstream', start);
    let end = marker < 0 ? bytes.length : marker;

    // Back off the end-of-line PDF writers put between the data and the
    // `endstream` keyword. The phone's inflater stops when the compressed
    // stream ends and ignores whatever follows; DecompressionStream refuses
    // trailing bytes outright, so those two characters were enough to make
    // every object stream in the file fail to inflate.
    while (end > start) {
        const last = bytes[end - 1];
        if (last === 0x0a || last === 0x0d || last === 0x20) end--;
        else break;
    }

    const slice = bytes.subarray(start, end);
    if (!slice.length || typeof DecompressionStream === 'undefined') return null;

    // FlateDecode is zlib-wrapped, but not every producer writes the wrapper,
    // so raw deflate is tried second rather than the stream being given up on.
    for (const format of ['deflate', 'deflate-raw']) {
        try {
            const stream = new Blob([slice]).stream()
                .pipeThrough(new DecompressionStream(format));
            const out = new Uint8Array(await new Response(stream).arrayBuffer());
            if (out.length) return out;
        } catch (e) {
            // Try the other framing before giving up on this stream.
        }
    }
    return null;
}

/** The searchable text the parser expects: the raw file plus inflated streams. */
async function searchableText(bytes) {
    const latin1 = latin1Of(bytes);
    if (!S) return latin1;
    let text = latin1;
    for (const start of S.objectStreamOffsets(latin1)) {
        const inflated = await inflateStream(bytes, latin1, start);
        if (inflated) text += '\n' + latin1Of(inflated);
    }
    return text;
}

// --------------------------------------------------------------- rendering

let pdfLib = null;

/**
 * Loads the PDF renderer, once, on first use.
 *
 * Not in the app shell on purpose: it is over a megabyte, and an app that has
 * to pull that down before it will show you where you are has got its
 * priorities backwards. It is fetched the first time a sheet is imported and
 * cached from then on, so the second import works with no signal.
 */
async function pdfEngine() {
    if (pdfLib) return pdfLib;
    pdfLib = await import('./pdfjs/pdf.min.mjs');
    pdfLib.GlobalWorkerOptions.workerSrc = './pdfjs/pdf.worker.min.mjs';
    return pdfLib;
}

/**
 * Renders page one to a canvas.
 *
 * Sized so the sheet can be zoomed into without going soft -- a division
 * boundary and a drop point number are the whole reason for having the sheet,
 * and both are small print. Capped so a large-format plot does not exhaust
 * memory on a phone.
 */
async function renderSheet(bytes) {
    const pdf = await pdfEngine();
    // pdf.js takes ownership of the buffer, so it gets its own copy: the same
    // bytes are still needed for the georeferencing and for storage.
    const file = await pdf.getDocument({ data: bytes.slice() }).promise;
    const page = await file.getPage(1);

    const base = page.getViewport({ scale: 1 });
    const longest = Math.max(base.width, base.height);
    const scale = Math.min(4, Math.max(1, 4000 / longest));
    const viewport = page.getViewport({ scale });

    const target = document.createElement('canvas');
    target.width = Math.round(viewport.width);
    target.height = Math.round(viewport.height);
    await page.render({
        canvasContext: target.getContext('2d'),
        viewport
    }).promise;

    return {
        canvas: target,
        pageWidth: base.width,
        pageHeight: base.height
    };
}

// ---------------------------------------------------------------- importing

/**
 * Reads a sheet from bytes: georeferencing, then a picture of it.
 *
 * Returns everything the map needs and everything the operator needs to be
 * told, including the case that matters most -- a PDF with no geospatial
 * viewport at all, which is readable but cannot carry a position.
 */
async function readSheet(bytes, name) {
    if (!S) throw new Error('The sheet reader did not load.');
    const text = await searchableText(bytes);
    const summary = JSON.parse(S.read(text));
    const rendered = await renderSheet(bytes);

    return {
        name,
        summary,
        frame: summary.georeferenced ? S.open(text) : null,
        canvas: rendered.canvas,
        pageWidth: rendered.pageWidth,
        pageHeight: rendered.pageHeight,
        bytes
    };
}

/**
 * Fetches a sheet from a URL.
 *
 * This is how an incident's own products get onto a phone that was handed to
 * somebody at briefing: a folder on the incident's server, and a link.
 *
 * The honest limitation, said out loud rather than discovered: a browser can
 * only fetch from a server that allows it. A plain `ftp://` address it cannot
 * open at all -- no browser has spoken FTP for years -- and an `https://`
 * server that does not send CORS headers will refuse the read even though the
 * file is there and the phone can see it. Neither is something this app can
 * fix from its side, so both are reported as what they are.
 */
async function fetchSheet(url) {
    const address = url.trim();
    if (/^ftps?:\/\//i.test(address)) {
        throw new Error(
            'Browsers stopped speaking FTP, so the page cannot open an ftp:// ' +
            'address directly. If the same folder is served over https, use that. ' +
            'Otherwise download the file and use IMPORT A FILE — that always works.'
        );
    }
    let reply;
    try {
        reply = await fetch(address);
    } catch (e) {
        throw new Error(
            'Could not read that address. Either there is no connection, or the ' +
            'server does not allow other sites to read from it. Downloading the ' +
            'file and importing it from disk always works.'
        );
    }
    if (!reply.ok) throw new Error('The server answered ' + reply.status + '.');
    return new Uint8Array(await reply.arrayBuffer());
}

/**
 * Lists the PDFs in a directory the server publishes as a listing.
 *
 * Plain autoindex HTML, which is what a file server hands back for a folder.
 * Anything that is not a link to a PDF is ignored.
 */
async function listSheets(rootUrl) {
    let reply;
    try {
        reply = await fetch(rootUrl);
    } catch (e) {
        throw new Error(
            'Could not reach that folder. Either there is no connection, or the ' +
            'server does not allow other sites to read from it.'
        );
    }
    // A 404 page parses perfectly well and contains no PDFs, which would read
    // as "the folder is empty" rather than "there is no such folder".
    if (!reply.ok) throw new Error('The server answered ' + reply.status + '.');
    const body = await reply.text();
    const found = [];
    const seen = new Set();
    const pattern = /href\s*=\s*["']([^"']+\.pdf)["']/gi;
    let match;
    while ((match = pattern.exec(body)) !== null) {
        const href = match[1];
        if (seen.has(href)) continue;
        seen.add(href);
        found.push({
            name: decodeURIComponent(href.split('/').pop()),
            url: new URL(href, rootUrl).href
        });
    }
    return found;
}
