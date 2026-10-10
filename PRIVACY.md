# Privacy

English | [Japanese](PRIVACY.ja.md)

QR Repair processes QR codes locally on your Android device. The app does not request the Internet permission.

## Saved work

Import automatically saves your work and its source image, including images created from matrix text, before you press **Save** or **Repair**. Saving or repairing updates that work.

At most 20 saved sessions are kept. New imports count toward this limit. When it is exceeded, the least recently saved work and its source image are automatically deleted. The limit also applies on startup.

To delete work, touch and hold its entry in **Saved work** (**Recent work** in Classic), then confirm deletion. **Delete all** removes all saved work.

Work and source images stay in app-private storage, excluded from Android cloud backup and device transfer. These storage protections do not encrypt QR content.

## Temporary files and sharing

| Data | Handling |
| --- | --- |
| Temporary camera images | Kept in app-private storage and deleted after use, including when import fails. Capture and crop files left behind are removed on startup once at least 24 hours have passed since their last modification. |
| Copied content | **Copy content** sends the complete recovered text to the system clipboard. Android controls how long clipboard content is kept. |
| Exported images | Saved outside app-private storage only when you choose to export. The destination's sharing and backup settings apply. |

Recovered text is displayed and compared in full, without masking or redaction. Recovered web addresses (URLs) never open automatically.

See [Security](SECURITY.md) for file validation and reporting security or privacy issues.
