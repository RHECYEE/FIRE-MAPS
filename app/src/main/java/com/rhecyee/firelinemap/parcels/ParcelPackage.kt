package com.rhecyee.firelinemap.parcels

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** One parcel: a boundary plus the attributes worth carrying. */
data class Parcel(
    val parcelId: String,
    val apn: String?,
    val siteAddress: String?,
    val acres: Double?,
    val landUse: String?,
    val ownerName: String?,
    val geometry: ParcelGeometry
) {
    /** What to draw on the boundary at close zoom. */
    val shortLabel: String? get() = apn ?: parcelId.takeIf { it.isNotBlank() }
}

/**
 * Reads a county parcel GeoPackage.
 *
 * A GeoPackage is an ordinary SQLite database with a documented schema, so it
 * opens with what Android already has. That is what makes manual import worth
 * doing first: a county file bought from a provider can be copied onto a
 * device and proven end to end before any download service exists.
 *
 * Owner names are read only when asked for. They carry a privacy and
 * licensing weight that boundaries and parcel numbers do not, so nothing
 * requests them by default.
 */
class ParcelPackage(private val file: File) : AutoCloseable {

    private var database: SQLiteDatabase? = null
    private var featureTable: String? = null
    private var geometryColumn: String? = null
    private var columns: Set<String> = emptySet()

    val isOpen: Boolean get() = database != null

    fun open(): Boolean {
        if (!file.exists()) return false
        database = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return false

        val db = database ?: return false
        // The features table is named in the GeoPackage's own contents table.
        featureTable = runCatching {
            db.rawQuery(
                "SELECT table_name FROM gpkg_contents WHERE data_type = 'features' LIMIT 1",
                null
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

        val table = featureTable ?: return false
        geometryColumn = runCatching {
            db.rawQuery(
                "SELECT column_name FROM gpkg_geometry_columns WHERE table_name = ? LIMIT 1",
                arrayOf(table)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "geom"

        columns = runCatching {
            db.rawQuery("SELECT * FROM \"$table\" LIMIT 0", null).use {
                it.columnNames.map { name -> name.lowercase() }.toSet()
            }
        }.getOrDefault(emptySet())

        return geometryColumn != null
    }

    /**
     * Parcels overlapping a view.
     *
     * Bounded by [limit] because a county holds hundreds of thousands of them
     * and a wide view would otherwise try to draw the lot.
     */
    fun parcelsIn(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        includeOwner: Boolean = false,
        limit: Int = 1500
    ): List<Parcel> {
        val db = database ?: return emptyList()
        val table = featureTable ?: return emptyList()
        val geometry = geometryColumn ?: return emptyList()

        val selected = buildList {
            add("\"$geometry\"")
            add(pick("parcel_id", "ll_uuid", "id", "fid") ?: "rowid")
            add(pick("apn", "parcelnumb", "parcel_number", "pin"))
            add(pick("site_address", "address", "saddress", "situs_address"))
            add(pick("acres", "gisacre", "ll_gisacre", "deeded_acres"))
            add(pick("land_use", "usedesc", "zoning", "landuse"))
            add(if (includeOwner) pick("owner_name", "owner", "ownername") else null)
        }

        val projection = selected.mapIndexed { index, column ->
            "${column ?: "NULL"} AS c$index"
        }.joinToString(", ")

        // The spatial index is the fast path; without it the whole table is
        // scanned and the envelope check does the work.
        val ids = spatialIndexIds(db, table, geometry, south, west, north, east, limit)
        val sql = if (ids != null) {
            "SELECT $projection FROM \"$table\" WHERE rowid IN (${ids.joinToString(",")})"
        } else {
            "SELECT $projection FROM \"$table\" LIMIT ${limit * 20}"
        }

        return runCatching {
            db.rawQuery(sql, null).use { cursor ->
                val results = mutableListOf<Parcel>()
                while (cursor.moveToNext() && results.size < limit) {
                    val blob = runCatching { cursor.getBlob(0) }.getOrNull() ?: continue
                    val shape = WkbGeometry.fromGeoPackageBlob(blob) ?: continue
                    if (!shape.intersects(south, west, north, east)) continue
                    results += Parcel(
                        parcelId = cursor.stringOrNull(1) ?: "",
                        apn = cursor.stringOrNull(2),
                        siteAddress = cursor.stringOrNull(3),
                        acres = cursor.doubleOrNull(4),
                        landUse = cursor.stringOrNull(5),
                        ownerName = if (includeOwner) cursor.stringOrNull(6) else null,
                        geometry = shape
                    )
                }
                results
            }
        }.getOrDefault(emptyList())
    }

    /** The parcel a position falls on, if any. */
    fun parcelAt(latitude: Double, longitude: Double, includeOwner: Boolean = false): Parcel? {
        val margin = 0.002
        return parcelsIn(
            latitude - margin, longitude - margin,
            latitude + margin, longitude + margin,
            includeOwner = includeOwner,
            limit = 200
        ).firstOrNull { it.geometry.contains(latitude, longitude) }
    }

    private fun spatialIndexIds(
        db: SQLiteDatabase,
        table: String,
        geometry: String,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        limit: Int
    ): List<Long>? = runCatching {
        db.rawQuery(
            "SELECT id FROM \"rtree_${table}_$geometry\" " +
                "WHERE maxx >= ? AND minx <= ? AND maxy >= ? AND miny <= ? LIMIT ?",
            arrayOf(
                west.toString(), east.toString(),
                south.toString(), north.toString(),
                (limit * 4).toString()
            )
        ).use { cursor ->
            val ids = mutableListOf<Long>()
            while (cursor.moveToNext()) ids += cursor.getLong(0)
            ids.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    /** The first of several possible column names that this file actually has. */
    private fun pick(vararg candidates: String): String? =
        candidates.firstOrNull { it in columns }?.let { "\"$it\"" }

    override fun close() {
        runCatching { database?.close() }
        database = null
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        runCatching { if (isNull(index)) null else getString(index)?.takeIf { it.isNotBlank() } }
            .getOrNull()

    private fun android.database.Cursor.doubleOrNull(index: Int): Double? =
        runCatching { if (isNull(index)) null else getDouble(index) }.getOrNull()
}
