package com.filecamera.apk;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class RoadDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "RoadDatabaseHelper";

    // --- Database Constants ---
    private static final String DATABASE_NAME = "roads.db";
    private static final int DATABASE_VERSION = 2; // 版本号增加，以便 onUpgrade 被调用
    private static final String TABLE_ROAD_INFO = "road_info";
    private static final String TABLE_ROAD_COORD = "road_coord";

    // road_info columns
    private static final String COL_ROAD_PART_ID = "road_part_id";
    private static final String COL_ROAD_ID = "road_id";
    private static final String COL_ROAD_NAME = "road_name";
    private static final String COL_DIRECTION = "direction"; // 新增字段：方向
    private static final String COL_START_PILE = "start_pile";
    private static final String COL_END_PILE = "end_pile";
    private static final String COL_ROAD_COUNTRY_ID = "road_country_id";
    private static final String COL_ROAD_DESC = "road_desc";
    private static final String COL_MAX_LNG = "max_lng";
    private static final String COL_MAX_LAT = "max_lat";
    private static final String COL_MIN_LNG = "min_lng";
    private static final String COL_MIN_LAT = "min_lat";
    private static final String COL_TOTAL_LENGTH = "total_length"; // 预计算路段总长

    // road_coord columns (re-designed for segments)
    private static final String COL_ROAD_INDEX = "road_index";
    private static final String COL_START_LNG = "start_lng";
    private static final String COL_START_LAT = "start_lat";
    private static final String COL_END_LNG = "end_lng";
    private static final String COL_END_LAT = "end_lat";
    private static final String COL_SEGMENT_LEN = "segment_len";
    private static final String COL_DIST_FROM_START = "dist_from_start";
    private static final String COL_LEN_SQ_APPROX = "len_sq_approx";

    // --- Matching Constants ---
    private static final double EARTH_RADIUS_METERS = 6371000.0;
    private static final double MATCH_DISTANCE_THRESHOLD_METERS = 100.0;

    public RoadDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createRoadInfoTableSQL = "CREATE TABLE " + TABLE_ROAD_INFO + " (" +
                COL_ROAD_PART_ID + " TEXT PRIMARY KEY," +
                COL_ROAD_ID + " TEXT," +
                COL_ROAD_NAME + " TEXT," +
                COL_DIRECTION + " TEXT," + // 添加 direction 字段
                COL_START_PILE + " REAL," +
                COL_END_PILE + " REAL," +
                COL_ROAD_COUNTRY_ID + " TEXT," +
                COL_ROAD_DESC + " TEXT," +
                COL_MAX_LNG + " REAL," +
                COL_MAX_LAT + " REAL," +
                COL_MIN_LNG + " REAL," +
                COL_MIN_LAT + " REAL," +
                COL_TOTAL_LENGTH + " REAL)";
        db.execSQL(createRoadInfoTableSQL);
        db.execSQL("CREATE INDEX idx_road_info_bbox ON " + TABLE_ROAD_INFO +
                " (" + COL_MIN_LNG + ", " + COL_MAX_LNG + ", " + COL_MIN_LAT + ", " + COL_MAX_LAT + ")");

        String createRoadCoordTableSQL = "CREATE TABLE " + TABLE_ROAD_COORD + " (" +
                COL_ROAD_PART_ID + " TEXT NOT NULL," +
                COL_ROAD_INDEX + " INTEGER NOT NULL," +
                COL_START_LNG + " REAL NOT NULL," +
                COL_START_LAT + " REAL NOT NULL," +
                COL_END_LNG + " REAL NOT NULL," +
                COL_END_LAT + " REAL NOT NULL," +
                COL_SEGMENT_LEN + " REAL NOT NULL," +
                COL_DIST_FROM_START + " REAL NOT NULL," +
                COL_LEN_SQ_APPROX + " REAL NOT NULL," +
                "PRIMARY KEY (" + COL_ROAD_PART_ID + ", " + COL_ROAD_INDEX + "))";
        db.execSQL(createRoadCoordTableSQL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 为简化起见，这里仍然使用删除和重建的策略
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_ROAD_INFO + " ADD COLUMN " + COL_DIRECTION + " TEXT");
            } catch (Exception e) {
                // 如果升级失败，则回退到删除重建
                Log.e(TAG, "Failed to upgrade table, fallback to drop and recreate.", e);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_ROAD_INFO);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_ROAD_COORD);
                onCreate(db);
            }
        } else {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_ROAD_INFO);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_ROAD_COORD);
            onCreate(db);
        }
    }

    public JSONArray getAllRoadInfo() throws JSONException {
        SQLiteDatabase db = this.getReadableDatabase();
        JSONArray roadList = new JSONArray();

        String query = "SELECT * FROM " + TABLE_ROAD_INFO
                + " ORDER BY " + COL_ROAD_ID + " ASC, "
                + COL_START_PILE + " ASC";

        try (Cursor cursor = db.rawQuery(query, null)) {
            if (cursor == null) {
                return roadList;
            }

            String[] columnNames = cursor.getColumnNames();

            while (cursor.moveToNext()) {
                JSONObject roadInfo = new JSONObject();
                for (String columnName : columnNames) {
                    int columnIndex = cursor.getColumnIndex(columnName);
                    if (columnIndex == -1) continue;

                    if (cursor.isNull(columnIndex)) {
                        if (columnName.equals(COL_DIRECTION) || columnName.equals(COL_ROAD_DESC)) {
                            roadInfo.put(columnName, "暂无");
                        } else {
                            roadInfo.put(columnName, JSONObject.NULL);
                        }
                        continue;
                    }

                    int type = cursor.getType(columnIndex);
                    switch (type) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            roadInfo.put(columnName, cursor.getLong(columnIndex));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            roadInfo.put(columnName, cursor.getDouble(columnIndex));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            roadInfo.put(columnName, cursor.getString(columnIndex));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                        case Cursor.FIELD_TYPE_NULL:
                        default:
                            roadInfo.put(columnName, JSONObject.NULL);
                            break;
                    }
                }
                roadList.put(roadInfo);
            }
        }
        return roadList;
    }

    public boolean deleteRoadById(String roadPartId) {
        if (roadPartId == null || roadPartId.isEmpty()) {
            return false;
        }
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            String whereClause = COL_ROAD_PART_ID + " = ?";
            String[] whereArgs = {roadPartId};

            db.delete(TABLE_ROAD_COORD, whereClause, whereArgs);
            int deletedRows = db.delete(TABLE_ROAD_INFO, whereClause, whereArgs);

            db.setTransactionSuccessful();
            return deletedRows > 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete road with id: " + roadPartId, e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public boolean replaceAllRoads(String geoJsonString) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_ROAD_INFO, null, null);
            db.delete(TABLE_ROAD_COORD, null, null);
            JSONObject geoJson = new JSONObject(geoJsonString);
            JSONArray features = geoJson.getJSONArray("features");
            for (int i = 0; i < features.length(); i++) {
                insertSingleFeature(db, features.getJSONObject(i));
            }
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to process GeoJSON for replacement.", e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public boolean updateOrInsertRoad(JSONObject feature) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.beginTransaction();
        try {
            JSONObject properties = feature.getJSONObject("properties");
            String roadPartId = properties.getString(COL_ROAD_PART_ID);
            String whereClause = COL_ROAD_PART_ID + " = ?";
            String[] whereArgs = {roadPartId};
            db.delete(TABLE_ROAD_INFO, whereClause, whereArgs);
            db.delete(TABLE_ROAD_COORD, whereClause, whereArgs);
            insertSingleFeature(db, feature);
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to update or insert road.", e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    private void insertSingleFeature(SQLiteDatabase db, JSONObject feature) throws JSONException {
        JSONObject properties = feature.getJSONObject("properties");
        String roadPartId = properties.getString(COL_ROAD_PART_ID);

        // 根据 road_part_id 的最后一个字符确定 direction
        String direction = null;
        if (roadPartId != null && !roadPartId.isEmpty()) {
            char lastChar = roadPartId.charAt(roadPartId.length() - 1);
            switch (lastChar) {
                case 'u':
                    direction = "上行";
                    break;
                case 'd':
                    direction = "下行";
                    break;
                case 't':
                    direction = "双向";
                    break;
            }
        }

        ContentValues infoValues = new ContentValues();
        infoValues.put(COL_ROAD_PART_ID, roadPartId);
        infoValues.put(COL_ROAD_ID, properties.getString(COL_ROAD_ID));
        infoValues.put(COL_ROAD_NAME, properties.getString(COL_ROAD_NAME));
        infoValues.put(COL_DIRECTION, direction); // 插入 direction 数据

        if (properties.has(COL_START_PILE) && !properties.isNull(COL_START_PILE))
            infoValues.put(COL_START_PILE, properties.getDouble(COL_START_PILE));
        else infoValues.putNull(COL_START_PILE);
        if (properties.has(COL_END_PILE) && !properties.isNull(COL_END_PILE))
            infoValues.put(COL_END_PILE, properties.getDouble(COL_END_PILE));
        else infoValues.putNull(COL_END_PILE);
        if (properties.has(COL_ROAD_COUNTRY_ID) && !properties.isNull(COL_ROAD_COUNTRY_ID))
            infoValues.put(COL_ROAD_COUNTRY_ID, properties.getString(COL_ROAD_COUNTRY_ID));
        else infoValues.putNull(COL_ROAD_COUNTRY_ID);
        if (properties.has(COL_ROAD_DESC) && !properties.isNull(COL_ROAD_DESC))
            infoValues.put(COL_ROAD_DESC, properties.getString(COL_ROAD_DESC));
        else infoValues.putNull(COL_ROAD_DESC);

        JSONArray coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates");

        double minLng = Double.MAX_VALUE, maxLng = Double.MIN_VALUE, minLat = Double.MAX_VALUE, maxLat = Double.MIN_VALUE;
        for (int i = 0; i < coordinates.length(); i++) {
            JSONArray coord = coordinates.getJSONArray(i);
            double lng = coord.getDouble(0);
            double lat = coord.getDouble(1);
            minLng = Math.min(minLng, lng);
            maxLng = Math.max(maxLng, lng);
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
        }
        infoValues.put(COL_MIN_LNG, minLng);
        infoValues.put(COL_MAX_LNG, maxLng);
        infoValues.put(COL_MIN_LAT, minLat);
        infoValues.put(COL_MAX_LAT, maxLat);

        double totalLength = 0;
        if (coordinates.length() > 1) {
            String coordInsertSQL = "INSERT INTO " + TABLE_ROAD_COORD + " VALUES (?,?,?,?,?,?,?,?,?)";
            SQLiteStatement stmt = db.compileStatement(coordInsertSQL);
            for (int i = 0; i < coordinates.length() - 1; i++) {
                JSONArray startCoordJson = coordinates.getJSONArray(i);
                JSONArray endCoordJson = coordinates.getJSONArray(i + 1);
                Coord startPoint = new Coord(startCoordJson.getDouble(0), startCoordJson.getDouble(1));
                Coord endPoint = new Coord(endCoordJson.getDouble(0), endCoordJson.getDouble(1));

                double segmentLength = haversineDistance(startPoint, endPoint);
                double dx = endPoint.lng - startPoint.lng;
                double dy = endPoint.lat - startPoint.lat;
                double lenSqApprox = dx * dx + dy * dy;

                stmt.clearBindings();
                stmt.bindString(1, roadPartId);
                stmt.bindLong(2, i);
                stmt.bindDouble(3, startPoint.lng);
                stmt.bindDouble(4, startPoint.lat);
                stmt.bindDouble(5, endPoint.lng);
                stmt.bindDouble(6, endPoint.lat);
                stmt.bindDouble(7, segmentLength);
                stmt.bindDouble(8, totalLength);
                stmt.bindDouble(9, lenSqApprox);
                stmt.executeInsert();

                totalLength += segmentLength;
            }
            stmt.close();
        }

        infoValues.put(COL_TOTAL_LENGTH, totalLength);
        db.insert(TABLE_ROAD_INFO, null, infoValues);
    }

    public RoadLocationMatch findNearestRoadLocation(double currentLng, double currentLat) {
        SQLiteDatabase db = this.getReadableDatabase();

        List<String> candidateRoadIds = getCandidateRoadsByBbox(db, currentLng, currentLat);
        if (candidateRoadIds.isEmpty()) return RoadLocationMatch.noMatch(-1);

        BestMatchCandidate bestCandidate = null;

        for (String roadId : candidateRoadIds) {
            String segmentQuery = "SELECT " + COL_START_LNG + "," + COL_START_LAT + "," +
                    COL_END_LNG + "," + COL_END_LAT + "," + COL_DIST_FROM_START + "," +
                    COL_LEN_SQ_APPROX +
                    " FROM " + TABLE_ROAD_COORD + " WHERE " + COL_ROAD_PART_ID + " = ?";

            try (Cursor segmentCursor = db.rawQuery(segmentQuery, new String[]{roadId})) {
                while (segmentCursor.moveToNext()) {
                    Coord start = new Coord(segmentCursor.getDouble(0), segmentCursor.getDouble(1));
                    Coord end = new Coord(segmentCursor.getDouble(2), segmentCursor.getDouble(3));
                    double lenSqApprox = segmentCursor.getDouble(5);

                    ProjectionResult planarProjection = getPlanarProjectionOnSegment(
                            new Coord(currentLng, currentLat), start, end, lenSqApprox
                    );

                    if (bestCandidate == null || planarProjection.distanceSq < bestCandidate.distanceSq) {
                        bestCandidate = new BestMatchCandidate(
                                roadId,
                                start,
                                segmentCursor.getDouble(4),
                                planarProjection.distanceSq,
                                planarProjection.projectedPoint
                        );
                    }
                }
            }
        }

        if (bestCandidate == null) return RoadLocationMatch.noMatch(-1);

        double preciseDistance = haversineDistance(new Coord(currentLng, currentLat), bestCandidate.projectedPointOnSegment);

        if (preciseDistance > MATCH_DISTANCE_THRESHOLD_METERS) {
            return RoadLocationMatch.noMatch(preciseDistance);
        }

        return calculateFinalPile(db, bestCandidate, preciseDistance);
    }

    private List<String> getCandidateRoadsByBbox(SQLiteDatabase db, double lng, double lat) {
        double lat_buffer = MATCH_DISTANCE_THRESHOLD_METERS / 111320.0;
        double lng_buffer = MATCH_DISTANCE_THRESHOLD_METERS / (111320.0 * Math.cos(Math.toRadians(lat)));
        String selection = COL_MIN_LNG + " <= ? AND " + COL_MAX_LNG + " >= ? AND " + COL_MIN_LAT + " <= ? AND " + COL_MAX_LAT + " >= ?";
        String[] selectionArgs = {String.valueOf(lng + lng_buffer), String.valueOf(lng - lng_buffer), String.valueOf(lat + lat_buffer), String.valueOf(lat - lat_buffer)};
        List<String> ids = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE_ROAD_INFO, new String[]{COL_ROAD_PART_ID}, selection, selectionArgs, null, null, null)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
            }
        }
        return ids;
    }

    private ProjectionResult getPlanarProjectionOnSegment(Coord p, Coord a, Coord b, double lenSq) {
        if (lenSq == 0.0) {
            double dx = p.lng - a.lng;
            double dy = p.lat - a.lat;
            return new ProjectionResult(a, dx * dx + dy * dy);
        }

        double apx = p.lng - a.lng;
        double apy = p.lat - a.lat;
        double abx = b.lng - a.lng;
        double aby = b.lat - a.lat;

        double t = (apx * abx + apy * aby) / lenSq;

        Coord projectedPoint;
        if (t < 0.0) {
            projectedPoint = a;
        } else if (t > 1.0) {
            projectedPoint = b;
        } else {
            projectedPoint = new Coord(a.lng + t * abx, a.lat + t * aby);
        }

        double dx = p.lng - projectedPoint.lng;
        double dy = p.lat - projectedPoint.lat;
        return new ProjectionResult(projectedPoint, dx * dx + dy * dy);
    }

    private RoadLocationMatch calculateFinalPile(SQLiteDatabase db, BestMatchCandidate candidate, double preciseDistance) {
        // 在查询中增加 COL_DIRECTION 字段
        try (Cursor infoCursor = db.query(TABLE_ROAD_INFO,
                new String[]{COL_START_PILE, COL_END_PILE, COL_ROAD_NAME, COL_ROAD_ID, COL_TOTAL_LENGTH, COL_ROAD_COUNTRY_ID, COL_DIRECTION},
                COL_ROAD_PART_ID + " = ?", new String[]{candidate.roadPartId}, null, null, null)) {

            if (infoCursor.moveToFirst()) {
                String roadName = infoCursor.getString(infoCursor.getColumnIndexOrThrow(COL_ROAD_NAME));
                String roadId = infoCursor.getString(infoCursor.getColumnIndexOrThrow(COL_ROAD_ID));

                String roadCountryId = infoCursor.getString(infoCursor.getColumnIndexOrThrow(COL_ROAD_COUNTRY_ID));
                if (roadCountryId == null) {
                    roadCountryId = "暂无";
                }

                // 获取 direction 字段值，如果为 null 则设置为 "暂无"
                String direction = infoCursor.getString(infoCursor.getColumnIndexOrThrow(COL_DIRECTION));
                if (direction == null) {
                    direction = "暂无";
                }

                int startPileIndex = infoCursor.getColumnIndexOrThrow(COL_START_PILE);
                int endPileIndex = infoCursor.getColumnIndexOrThrow(COL_END_PILE);

                if (infoCursor.isNull(startPileIndex) || infoCursor.isNull(endPileIndex)) {
                    // 将 direction 传递给构造函数
                    return new RoadLocationMatch(candidate.roadPartId, roadName, roadId, roadCountryId, direction, Double.NaN, preciseDistance);
                }

                double startPile = infoCursor.getDouble(startPileIndex);
                double endPile = infoCursor.getDouble(endPileIndex);
                double totalLength = infoCursor.getDouble(infoCursor.getColumnIndexOrThrow(COL_TOTAL_LENGTH));

                if (totalLength <= 1e-6) {
                    // 将 direction 传递给构造函数
                    return new RoadLocationMatch(candidate.roadPartId, roadName, roadId, roadCountryId, direction, startPile, preciseDistance);
                }

                double distAlongSegment = haversineDistance(candidate.segmentStart, candidate.projectedPointOnSegment);
                double totalDistFromStart = candidate.distFromStart + distAlongSegment;

                double proportion = Math.max(0, Math.min(1, totalDistFromStart / totalLength));
                double calculatedPile = startPile + (proportion * (endPile - startPile));

                // 将 direction 传递给构造函数
                return new RoadLocationMatch(candidate.roadPartId, roadName, roadId, roadCountryId, direction, calculatedPile, preciseDistance);
            }
        }
        return RoadLocationMatch.noMatch(-1);
    }

    private double haversineDistance(Coord p1, Coord p2) {
        if (p1 == null || p2 == null) return Double.MAX_VALUE;
        double dLat = Math.toRadians(p2.lat - p1.lat);
        double dLon = Math.toRadians(p2.lng - p1.lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(p1.lat)) * Math.cos(Math.toRadians(p2.lat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private static class Coord {
        final double lng, lat;

        Coord(double lng, double lat) {
            this.lng = lng;
            this.lat = lat;
        }
    }

    private static class ProjectionResult {
        final Coord projectedPoint;
        final double distanceSq;

        ProjectionResult(Coord p, double dSq) {
            this.projectedPoint = p;
            this.distanceSq = dSq;
        }
    }

    private static class BestMatchCandidate {
        final String roadPartId;
        final Coord segmentStart, projectedPointOnSegment;
        final double distFromStart, distanceSq;

        BestMatchCandidate(String roadPartId, Coord start, double distFromStart, double distanceSq, Coord projectedPoint) {
            this.roadPartId = roadPartId;
            this.segmentStart = start;
            this.distFromStart = distFromStart;
            this.distanceSq = distanceSq;
            this.projectedPointOnSegment = projectedPoint;
        }
    }

    public static class RoadLocationMatch {
        // 增加 direction 字段
        public final String roadPartId, roadName, roadId, roadCountryId, direction, formattedPile;
        public final double rawPile, distanceToRoad;

        // 更新构造函数以接收 direction
        public RoadLocationMatch(String roadPartId, String roadName, String roadId, String roadCountryId, String direction, double rawPile, double distanceToRoad) {
            this.roadPartId = roadPartId;
            this.roadName = roadName;
            this.roadId = roadId;
            this.roadCountryId = roadCountryId;
            this.direction = direction; // 赋值
            this.rawPile = rawPile;
            this.formattedPile = formatPile(rawPile);
            this.distanceToRoad = distanceToRoad;
        }

        // 更新 noMatch 工厂方法
        public static RoadLocationMatch noMatch(double distance) {
            return new RoadLocationMatch("暂无", "暂无", "暂无", "暂无", "暂无", Double.NaN, distance);
        }

        private String formatPile(double pileNumberInKm) {
            if (Double.isNaN(pileNumberInKm)) {
                return "暂无";
            }

            int kilometers = (int) pileNumberInKm;
            int meters = (int) Math.round((pileNumberInKm - kilometers) * 1000);

            if (meters == 1000) {
                kilometers += 1;
                meters = 0;
            }

            return String.format(Locale.US, "K%d+%03d", kilometers, meters);
        }

        @Override
        public String toString() {
            // 更新 toString 方法以包含 direction
            return "RoadLocationMatch{roadName='" + roadName + "', roadCountryId='" + roadCountryId + "', direction='" + direction + "', formattedPile='" + formattedPile + "', distance=" + String.format(Locale.US, "%.2f", distanceToRoad) + "m}";
        }
    }
}
