/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.provider;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.odk.collect.android.application.Collect;
import org.odk.collect.android.database.FormsDatabaseHelper;
import org.odk.collect.android.forms.Form;
import org.odk.collect.android.forms.FormsRepository;
import org.odk.collect.android.injection.DaggerUtils;
import org.odk.collect.android.provider.FormsProviderAPI.FormsColumns;
import org.odk.collect.android.storage.StoragePathProvider;
import org.odk.collect.android.utilities.ContentUriHelper;
import org.odk.collect.android.utilities.FileUtils;
import org.odk.collect.utilities.Clock;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import timber.log.Timber;

import static android.provider.BaseColumns._ID;
import static org.odk.collect.android.database.DatabaseConstants.FORMS_TABLE_NAME;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.AUTO_DELETE;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.AUTO_SEND;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.BASE64_RSA_PUBLIC_KEY;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.CONTENT_NEWEST_FORMS_BY_FORMID_URI;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.CONTENT_URI;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.DATE;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.DELETED_DATE;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.DESCRIPTION;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.DISPLAY_NAME;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.FORM_FILE_PATH;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.FORM_MEDIA_PATH;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.GEOMETRY_XPATH;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.JRCACHE_FILE_PATH;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.JR_FORM_ID;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.JR_VERSION;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.LANGUAGE;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.MD5_HASH;
import static org.odk.collect.android.provider.FormsProviderAPI.FormsColumns.SUBMISSION_URI;

public class FormsProvider extends ContentProvider {
    private static HashMap<String, String> sFormsProjectionMap;

    private static final int FORMS = 1;
    private static final int FORM_ID = 2;
    // Forms unique by ID, keeping only the latest one downloaded
    private static final int NEWEST_FORMS_BY_FORM_ID = 3;

    private static final UriMatcher URI_MATCHER;

    @Inject
    Clock clock;

    @Inject
    FormsRepository formsRepository;

    public static void notifyChange() {
        // Make sure content observers (CursorLoaders for instance) are notified of change
        Collect.getInstance().getContentResolver().notifyChange(CONTENT_URI, null);
        Collect.getInstance().getContentResolver().notifyChange(CONTENT_NEWEST_FORMS_BY_FORMID_URI, null);
    }

