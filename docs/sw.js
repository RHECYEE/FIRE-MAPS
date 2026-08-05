/*
 * What makes this work with no signal.
 *
 * Two caches, kept apart on purpose.
 *
 * The shell -- the page, its script and the compiled Kotlin -- is fetched once
 * and served from the cache from then on, so the app opens instantly and opens
 * at all with the phone in airplane mode.
 *
 * Tiles are kept as they are looked at. Nothing is prefetched: the USGS serves
 * this map for use, not for bulk copying, and an app that quietly pulls a
 * county in the background is abusing somebody else's bandwidth. Ground that
 * has been on screen is ground you can still see when the signal goes, which
 * is the honest version of offline and covers the real case -- you looked at
 * the division this morning in town.
 */

const SHELL = 'fireline-shell-v1';
const TILES = 'fireline-tiles-v1';

const SHELL_FILES = [
    './',
    './index.html',
    './app.css',
    './app.js',
    './fireline.js',
    './manifest.webmanifest',
    './icon-192.png',
    './icon-512.png',
    './icon-180.png'
];

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(SHELL)
            // Individually, so one missing file does not fail the whole
            // install and leave the app with no offline support at all.
            .then(cache => Promise.allSettled(
                SHELL_FILES.map(file => cache.add(file))
            ))
            .then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys()
            .then(names => Promise.all(
                names
                    .filter(name => name !== SHELL && name !== TILES)
                    .map(name => caches.delete(name))
            ))
            .then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', event => {
    const request = event.request;
    if (request.method !== 'GET') return;

    const url = new URL(request.url);

    if (url.hostname === 'basemap.nationalmap.gov') {
        event.respondWith(tile(request));
        return;
    }

    if (url.origin === self.location.origin) {
        event.respondWith(shell(request));
    }
});

/**
 * Cache first for tiles.
 *
 * A tile of a mountain does not change between shifts, and going to the
 * network first would make every pan slow and every offline pan fail.
 */
async function tile(request) {
    const cache = await caches.open(TILES);
    const hit = await cache.match(request);
    if (hit) return hit;
    try {
        const response = await fetch(request);
        if (response && response.status === 200) {
            cache.put(request, response.clone());
        }
        return response;
    } catch (e) {
        // No signal and never seen. The map draws the ground it does have.
        return new Response('', { status: 504 });
    }
}

/**
 * Network first for the app itself, falling back to the cache.
 *
 * The other way round would mean a fix shipped today reaching a phone weeks
 * later, and the failure that matters here is the opposite one: the app has to
 * open with no signal, which the fallback covers.
 */
async function shell(request) {
    try {
        const response = await fetch(request);
        if (response && response.status === 200) {
            const cache = await caches.open(SHELL);
            cache.put(request, response.clone());
        }
        return response;
    } catch (e) {
        const hit = await caches.match(request);
        if (hit) return hit;
        // A navigation with nothing cached for that exact path still gets the
        // app rather than a browser error page.
        if (request.mode === 'navigate') {
            const index = await caches.match('./index.html');
            if (index) return index;
        }
        return new Response('Offline', { status: 503 });
    }
}
