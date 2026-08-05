package com.rhecyee.firelinemap.terrain

/**
 * Where the elevation tiles come from, for whichever app is asking.
 *
 * Split out from the Android downloader so the browser can draw the same
 * contours from the same data. What is here is the source's terms and its
 * shape -- the endpoint, the credit it asks for, how big a tile is, and where
 * it stops -- none of which either app should be deciding for itself.
 *
 * Terrarium tiles, which are public domain and served without a key. That is
 * the whole reason this layer can exist: anything behind an account would not
 * be usable by the people this is built for, at the end of a road, on a phone
 * nobody signed into.
 */
object DemSource {

    const val ENDPOINT = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"

    /**
     * The wording the USGS asks for, carried verbatim.
     *
     * Public domain data still gets credited. It costs a line on the key.
     */
    const val ATTRIBUTION =
        "United States 3DEP (formerly NED) and global GMTED2010 and SRTM " +
            "terrain data courtesy of the U.S. Geological Survey."

    const val TILE_SIZE = 256

    /** The source stops here; asking beyond it returns nothing forever. */
    const val MAX_ZOOM = 15

    fun url(zoom: Int, x: Int, y: Int): String = "$ENDPOINT/$zoom/$x/$y.png"
}
