# Firebase / GCP Setup Instructions

Step-by-step record of setting up Firebase (Firestore, Cloud Storage, Cloud
Messaging), the Blaze billing plan, and deploying the backend for this
project (`all-anynoise`). Written so it can be repeated on a fresh machine,
or so the Blaze plan / cloud spend can be safely monitored and torn down
later.

## 0. Prerequisites already in place

- `app/google-services.json` — downloaded from Firebase console, placed in
  `app/` (gitignored, never commit it). If starting from scratch: Firebase
  console → **Add app** → Android → package name matching `applicationId`
  in `app/build.gradle` → download the file.
- A Firebase project exists (this project: `all-anynoise`).

If you don't have a project yet: https://console.firebase.google.com/ →
**Add project**.

## 1. Install the CLIs (macOS, Homebrew)

```
brew install firebase-cli
brew install --cask gcloud-cli
```

`firebase-cli` installs cleanly. `gcloud-cli` has a known bug: the cask's
post-install script tries to set up a Python virtualenv for optional gcloud
extension modules, and on newer Homebrew Python (PEP 668
"externally-managed-environment") that `pip install --user` step fails:

```
ERROR: Can not perform a '--user' install. User site-packages are not visible in this virtualenv.
ERROR: Virtual env setup failed.
```

This is **non-fatal** — it's only the optional extension modules, not the
core CLI. If the cask install rolls itself back entirely (Homebrew unlinks
everything because the postflight script exited non-zero), use Google's
standalone installer instead, which hits the same warning but does **not**
abort:

```
curl https://sdk.cloud.google.com | bash
exec -l $SHELL
```

The installer:
- Installs into `~/google-cloud-sdk`
- Prompts to update your shell profile (`~/.zshrc`) — say yes, it backs up
  the existing file first (`~/.zshrc.backup`)
- Prints the same virtualenv warning — ignore it, `gcloud` itself works
- `exec -l $SHELL` restarts your shell so the updated `PATH` takes effect

Docs: https://firebase.google.com/docs/cli and
https://cloud.google.com/sdk/docs/install

## 2. Authenticate both CLIs

```
gcloud init
firebase login
```

`gcloud init` opens a browser for Google login, then lets you pick/create a
default project — pick `all-anynoise`.
`firebase login` also opens a browser for Google login.

Verify either is authenticated at any time:

```
gcloud auth list
gcloud config get-value project
firebase login:list
```

## 3. Point the CLIs at this project

`gcloud`'s active project is set via `gcloud init` (or `gcloud config set
project all-anynoise`).

For `firebase`, create `.firebaserc` in the repo root (gitignored — the
checked-in `.firebaserc.example` is just a template):

```json
{
  "projects": {
    "default": "all-anynoise"
  }
}
```

## 4. Enable Firebase products

### Cloud Messaging (FCM)
No action needed — enabled automatically (`fcm.googleapis.com`,
`fcmregistrations.googleapis.com`) as soon as an Android app is registered
in the project.

### Firestore
Firebase console → https://console.firebase.google.com/project/all-anynoise/firestore
→ **Create database** → pick a region (used `nam5`) → Native mode.

Verify from the CLI:

```
gcloud services list --available --project=all-anynoise \
  --filter="name:firestore.googleapis.com" --format="table(config.name,state)"
gcloud firestore databases list --project=all-anynoise
```

### Cloud Storage
As of Oct 2024, Google requires the **Blaze** plan to provision a new
Firebase Storage bucket — the free Spark plan can't create one, even though
Firestore/FCM stay available on Spark. See step 5 below, then:

Firebase console → https://console.firebase.google.com/project/all-anynoise/storage
→ **Get started** → choose security rules mode (test mode is fine
initially) → pick a location → finish.

Verify from the CLI:

```
gcloud services list --available --project=all-anynoise \
  --filter="name:firebasestorage.googleapis.com" --format="table(config.name,state)"