    // Do not call it in onCreate() https://stackoverflow.com/questions/23521083/inject-database-in-a-contentprovider-with-dagger
    private void deferDaggerInit() {
        DaggerUtils.getComponent(getContext()).inject(this);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        deferDaggerInit();

        Cursor cursor;
        switch (URI_MATCHER.match(uri)) {
            case FORMS:
                cursor = formsRepository.rawQuery(projection, selection, selectionArgs, sortOrder);
                break;

            case FORM_ID:
                String formId = String.valueOf(ContentUriHelper.getIdFromUri(uri));
                cursor = formsRepository.rawQuery(null, _ID + "=?", new String[]{formId}, null);
                break;

            // Only include the latest form that was downloaded with each form_id
            case NEWEST_FORMS_BY_FORM_ID:
                SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
                qb.setTables(FORMS_TABLE_NAME);
                qb.setProjectionMap(sFormsProjectionMap);
                qb.setStrict(true);

                Map<String, String> filteredProjectionMap = new HashMap<>(sFormsProjectionMap);
                filteredProjectionMap.put(DATE, FormsColumns.MAX_DATE);

                qb.setProjectionMap(filteredProjectionMap);
                String groupBy = FormsColumns.JR_FORM_ID;

                FormsDatabaseHelper formsDatabaseHelper = FormsDatabaseHelper.getDbHelper();
                cursor = qb.query(formsDatabaseHelper.getReadableDatabase(), projection, selection, selectionArgs, groupBy, null, sortOrder);
                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // Tell the cursor what uri to watch, so it knows when its source data changes
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        switch (URI_MATCHER.match(uri)) {
            case FORMS:
            case NEWEST_FORMS_BY_FORM_ID:
                return FormsColumns.CONTENT_TYPE;

            case FORM_ID:
                return FormsColumns.CONTENT_ITEM_TYPE;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public synchronized Uri insert(@NonNull Uri uri, ContentValues initialValues) {
        deferDaggerInit();

        // Validate the requested uri
        if (URI_MATCHER.match(uri) != FORMS) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        FormsDatabaseHelper formsDatabaseHelper = FormsDatabaseHelper.getDbHelper();
        if (formsDatabaseHelper != null) {
            ContentValues values;
            if (initialValues != null) {
                values = new ContentValues(initialValues);
            } else {
                values = new ContentValues();
            }

            if (!values.containsKey(FormsColumns.FORM_FILE_PATH)) {
                throw new IllegalArgumentException(FormsColumns.FORM_FILE_PATH + " must be specified.");
            }

            // Normalize the file path.
            // (don't trust the requester).
            StoragePathProvider storagePathProvider = new StoragePathProvider();
            String filePath = storagePathProvider.getAbsoluteFormFilePath(values.getAsString(FormsColumns.FORM_FILE_PATH));
            File form = new File(filePath);
            filePath = form.getAbsolutePath(); // normalized
            values.put(FormsColumns.FORM_FILE_PATH, storagePathProvider.getRelativeFormPath(filePath));

            Long now = clock.getCurrentTime();

            // Make sure that the necessary fields are all set
            if (!values.containsKey(DATE)) {
                values.put(DATE, now);
            }

            if (!values.containsKey(FormsColumns.DISPLAY_NAME)) {
                values.put(FormsColumns.DISPLAY_NAME, form.getName());
            }

            // don't let users put in a manual md5 hash
            if (values.containsKey(FormsColumns.MD5_HASH)) {
                values.remove(FormsColumns.MD5_HASH);
            }
            String md5 = FileUtils.getMd5Hash(form);
            values.put(FormsColumns.MD5_HASH, md5);

            if (!values.containsKey(FormsColumns.JRCACHE_FILE_PATH)) {
                values.put(FormsColumns.JRCACHE_FILE_PATH, storagePathProvider.getRelativeCachePath(md5 + ".formdef"));
            }
            if (!values.containsKey(FormsColumns.FORM_MEDIA_PATH)) {
                values.put(FormsColumns.FORM_MEDIA_PATH, storagePathProvider.getRelativeFormPath(FileUtils.constructMediaPath(filePath)));
            }

            SQLiteDatabase db = formsDatabaseHelper.getWritableDatabase();

            // first try to see if a record with this filename already exists...
            String[] projection = {FormsColumns._ID, FormsColumns.FORM_FILE_PATH};
            String[] selectionArgs = {storagePathProvider.getRelativeFormPath(filePath)};
            String selection = FormsColumns.FORM_FILE_PATH + "=?";
            Cursor c = null;
            try {
                c = db.query(FORMS_TABLE_NAME, projection, selection,
                        selectionArgs, null, null, null);
                if (c.getCount() > 0) {
                    // already exists
                    throw new SQLException("FAILED Insert into " + uri
                            + " -- row already exists for form definition file: "
                            + filePath);
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }

            long rowId = db.insertOrThrow(FORMS_TABLE_NAME, null, values);
            if (rowId > 0) {
                Uri formUri = ContentUris.withAppendedId(FormsColumns.CONTENT_URI,
                        rowId);
                getContext().getContentResolver().notifyChange(formUri, null);
                getContext().getContentResolver().notifyChange(FormsColumns.CONTENT_NEWEST_FORMS_BY_FORMID_URI, null);
                return formUri;
            }
        }

        throw new SQLException("Failed to insert into the forms database.");
    }

    private void deleteFileOrDir(String fileName) {
        File file = new File(fileName);
        if (file.exists()) {
            if (file.isDirectory()) {
                // delete all the containing files
                File[] files = file.listFiles();
                if (files != null) {
                    for (File f : files) {
                        // should make this recursive if we get worried about
                        // the media directory containing directories
                        Timber.i("attempting to delete file: %s", f.getAbsolutePath());
                        f.delete();
                    }
                }
            }
            file.delete();
            Timber.i("attempting to delete file: %s", file.getAbsolutePath());
        }
    }

    /**
     * This method removes the entry from the content provider, and also removes
     * any associated files. files: form.xml, [formmd5].formdef, formname-media
     * {directory}
     */
    @Override
    public int delete(@NonNull Uri uri, String where, String[] whereArgs) {
        int count;

        switch (URI_MATCHER.match(uri)) {
            case FORMS:
                Cursor cursor = formsRepository.rawQuery(null, where, whereArgs, null);
                while (cursor.moveToNext()) {
                    formsRepository.delete(cursor.getLong(cursor.getColumnIndex(_ID)));
                }

                count = cursor.getCount();
                break;

            case FORM_ID:
                formsRepository.delete(ContentUriHelper.getIdFromUri(uri));
                count = 1;
                break;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        getContext().getContentResolver().notifyChange(FormsColumns.CONTENT_NEWEST_FORMS_BY_FORMID_URI, null);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        StoragePathProvider storagePathProvider = new StoragePathProvider();

        int count = 0;
        FormsDatabaseHelper formsDatabaseHelper = FormsDatabaseHelper.getDbHelper();
        if (formsDatabaseHelper != null) {
            SQLiteDatabase db = formsDatabaseHelper.getWritableDatabase();
            switch (URI_MATCHER.match(uri)) {
                case FORMS:
                    Cursor cursor = formsRepository.rawQuery(null, where, whereArgs, null);
                    while (cursor.moveToNext()) {
                        Form form = getFormFromCurrentCursorPosition(cursor, storagePathProvider);

                        ContentValues existingValues = getValuesFromForm(form, storagePathProvider);
                        existingValues.putAll(values);

                        formsRepository.save(getFormFromValues(existingValues, storagePathProvider));
                    }

                    count = cursor.getCount();
                    break;

                case FORM_ID:
                    String formId = uri.getPathSegments().get(1);
                    // Whenever file paths are updated, delete the old files.

                    Cursor update = null;
                    try {
                        update = this.query(uri, null, where, whereArgs, null);

                        // This should only ever return 1 record.
                        if (update != null && update.getCount() > 0) {
                            update.moveToFirst();

                            // don't let users manually update md5
                            if (values.containsKey(FormsColumns.MD5_HASH)) {
                                values.remove(FormsColumns.MD5_HASH);
                            }

                            // the order here is important (jrcache needs to be before
                            // form file)
                            // because we update the jrcache file if there's a new form
                            // file
                            if (values.containsKey(FormsColumns.JRCACHE_FILE_PATH)) {
                                deleteFileOrDir(storagePathProvider.getAbsoluteCacheFilePath(update
                                        .getString(update
                                                .getColumnIndex(FormsColumns.JRCACHE_FILE_PATH))));
                            }

                            if (values.containsKey(FormsColumns.FORM_FILE_PATH)) {
                                String formFile = storagePathProvider.getAbsoluteFormFilePath(values
                                        .getAsString(FormsColumns.FORM_FILE_PATH));
                                String oldFile = storagePathProvider.getAbsoluteFormFilePath(update.getString(update
                                        .getColumnIndex(FormsColumns.FORM_FILE_PATH)));

                                if (formFile == null || !formFile.equalsIgnoreCase(oldFile)) {
                                    deleteFileOrDir(oldFile);
                                }

                                // we're updating our file, so update the md5
                                // and get rid of the cache (doesn't harm anything)
                                deleteFileOrDir(storagePathProvider.getAbsoluteCacheFilePath(update
                                        .getString(update
                                                .getColumnIndex(FormsColumns.JRCACHE_FILE_PATH))));
                                String newMd5 = FileUtils
                                        .getMd5Hash(new File(formFile));
                                values.put(FormsColumns.MD5_HASH, newMd5);
                                values.put(FormsColumns.JRCACHE_FILE_PATH,
                                        storagePathProvider.getRelativeCachePath(newMd5 + ".formdef"));
                            }

                            count = db.update(
                                    FORMS_TABLE_NAME,
                                    values,
                                    FormsColumns._ID
                                            + "=?"
                                            + (!TextUtils.isEmpty(where) ? " AND ("
                                            + where + ')' : ""), prepareWhereArgs(whereArgs, formId));
                        } else {
                            Timber.e("Attempting to update row that does not exist");
                        }
                    } finally {
                        if (update != null) {
                            update.close();
                        }
                    }
                    break;

                default:
                    throw new IllegalArgumentException("Unknown URI " + uri);
            }

            getContext().getContentResolver().notifyChange(uri, null);
            getContext().getContentResolver().notifyChange(FormsColumns.CONTENT_NEWEST_FORMS_BY_FORMID_URI, null);
        }

        return count;
    }

    @NonNull
    private String[] prepareWhereArgs(String[] whereArgs, String formId) {
        String[] newWhereArgs;
        if (whereArgs == null || whereArgs.length == 0) {
            newWhereArgs = new String[]{formId};
        } else {
            newWhereArgs = new String[whereArgs.length + 1];
            newWhereArgs[0] = formId;
            System.arraycopy(whereArgs, 0, newWhereArgs, 1, whereArgs.length);
        }
        return newWhereArgs;
    }

    private static Form getFormFromCurrentCursorPosition(Cursor cursor, StoragePathProvider storagePathProvider) {
        int idColumnIndex = cursor.getColumnIndex(_ID);
        int displayNameColumnIndex = cursor.getColumnIndex(DISPLAY_NAME);
        int descriptionColumnIndex = cursor.getColumnIndex(FormsProviderAPI.FormsColumns.DESCRIPTION);
        int jrFormIdColumnIndex = cursor.getColumnIndex(JR_FORM_ID);
        int jrVersionColumnIndex = cursor.getColumnIndex(JR_VERSION);
        int formFilePathColumnIndex = cursor.getColumnIndex(FORM_FILE_PATH);
        int submissionUriColumnIndex = cursor.getColumnIndex(SUBMISSION_URI);
        int base64RSAPublicKeyColumnIndex = cursor.getColumnIndex(BASE64_RSA_PUBLIC_KEY);
        int md5HashColumnIndex = cursor.getColumnIndex(FormsProviderAPI.FormsColumns.MD5_HASH);
        int dateColumnIndex = cursor.getColumnIndex(DATE);
        int jrCacheFilePathColumnIndex = cursor.getColumnIndex(FormsProviderAPI.FormsColumns.JRCACHE_FILE_PATH);
        int formMediaPathColumnIndex = cursor.getColumnIndex(FORM_MEDIA_PATH);
        int languageColumnIndex = cursor.getColumnIndex(FormsProviderAPI.FormsColumns.LANGUAGE);
        int autoSendColumnIndex = cursor.getColumnIndex(AUTO_SEND);
        int autoDeleteColumnIndex = cursor.getColumnIndex(AUTO_DELETE);
        int geometryXpathColumnIndex = cursor.getColumnIndex(GEOMETRY_XPATH);
        int deletedDateColumnIndex = cursor.getColumnIndex(DELETED_DATE);

        return new Form.Builder()
                .id(cursor.getLong(idColumnIndex))
                .displayName(cursor.getString(displayNameColumnIndex))
                .description(cursor.getString(descriptionColumnIndex))
                .jrFormId(cursor.getString(jrFormIdColumnIndex))
                .jrVersion(cursor.getString(jrVersionColumnIndex))
                .formFilePath(storagePathProvider.getAbsoluteFormFilePath(cursor.getString(formFilePathColumnIndex)))
                .submissionUri(cursor.getString(submissionUriColumnIndex))
                .base64RSAPublicKey(cursor.getString(base64RSAPublicKeyColumnIndex))
                .md5Hash(cursor.getString(md5HashColumnIndex))
                .date(cursor.getLong(dateColumnIndex))
                .jrCacheFilePath(storagePathProvider.getAbsoluteCacheFilePath(cursor.getString(jrCacheFilePathColumnIndex)))
                .formMediaPath(storagePathProvider.getAbsoluteFormFilePath(cursor.getString(formMediaPathColumnIndex)))
                .language(cursor.getString(languageColumnIndex))
                .autoSend(cursor.getString(autoSendColumnIndex))
                .autoDelete(cursor.getString(autoDeleteColumnIndex))
                .geometryXpath(cursor.getString(geometryXpathColumnIndex))
                .deleted(!cursor.isNull(deletedDateColumnIndex))
                .build();
    }

    private static ContentValues getValuesFromForm(Form form, StoragePathProvider storagePathProvider) {
        ContentValues values = new ContentValues();
        values.put(_ID, form.getId());
        values.put(FormsProviderAPI.FormsColumns.DISPLAY_NAME, form.getDisplayName());
        values.put(FormsProviderAPI.FormsColumns.DESCRIPTION, form.getDescription());
        values.put(FormsProviderAPI.FormsColumns.JR_FORM_ID, form.getJrFormId());
        values.put(FormsProviderAPI.FormsColumns.JR_VERSION, form.getJrVersion());
        values.put(FormsProviderAPI.FormsColumns.FORM_FILE_PATH, storagePathProvider.getRelativeFormPath(form.getFormFilePath()));
        values.put(FormsProviderAPI.FormsColumns.SUBMISSION_URI, form.getSubmissionUri());
        values.put(FormsProviderAPI.FormsColumns.BASE64_RSA_PUBLIC_KEY, form.getBASE64RSAPublicKey());
        values.put(FormsProviderAPI.FormsColumns.MD5_HASH, form.getMD5Hash());
        values.put(FormsProviderAPI.FormsColumns.FORM_MEDIA_PATH, storagePathProvider.getRelativeFormPath(form.getFormMediaPath()));
        values.put(FormsProviderAPI.FormsColumns.LANGUAGE, form.getLanguage());
        values.put(FormsProviderAPI.FormsColumns.AUTO_SEND, form.getAutoSend());
        values.put(FormsProviderAPI.FormsColumns.AUTO_DELETE, form.getAutoDelete());
        values.put(FormsProviderAPI.FormsColumns.GEOMETRY_XPATH, form.getGeometryXpath());

        return values;
    }

    private static Form getFormFromValues(ContentValues values, StoragePathProvider storagePathProvider) {
        return new Form.Builder()
                .id(values.getAsLong(_ID))
                .displayName(values.getAsString(DISPLAY_NAME))
                .description(values.getAsString(DESCRIPTION))
                .jrFormId(values.getAsString(JR_FORM_ID))
                .jrVersion(values.getAsString(JR_VERSION))
                .formFilePath(storagePathProvider.getAbsoluteFormFilePath(values.getAsString(FORM_FILE_PATH)))
                .submissionUri(values.getAsString(SUBMISSION_URI))
                .base64RSAPublicKey(values.getAsString(BASE64_RSA_PUBLIC_KEY))
                .md5Hash(values.getAsString(MD5_HASH))
                .date(values.getAsLong(DATE))
                .jrCacheFilePath(storagePathProvider.getAbsoluteCacheFilePath(values.getAsString(JRCACHE_FILE_PATH)))
                .formMediaPath(storagePathProvider.getAbsoluteFormFilePath(values.getAsString(FORM_MEDIA_PATH)))
                .language(values.getAsString(LANGUAGE))
                .autoSend(values.getAsString(AUTO_SEND))
                .autoDelete(values.getAsString(AUTO_DELETE))
                .geometryXpath(values.getAsString(GEOMETRY_XPATH))
                .deleted(values.getAsLong(DELETED_DATE) != null)
                .build();
    }

    static {
        URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
        URI_MATCHER.addURI(FormsProviderAPI.AUTHORITY, FormsColumns.CONTENT_URI.getPath(), FORMS);
        URI_MATCHER.addURI(FormsProviderAPI.AUTHORITY, FormsColumns.CONTENT_URI.getPath() + "/#", FORM_ID);
        // Only available for query and type
        URI_MATCHER.addURI(FormsProviderAPI.AUTHORITY, FormsColumns.CONTENT_NEWEST_FORMS_BY_FORMID_URI.getPath(), NEWEST_FORMS_BY_FORM_ID);

        sFormsProjectionMap = new HashMap<>();
        sFormsProjectionMap.put(FormsColumns._ID, FormsColumns._ID);
        sFormsProjectionMap.put(FormsColumns.DISPLAY_NAME, FormsColumns.DISPLAY_NAME);
        sFormsProjectionMap.put(FormsColumns.DESCRIPTION, FormsColumns.DESCRIPTION);
        sFormsProjectionMap.put(FormsColumns.JR_FORM_ID, FormsColumns.JR_FORM_ID);
        sFormsProjectionMap.put(FormsColumns.JR_VERSION, FormsColumns.JR_VERSION);
        sFormsProjectionMap.put(FormsColumns.SUBMISSION_URI, FormsColumns.SUBMISSION_URI);
        sFormsProjectionMap.put(FormsColumns.BASE64_RSA_PUBLIC_KEY, FormsColumns.BASE64_RSA_PUBLIC_KEY);
        sFormsProjectionMap.put(FormsColumns.MD5_HASH, FormsColumns.MD5_HASH);
        sFormsProjectionMap.put(DATE, DATE);
        sFormsProjectionMap.put(FormsColumns.FORM_MEDIA_PATH, FormsColumns.FORM_MEDIA_PATH);
        sFormsProjectionMap.put(FormsColumns.FORM_FILE_PATH, FormsColumns.FORM_FILE_PATH);
        sFormsProjectionMap.put(FormsColumns.JRCACHE_FILE_PATH, FormsColumns.JRCACHE_FILE_PATH);
        sFormsProjectionMap.put(FormsColumns.LANGUAGE, FormsColumns.LANGUAGE);
        sFormsProjectionMap.put(FormsColumns.AUTO_DELETE, FormsColumns.AUTO_DELETE);
        sFormsProjectionMap.put(FormsColumns.AUTO_SEND, FormsColumns.AUTO_SEND);
        sFormsProjectionMap.put(FormsColumns.GEOMETRY_XPATH, FormsColumns.GEOMETRY_XPATH);
        sFormsProjectionMap.put(FormsColumns.DELETED_DATE, FormsColumns.DELETED_DATE);
    }
}
