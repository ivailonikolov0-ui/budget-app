# Бюджет / Budget — Android app for the monthly budget sheet

The app writes straight into your existing Google Sheet **"Бюджет / Budget 2026"**, in
the same layout you already use, and reads it back for the month view and analysis.

## What happens in the sheet

| When | What the script does |
|---|---|
| You save an entry | It goes into the tab of the entry's date, e.g. **"Октомври 2026"**. The date defaults to today on the phone. |
| First entry (or the 1st) of a new month | A new tab, e.g. **"Ноември 2026"**, is copied from the previous month: section headers, reasons and income sources are kept, amounts are cleared, and **Кредит** (column T) is carried over once. |
| First entry of a new year | A new file **"Бюджет / Budget 2027"** is created in the same Drive folder, starting with **"Януари 2027"**. |
| Every month tab | A **Баланс** block appears right of Приходи (columns AA:AB). It shows the previous month's leftover, income, bills, and this month's leftover, plus a total per section. |

How entries are written:
- **Two-column sections** (Сметки дом, Сметки Дружба, Сметки Ади, Сметки Иво, Кола, Лични разходи…, Общи разходи): reason in the left column, amount in the right.
  - If the reason already exists (e.g. "Ток"), the amount goes next to it.
  - If that reason already has an amount, the two are added up. The cell shows `=45.2+4.8`, so you can still see both.
  - A new reason goes on the next free row.
- **Пазар, Лего, Мъри**: the amount is added to the end of the list.
- **Приходи**: choose Ади or Иво and a source (Заплата, Ваучери…). The same rules apply, and the "Общо …" totals update. If the block runs out of rows, the script inserts a row inside the Приходи block only.
- **Leftover formula**: leftover this month = leftover last month + income − bills. Bills include everything in sections A–T, Кредит included.
  - Within one file, "leftover last month" is a live link to the previous tab.
  - For January it is copied from December of the previous file, and updated again whenever December changes.
  - October 2026 has no previous tab, so AB2 starts at 0. Type your real leftover into **AB2** of "Октомври 2026" if you want it counted.

## App screens

- **Add**: choose Expense or Income, type the amount, and tap a category. Then pick the reason from the **dropdown**, which lists the reasons already in this month's sheet with the amount entered so far, or type a new one. Press Save. The entry shows immediately and is uploaded in the background. With no internet it waits on the phone and is sent later.
- **Month**: shows the balance at the top, then income and every section with its total. Tap a section to open it. Tap an amount to **edit** or **remove** it. Tap a reason that has no amount yet (e.g. "Ток — add") to fill it in.
- **Analysis**: shows the balance, where the money went (share per section and change vs last month), income per person, and income vs bills for the last months.
- **⚙ Settings**: the sheet connection.

---

## 1. Install the script in the sheet (once, ~5 min)

1. Open **"Бюджет / Budget 2026"** → **Extensions → Apps Script**.
2. Delete the sample code, paste all of `apps-script/Code.gs`, and press 💾.
3. Choose the function **setup** in the toolbar and press **Run**. Allow the permissions it asks for (Sheets, Drive, triggers).
   - Google warns that the app is unverified. It's your own script, so choose *Advanced → Go to …*.
   - `setup` adds the Баланс block to "Октомври 2026" and schedules the monthly tab creation.
4. Open **Execution log** and copy the **secret key**. It is also under ⚙ Project Settings → Script properties → `SECRET`.
5. **Deploy → New deployment** → ⚙ type **Web app**:
   - Execute as: **Me**
   - Who has access: **Anyone** (the secret key still protects every request)
6. Copy the **Web app URL** (it ends in `/exec`).

> Changing `Code.gs` later: Deploy → Manage deployments → ✏ → Version: *New version*. The URL stays the same.
> Keep the script in the 2026 file. It creates and manages the 2027+ files from there.

## 2. Get the APK

Every push to `main` makes GitHub Actions build a signed APK (≈5 min):
- **Actions tab:** the run's artifact is **Budget-<version>**.
- **`apk` branch:** always holds the newest `Budget-<version>.apk`.

Every build is signed with the same key (`keystore/budget.jks`), so a new version installs over the old one.
- Keep the repository **private**.
- The very first install of a build signed with this key needs the old app uninstalled once.

Android Studio also works: **Build → Build APK(s)**, which uses the same key.

## 3. Install and connect

1. Open the APK on the phone. Allow *Install unknown apps* when Android asks.
2. Settings opens on first start. Paste the URL and secret key, then press **Save and connect**.

## If the app can't reach the sheet

1. Open ⚙ Settings and tap **Test connection**. Each line says what worked (✓) and what didn't (✗), with the fix.
2. The URL must be the **Web app URL ending in `/exec`**. Copy it with **Копиране** under *URL адрес* in Deploy → Manage deployments.
   - You can also paste just the Deployment ID; the app builds the URL from it.
   - The `/dev` test URL does not work.
3. Open the same URL in the phone's browser. It should say *"Budget backend is running."*
4. After any change to `Code.gs`, deploy a **New version**: Deploy → Manage deployments → ✏ → Version: *New version* → Deploy.
5. Apps Script → **Executions** lists every request that reached the script. If nothing appears when the app syncs, the request was stopped before the script ran, which points to the URL, access or network.

The app says "Offline" only when the phone has no connection. Any other problem is shown with its cause, and your unsent entries stay queued until it's fixed.

## Rules to keep the sheet and app in step

- Keep the **row 1 headers**. Their 2-column vs 1-column merges tell the script which sections have reasons.
- Keep **"Приходи"**, the person names in row 3, and the **"Общо …"** rows.
- Keep tab names in the form **"Месец ГГГГ"** (e.g. "Ноември 2026").
- You can add a new section in row 1, left of Приходи. The app picks it up automatically.
- If you edit the sheet while the phone holds an edit for the same cell, the app refuses that edit and asks you to refresh.

## Speed

- One request brings the current month and the 12 before it, so switching months and Analysis need no waiting.
- The script keeps a cached copy of past months, so it only reads the current month from the sheet on each sync.
  - The cache of a month and every month after it is cleared when you change it from the app or edit that tab by hand in the 2026 file.
- ⟳ in the app re-reads everything from the sheet. Use it after editing a 2027+ file by hand or renaming tabs.
- An entry marked "↑ uploading" can be edited once the upload finishes.

## Technical notes

- Kotlin + Jetpack Compose (Material 3), Android 8.0+, no third-party libraries.
- The phone keeps the last loaded month and unsent changes in its private storage.
- Each change carries an id, so a retry after a dropped connection is never written twice.
- The debug APK is signed with a debug key, which is fine for personal use.