gcloud storage buckets list --project=all-anynoise
```

## 5. Upgrade to the Blaze plan

Needed only for Cloud Storage (and for Cloud Functions in step 6).

Firebase console → gear icon → **Usage and billing** →
https://console.firebase.google.com/project/all-anynoise/usage/details
→ **Modify plan** → select **Blaze** → link (or create) a Cloud Billing
account with a payment method.

Blaze is pay-as-you-go but still includes the same free-tier quotas as
Spark (e.g. 5 GB Storage, 1 GB/day download, 50K Firestore reads/day, 2M
function invocations/month) — you're only billed for usage **above** those
quotas. For a small app like this, expected cost is $0/month unless usage
spikes.

Pricing reference: https://firebase.google.com/pricing

## 6. Deploy the backend (Firestore rules, Storage rules, Cloud Function)

```
cd functions
npm install
cd ..
```

`npm install` may warn about a Node engine mismatch (`functions/package.json`
expects Node 20, local Node may be newer) — harmless locally; Cloud
Functions runs on the pinned runtime (`nodejs20`) regardless of your local
Node version.

The optional Sheets webhook is read via `defineString("SHEETS_WEBHOOK_URL",
{default: ""})` in `functions/index.js`. Deploying non-interactively (e.g.
from a script) requires an explicit value even to accept the default — create
`functions/.env` (gitignored):

```
echo 'SHEETS_WEBHOOK_URL=' > functions/.env
```

Leave it empty to skip Sheets logging (see `appsscript/SETUP.md` if you want
it later).

`firebase.json` needs a `storage` block pointing at `storage.rules` — this
was missing in the original checked-in file and had to be added:

```json
"storage": {
  "bucket": "all-anynoise.firebasestorage.app",
  "rules": "storage.rules"
}
```

Deploy each piece. Note the target syntax gotcha: `storage:rules` is only
valid if you've configured **named** storage targets (multi-bucket setups)
via `firebase target:apply`. For a single default bucket, use plain
`storage`:

```
firebase deploy --only firestore:rules
firebase deploy --only storage
firebase deploy --only functions
```

(`firebase deploy --only firestore:rules,storage:rules,functions` in one
shot will fail on the storage part for this reason.)

**First-time function deploy gotcha:** the very first 2nd-gen function in a
project can fail once with:

```
Permission denied while using the Eventarc Service Agent. ... it may take
a few minutes before all necessary permissions are propagated
```

This is expected — wait ~1-2 minutes and re-run `firebase deploy --only
functions`. It succeeded on retry here.

**Cleanup policy warning:** after a successful function deploy you may see
a warning about no cleanup policy for container image artifacts (they'd
otherwise accumulate small storage cost over time). Fix once:

```
firebase functions:artifacts:setpolicy
```

This sets container images in that region to auto-delete after 1 day.

Verify the function is live:

```
firebase functions:list
```

## 7. Google Sheets logging (optional)

Wires each detected noise event into a row of a Google Sheet, via a Cloud
Function → Apps Script Web App webhook. Skippable — the app and push
notifications work fully without it (`logToSheet()` in `functions/index.js`
no-ops when the URL isn't configured).

### 7.1 Create the Sheet and script
1. Create/open a Google Sheet: https://sheets.google.com — optionally add a
   header row: `Timestamp | Listener | Duration (s) | Audio URL`.
2. In the Sheet: **Extensions → Apps Script**.
3. Delete the default boilerplate and paste in the contents of this repo's
   `appsscript/Code.gs`.
4. (Optional) Rename the project — it defaults to **"Untitled project"**,
   which is also what shows on the OAuth consent screen later. Click the
   "Untitled project" text next to the Apps Script logo (top-left of the
   editor) to rename it, e.g. `AnyNoise Sheets Webhook`.

### 7.2 Deploy as a Web App
**Deploy → New deployment**:
- Type: **Web app**
- Execute as: **Me**
- Who has access: **Anyone**

Click **Deploy**, then **Authorize access**. You'll see an "unverified app"
warning since it's a personal script — click **Advanced → Go to [project
name] (unsafe)**, that's expected for scripts you author yourself.

Copy the resulting Web app URL (ends in `/exec`).

**Gotcha — 403 "You need access" even with "Anyone" selected at creation
time:** if a test call comes back with a Google Drive-style "You need
access" page instead of executing, the deployment's access setting didn't
actually stick. Fix: **Deploy → Manage deployments** → pencil/edit icon on
the Web app deployment → re-check **"Who has access" = Anyone** → **Deploy**
again. Note this is a *deployment* setting inside Apps Script — sharing the
underlying Google Sheet itself (Drive-level "anyone with the link") is a
**separate** setting and does not fix this.

### 7.3 Wire the URL into the Cloud Function
Put the URL in `functions/.env` (gitignored):

```
echo 'SHEETS_WEBHOOK_URL=https://script.google.com/macros/s/XXXX/exec' > functions/.env
```

Redeploy the function so it picks up the new env value (`defineString`
reads it at deploy time, not runtime):

```
firebase deploy --only functions
```

### 7.4 Test the webhook directly
Apps Script Web App calls always 302-redirect from `/exec` to a
`script.googleusercontent.com/macros/echo?...` URL to hand back the response
body — the *first* request already executes `doPost` (i.e. the row is
appended) even though you see a redirect, not a 200, from `/exec` itself.
`curl` doesn't follow redirects by default, so a plain POST looks like it
"did nothing" even when it worked. Two ways to check:

```
# 1. Fire the POST, capture the Location header from the 302 response:
curl -s -D - -X POST "https://script.google.com/macros/s/XXXX/exec" \
  -H "Content-Type: application/json" \
  -d '{"timestamp":"TEST-ROW-DELETE-ME","listenerName":"smoke-test","durationSec":3,"audioUrl":"https://example.com/test.wav"}' \
  -o /dev/null

