// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
/**
 * Deploy this bound to a Google Sheet as a Web App (see SETUP.md).
 * The Cloud Function POSTs one JSON body per detected noise event; this
 * appends it as a row: [timestamp, listenerName, durationSec, audioUrl, soundType, soundLabelName].
 * soundLabelName is blank until a human names the sound (or a later event
 * auto-matches an existing name) - see SubscriptionsFragment/SoundLabelManager.
 */
function doPost(e) {
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getActiveSheet();
  var body = JSON.parse(e.postData.contents);

  sheet.appendRow([
    body.timestamp || new Date().toISOString(),
    body.listenerName || '',
    body.durationSec || '',
    body.audioUrl || '',
    body.soundType || '',
    body.soundLabelName || ''
  ]);

  return ContentService
    .createTextOutput(JSON.stringify({ ok: true }))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Custom spreadsheet function: converts a UTC ISO 8601 timestamp string to
 * New York local time for display, correctly handling the EST/EDT switch.
 * Leaves the source data untouched — use this on a separate "local time" tab.
 *
 * Usage (single cell):  =TO_LOCAL_TIME(Sheet1!A2)
 * Usage (whole column):  =ARRAYFORMULA(TO_LOCAL_TIME(Sheet1!A2:A))
 */
function TO_LOCAL_TIME(input, tz) {
  tz = tz || 'America/New_York';
  if (Array.isArray(input)) {
    return input.map(function (row) {
      return row.map(function (cell) {
        return formatLocalTime_(cell, tz);
      });
    });
  }
  return formatLocalTime_(input, tz);
}

function formatLocalTime_(value, tz) {
  if (!value) return '';
  var date = new Date(value);
  if (isNaN(date.getTime())) return value;
  return Utilities.formatDate(date, tz, 'yyyy-MM-dd hh:mm:ss a zzz');
}

/**
 * Adds a "AnyNoise" menu with a button to refresh a "Devices" sheet tab
 * from the `devices` Firestore collection. Requires the "Firestore" scope
 * in this project's appsscript.json manifest (see SETUP.md step 7) — Apps
 * Script's default OAuth token doesn't include it otherwise.
 */
function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('AnyNoise')
    .addItem('Refresh registered devices', 'refreshDevices')
    .addToUi();
}

function refreshDevices() {
  var projectId = 'all-anynoise';
  var url = 'https://firestore.googleapis.com/v1/projects/' + projectId +
      '/databases/(default)/documents/devices';

  var response = UrlFetchApp.fetch(url, {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() }
  });
  var data = JSON.parse(response.getContentText());
  var docs = data.documents || [];

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName('Devices') || ss.insertSheet('Devices');
  sheet.clear();
  sheet.appendRow(['Device ID', 'Display Name', 'Has FCM Token', 'Muted Listener Count', 'Updated At']);

  docs.forEach(function (doc) {
    var id = doc.name.split('/').pop();
    var fields = doc.fields || {};
    var displayName = (fields.displayName || {}).stringValue || '';
    var hasToken = !!((fields.fcmToken || {}).stringValue);
    var mutedCount = ((fields.mutedListenerIds || {}).arrayValue || {}).values;
    var updatedAt = (fields.updatedAt || {}).timestampValue || '';
    sheet.appendRow([id, displayName, hasToken, mutedCount ? mutedCount.length : 0, updatedAt]);
  });

  SpreadsheetApp.getUi().alert(docs.length + ' device(s) loaded into the Devices tab.');
}
