package org.odk.collect.android.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;

import org.odk.collect.android.application.Collect;
import org.odk.collect.android.injection.DaggerUtils;
import org.odk.collect.android.instances.Instance;
import org.odk.collect.android.instances.InstancesRepository;
import org.odk.collect.android.storage.StoragePathProvider;

import java.util.ArrayList;
import java.util.List;

import static android.provider.BaseColumns._ID;
import static org.odk.collect.android.database.DatabaseConstants.INSTANCES_TABLE_NAME;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.CAN_EDIT_WHEN_COMPLETE;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.DELETED_DATE;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.DISPLAY_NAME;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.GEOMETRY;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.GEOMETRY_TYPE;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.INSTANCE_FILE_PATH;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.JR_FORM_ID;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.JR_VERSION;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.LAST_STATUS_CHANGE_DATE;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.STATUS;
import static org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns.SUBMISSION_URI;

/**
 * Mediates between {@link Instance} objects and the underlying SQLite database that stores them.
 */
public final class DatabaseInstancesRepository implements InstancesRepository {

    private final InstancesDatabaseProvider instancesDatabaseProvider;

    public DatabaseInstancesRepository() {
        instancesDatabaseProvider = DaggerUtils.getComponent(Collect.getInstance()).instancesDatabaseProvider();
    }

    @Override
    public Instance get(Long databaseId) {
        String selection = _ID + "=?";
        String[] selectionArgs = {Long.toString(databaseId)};

        try (Cursor cursor = query(selection, selectionArgs)) {
            List<Instance> result = getInstancesFromCursor(cursor);
            return !result.isEmpty() ? result.get(0) : null;
        }
    }

    @Override
    public Instance getOneByPath(String instancePath) {
        String selection = INSTANCE_FILE_PATH + "=?";
        String[] args = {new StoragePathProvider().getRelativeInstancePath(instancePath)};
        try (Cursor cursor = query(selection, args)) {
            List<Instance> instances = getInstancesFromCursor(cursor);
            if (instances.size() == 1) {
                return instances.get(0);
            } else {
                return null;
            }
        }
    }

    @Override
    public List<Instance> getAll() {
        try (Cursor cursor = query(null, null)) {
            return getInstancesFromCursor(cursor);
        }
    }

    @Override
    public List<Instance> getAllNotDeleted() {
        try (Cursor cursor = query(DELETED_DATE + " IS NULL ", null)) {
            return getInstancesFromCursor(cursor);
        }
    }

    @Override
    public List<Instance> getAllByStatus(String... status) {
        try (Cursor instancesCursor = getCursorForAllByStatus(status)) {
            return getInstancesFromCursor(instancesCursor);
        }
    }

    @Override
    public int getCountByStatus(String... status) {
        try (Cursor cursorForAllByStatus = getCursorForAllByStatus(status)) {
            return cursorForAllByStatus.getCount();
        }
    }


    @Override
    public List<Instance> getAllByFormId(String formId) {
        try (Cursor c = query(JR_FORM_ID + " = ?", new String[]{formId})) {
            return getInstancesFromCursor(c);
        }
    }

    @Override
    public List<Instance> getAllNotDeletedByFormIdAndVersion(String jrFormId, String jrVersion) {
        if (jrVersion != null) {
            try (Cursor cursor = query(JR_FORM_ID + " = ? AND " + JR_VERSION + " = ? AND " + DELETED_DATE + " IS NULL", new String[]{jrFormId, jrVersion})) {
                return getInstancesFromCursor(cursor);
            }
        } else {
            try (Cursor cursor = query(JR_FORM_ID + " = ? AND " + JR_VERSION + " IS NULL AND " + DELETED_DATE + " IS NULL", new String[]{jrFormId})) {
                return getInstancesFromCursor(cursor);
            }
        }
    }

    @Override
    public void delete(Long id) {
        instancesDatabaseProvider.getWriteableDatabase().delete(
                INSTANCES_TABLE_NAME,
                _ID + "=?",
                new String[]{String.valueOf(id)}
        );
    }

    @Override
    public void deleteAll() {
        instancesDatabaseProvider.getWriteableDatabase().delete(
                INSTANCES_TABLE_NAME,
                null,
                null
        );
    }

    @Override
    public Instance save(Instance instance) {
        if (instance.getStatus() == null) {
            instance = new Instance.Builder(instance)
                    .status(Instance.STATUS_INCOMPLETE)
                    .build();
        }

        if (instance.getLastStatusChangeDate() == null) {
            instance = new Instance.Builder(instance)
                    .lastStatusChangeDate(System.currentTimeMillis())
                    .build();
        }

        Long instanceId = instance.getDbId();
        ContentValues values = getValuesFromInstanceObject(instance);

        if (instanceId == null) {
            long insertId = insert(values);
            return get(insertId);
        } else {
            update(instanceId, values);

            return get(instanceId);
        }
    }

