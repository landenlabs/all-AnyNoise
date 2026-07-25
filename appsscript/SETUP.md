# Sheets logging setup (enhanced feature)

This wires detected noise events into a row of a Google Sheet. It's the one
piece I can't do for you — it needs your Google account.

1. Create (or open) a Google Sheet you want events logged into. Add a header
   row if you like: `Timestamp | Listener | Duration (s) | Audio URL | Sound Type`.
2. In the Sheet, go to **Extensions → Apps Script**.
3. Delete the default `Code.gs` boilerplate and paste in the contents of
   this directory's `Code.gs`.
4. Click **Deploy → New deployment**.
   - Type: **Web app**
   - Execute as: **Me**
   - Who has access: **Anyone** (the URL itself is the only "secret" — it's
     called only by your Cloud Function, never shipped in the app)
5. Click **Deploy**, authorize the requested permissions, and copy the Web
   app URL it gives you (ends in `/exec`).
6. Create `functions/.env` (gitignored) with that URL:
   ```
   SHEETS_WEBHOOK_URL=https://script.google.com/macros/s/XXXX/exec
   ```
   `functions/index.js` reads it via `firebase-functions/params`'s
   `defineString("SHEETS_WEBHOOK_URL")` — no code changes needed.

If you skip this, the app still works end-to-end for registration, opt-out,
listening, and push notifications — `logToSheet()` in `functions/index.js`
just no-ops when the URL isn't configured.

## Bonus: "Refresh registered devices" menu button

`Code.gs` also adds an **AnyNoise → Refresh registered devices** menu item
that pulls the current `devices` Firestore collection into a `Devices` tab
in this same Sheet — a no-code way to check who's registered.

7. In the Apps Script editor, click the gear icon (**Project Settings**) →
   check **"Show appsscript.json manifest file"**.
8. Open `appsscript.json` and add the Firestore OAuth scope to `oauthScopes`
   (create the array if it isn't there). `script.external_request` is also
   required once this array is explicit — Apps Script otherwise auto-detects
   scopes from your code, but an explicit `oauthScopes` list disables that,
   so `UrlFetchApp.fetch()` (used to call the Firestore REST API) needs its
   own scope listed or you'll hit "Specified permissions are not sufficient
   to call UrlFetchApp.fetch". Use `datastore`, not `datastore.readonly` —
   the Firestore REST API only recognizes the read/write scope; a
   `datastore.readonly` scope is silently accepted at consent time but
   produces a token with no real access, causing a 403 "insufficient
   authentication scopes" error when calling `firestore.googleapis.com`:
   ```json
   "oauthScopes": [
     "https://www.googleapis.com/auth/spreadsheets.currentonly",
     "https://www.googleapis.com/auth/script.container.ui",
     "https://www.googleapis.com/auth/datastore",
     "https://www.googleapis.com/auth/script.external_request"
   ]
   ```
9. Save, reload the Sheet (close and reopen it), and re-authorize when
   prompted — you'll be asked to grant read access to Firestore data.
10. Use the new **AnyNoise → Refresh registered devices** menu to populate
    the `Devices` tab. The account you authorize as must have at least
    "Firestore Viewer" (Cloud IAM role) on the `all-anynoise` project.