# 2. GET the echo URL from that Location header to see the actual result:
curl -s "https://script.googleusercontent.com/macros/echo?user_content_key=..."
# → {"ok":true}
```

Do **not** use `curl -L --post302` to auto-follow — the echo URL only
accepts GET, so forcing POST on it returns 405.

A `{"ok":true}` response confirms the row was appended — check the Sheet
and delete the `TEST-ROW-DELETE-ME` row afterward.

## 8. Monitor Firebase / GCP charges

- **Firebase usage dashboard** (per-product quota usage, free-tier
  progress):
  https://console.firebase.google.com/project/all-anynoise/usage/details
- **Google Cloud Billing overview** (actual $ spend, invoices):
  https://console.cloud.google.com/billing
- **Budgets & alerts** (recommended — get emailed before you're
  surprised by a bill): https://console.cloud.google.com/billing/budgets
  → **Create budget** → scope to this project → set an amount (e.g. $1 or
  $5) → set alert thresholds (50%/90%/100% of budget) → save. You'll get an
  email if usage/cost approaches the threshold, even if it never actually
  charges anything.
- **Cloud Functions console** (invocation counts, errors, logs):
  https://console.cloud.google.com/functions/list?project=all-anynoise

## 9. Disable Blaze / stop billing

Two options depending on how much you want to keep working.

### Option A (recommended if you're still developing): keep Blaze, add a budget alert
Cloud Functions and Cloud Storage **require** Blaze — downgrading to Spark
deletes/disables both. If you're still actively using this app, just set a
low budget alert (step 8) instead of downgrading, and periodically check
https://console.cloud.google.com/billing. Realistic cost for this app's
usage pattern (one Cloud Function trigger, small Storage/Firestore volume)
is $0/month within free-tier quotas.

### Option B: fully downgrade to Spark (stops all possibility of charges)
Downgrading is blocked while paid-tier-only resources exist, so delete them
first:

1. Delete the Cloud Function:
   ```
   firebase functions:delete onNoiseEventCreated
   ```
   or via console: https://console.cloud.google.com/functions/list?project=all-anynoise
2. Delete the Storage bucket contents/bucket (only if you're OK losing any
   uploaded audio clips):
   Firebase console → Storage →
   https://console.firebase.google.com/project/all-anynoise/storage →
   bucket settings → delete bucket. (Deleting is permanent.)
3. Downgrade the plan: Firebase console → gear icon → **Usage and
   billing** → **Details & settings** → **Modify plan** → select **Spark**.
4. Optionally remove the billing account entirely so no project on it can
   ever be billed: https://console.cloud.google.com/billing →
   select the billing account → **Account management** → **Close billing
   account** (only do this if no other project relies on it).

Note: downgrading to Spark disables push notifications from this app's
Cloud Function (no more `onNoiseEventCreated` fan-out) and removes Storage
support for audio clip uploads — Firestore and FCM keep working since they
don't require Blaze.

## Quick reference: commands used, in order

```
brew install firebase-cli
brew install --cask gcloud-cli        # if this rolls back, use the curl installer below
curl https://sdk.cloud.google.com | bash
exec -l $SHELL
gcloud init
firebase login
# .firebaserc created manually (see step 3)
# Firestore + Storage enabled via console (see step 4)
# Blaze plan enabled via console (see step 5)
cd functions && npm install && cd ..
echo 'SHEETS_WEBHOOK_URL=' > functions/.env
# firebase.json storage block added manually (see step 6)
firebase deploy --only firestore:rules
firebase deploy --only storage
firebase deploy --only functions
firebase functions:artifacts:setpolicy
# Sheet + Apps Script Web App created/deployed manually (see step 7)
echo 'SHEETS_WEBHOOK_URL=https://script.google.com/macros/s/XXXX/exec' > functions/.env
firebase deploy --only functions
curl -s -D - -X POST "https://script.google.com/macros/s/XXXX/exec" \
  -H "Content-Type: application/json" \
  -d '{"timestamp":"TEST","listenerName":"smoke-test","durationSec":3,"audioUrl":"https://example.com/test.wav"}' \
  -o /dev/null
curl -s "https://script.googleusercontent.com/macros/echo?user_content_key=..."
```