    @Override
    public void softDelete(Long id) {
        ContentValues values = new ContentValues();
        values.put(DELETED_DATE, System.currentTimeMillis());
        update(id, values);
    }

    private Cursor getCursorForAllByStatus(String[] status) {
        StringBuilder selection = new StringBuilder(STATUS + "=?");
        for (int i = 1; i < status.length; i++) {
            selection.append(" or ").append(STATUS).append("=?");
        }

        return query(selection.toString(), status);
    }

    private Cursor query(String selection, String[] selectionArgs) {
        SQLiteDatabase readableDatabase = instancesDatabaseProvider.getReadableDatabase();
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(INSTANCES_TABLE_NAME);

        String[] projection = {
                _ID,
                DISPLAY_NAME,
                SUBMISSION_URI,
                CAN_EDIT_WHEN_COMPLETE,
                INSTANCE_FILE_PATH,
                JR_FORM_ID,
                JR_VERSION,
                STATUS,
                LAST_STATUS_CHANGE_DATE,
                DELETED_DATE,
                GEOMETRY,
                GEOMETRY_TYPE
        };

        return qb.query(readableDatabase, projection, selection, selectionArgs, null, null, null);
    }

    private long insert(ContentValues values) {
        return instancesDatabaseProvider.getWriteableDatabase().insert(
                INSTANCES_TABLE_NAME,
                null,
                values
        );
    }

    private void update(Long instanceId, ContentValues values) {
        instancesDatabaseProvider.getWriteableDatabase().update(
                INSTANCES_TABLE_NAME,
                values,
                _ID + "=?",
                new String[]{instanceId.toString()}
        );
    }

    private static ContentValues getValuesFromInstanceObject(Instance instance) {
        ContentValues values = new ContentValues();
        values.put(DISPLAY_NAME, instance.getDisplayName());
        values.put(SUBMISSION_URI, instance.getSubmissionUri());
        values.put(CAN_EDIT_WHEN_COMPLETE, Boolean.toString(instance.canEditWhenComplete()));
        values.put(INSTANCE_FILE_PATH, new StoragePathProvider().getRelativeInstancePath(instance.getInstanceFilePath()));
        values.put(JR_FORM_ID, instance.getFormId());
        values.put(JR_VERSION, instance.getFormVersion());
        values.put(STATUS, instance.getStatus());
        values.put(LAST_STATUS_CHANGE_DATE, instance.getLastStatusChangeDate());
        values.put(DELETED_DATE, instance.getDeletedDate());
        values.put(GEOMETRY, instance.getGeometry());
        values.put(GEOMETRY_TYPE, instance.getGeometryType());
        return values;
    }

    public static List<Instance> getInstancesFromCursor(Cursor cursor) {
        List<Instance> instances = new ArrayList<>();
        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            int displayNameColumnIndex = cursor.getColumnIndex(DISPLAY_NAME);
            int submissionUriColumnIndex = cursor.getColumnIndex(SUBMISSION_URI);
            int canEditWhenCompleteIndex = cursor.getColumnIndex(CAN_EDIT_WHEN_COMPLETE);
            int instanceFilePathIndex = cursor.getColumnIndex(INSTANCE_FILE_PATH);
            int jrFormIdColumnIndex = cursor.getColumnIndex(JR_FORM_ID);
            int jrVersionColumnIndex = cursor.getColumnIndex(JR_VERSION);
            int statusColumnIndex = cursor.getColumnIndex(STATUS);
            int lastStatusChangeDateColumnIndex = cursor.getColumnIndex(LAST_STATUS_CHANGE_DATE);
            int deletedDateColumnIndex = cursor.getColumnIndex(DELETED_DATE);
            int geometryTypeColumnIndex = cursor.getColumnIndex(GEOMETRY_TYPE);
            int geometryColumnIndex = cursor.getColumnIndex(GEOMETRY);

            int databaseIdIndex = cursor.getColumnIndex(_ID);

            Instance instance = new Instance.Builder()
                    .displayName(cursor.getString(displayNameColumnIndex))
                    .submissionUri(cursor.getString(submissionUriColumnIndex))
                    .canEditWhenComplete(Boolean.valueOf(cursor.getString(canEditWhenCompleteIndex)))
                    .instanceFilePath(new StoragePathProvider().getAbsoluteInstanceFilePath(cursor.getString(instanceFilePathIndex)))
                    .formId(cursor.getString(jrFormIdColumnIndex))
                    .formVersion(cursor.getString(jrVersionColumnIndex))
                    .status(cursor.getString(statusColumnIndex))
                    .lastStatusChangeDate(cursor.getLong(lastStatusChangeDateColumnIndex))
                    .deletedDate(cursor.isNull(deletedDateColumnIndex) ? null : cursor.getLong(deletedDateColumnIndex))
                    .geometryType(cursor.getString(geometryTypeColumnIndex))
                    .geometry(cursor.getString(geometryColumnIndex))
                    .dbId(cursor.getLong(databaseIdIndex))
                    .build();

            instances.add(instance);
        }

        return instances;
    }
}
