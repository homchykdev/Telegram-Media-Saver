<div align="center">
  <img src="app/src/main/res/drawable/app_icon_foreground_1783869621697.jpg" width="20%" /> <br>
  <h1>Media Saver</h1>
  <p>Android app that saves TikTok videos and photo carousels directly to your Telegram group — no login required.</p>
</div>

<div align="center">

[English](README.md) &nbsp;|&nbsp; [Русский](README-ru.md)

[![GitHub Releases](https://img.shields.io/badge/Download-APK-blue?style=for-the-badge&logo=android&logoColor=white)](https://github.com/YOUR_USERNAME/YOUR_REPO/releases/latest)
![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?style=for-the-badge&logo=android&logoColor=white)
![Version](https://img.shields.io/badge/version-1.1.0-blue?style=for-the-badge)

</div>

---

## ✨ Features

- **Share in one tap** — hit Share in TikTok, pick Media Saver, confirm. Done.
- **Videos & carousels** — downloads single videos or full photo carousels (sent as albums).
- **Straight to Telegram** — uploads to your group via Bot API with title and a link back to the post.
- **Runs in the background** — WorkManager keeps the job alive even when the screen is off.
- **Download history** — every job is saved locally with thumbnail, status, and a link to the Telegram message.
- **No TikTok login needed** — works with any public link.

---

## 📲 Installation

1. Go to the [**Releases**](https://github.com/YOUR_USERNAME/YOUR_REPO/releases/latest) page.
2. Download the latest `.apk` file.
3. Open the APK on your phone and tap **Install**.  
   *(Allow "Install from unknown sources" if prompted — Settings → Apps → Special app access.)*

**Requires Android 8.0 or higher.**

---

## ⚙️ Setup (one-time)

The app needs a Telegram bot and a group to send files to.

### 1. Create a bot

1. Open [@BotFather](https://t.me/BotFather) in Telegram.
2. Send `/newbot`, pick a name and username.
3. Copy the **token** (looks like `123456789:AAExxxxxx…`).

> [!CAUTION]
> The token is your bot's password. Never share it. If leaked, revoke it via `/revoke` in @BotFather.

### 2. Add the bot to a group

1. Create or open a Telegram **group** (not a channel or direct chat).
2. Add your bot as a member.
3. Make it an **admin** with at least the **Post Messages** permission — otherwise uploads will fail with a `403` error.

### 3. Get the group's Chat ID

The Chat ID always starts with `-100` (e.g., `-1001234567890`). Easiest way to find it:

- **Share the group** to [@username_to_id_bot](https://t.me/username_to_id_bot) — open the bot, tap the attachment icon, choose a chat, select your group — it will instantly reply with the Chat ID.

### 4. Enter the credentials in the app

1. Open **Media Saver** → tap the ⚙️ gear icon.
2. Under **Telegram API**, paste:
   - **Bot Token** — from step 1.
   - **Group ID** — from step 3 (starts with `-100`).
3. Tap **Test Connection**.
   - ✅ *Connected* — you're all set.
   - ❌ `Unauthorized` — wrong token.
   - ❌ `chat not found` — wrong Group ID or bot not in the group.
   - ❌ `not enough rights` — bot is not an admin.
4. Tap **Save**.

---

## 🚀 How to Use

| Method | Steps |
|---|---|
| **Share from TikTok** *(recommended)* | Tap Share in TikTok → select **Media Saver** → confirm |
| **Paste a link** | Open Media Saver → paste a TikTok URL → tap the download button |
| **Open link directly** | Tap a `tiktok.com` link → choose **Open with Media Saver** |

The app automatically extracts a TikTok link from any shared text, even if it includes extra content.

---

## ⚠️ Known Limitations

| Issue | Details |
|---|---|
| **50 MB video limit** | Telegram Bot API caps videos at 50 MB. The app retries once at lower quality; if still over the limit, it fails with a clear error. Videos are not split or re-encoded. |
| **Third-party API** | Media resolution depends on [tikwm.com](https://tikwm.com). If the service is down or rate-limited, downloads will fail. |
| **Settings not backed up** | The bot token is stored encrypted and intentionally excluded from Android cloud backup and device transfers. You'll need to re-enter it on a new device. |
| **Public posts only** | Only publicly accessible TikTok links are supported — no private accounts or login. |

---

## 🔧 Troubleshooting

| Symptom | Fix |
|---|---|
| "Bot token or Group ID not set" | Go to Settings → Telegram API and fill in both fields. |
| `Unauthorized` | Wrong token — check it in @BotFather and re-paste. |
| `chat not found` | Wrong Group ID or bot is not a member of the group. |
| `not enough rights` / 403 | Grant the bot the **Post Messages** admin permission. |
| "File too large (>50 MB)" | Telegram Bot API hard limit — see above. |
| Download doesn't start | Check your internet connection and make sure notifications are allowed (Android 13+). |
