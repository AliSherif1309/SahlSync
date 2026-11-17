# Shared Sahl visual identity

All Sahl apps should share the same theme. Sahl Sync uses the existing palette
from `SahlTV-Launcher/common/src/main/java/com/aly/sahl/common/Ui.kt`.

| Token | Value | Use |
|---|---|---|
| Background top | `#0E1A24` | Top of background gradient |
| Background bottom | `#10261F` | Bottom of background gradient |
| Panel | `#13241F` | Rounded sections and dialogs |
| Main text | `#EEF3F1` | Readable primary content |
| Muted text | `#8FA39C` | Supporting copy |
| Gold | `#E3B85C` | Primary actions and focus |
| Dark | `#0B1E1A` | Text on gold |

Use rounded panels, consistent spacing, readable typography and 48 dp minimum
touch controls. Dialogs inherit the dark Sahl theme and gold accent. The shared
palette lives in `SahlTheme.java`, not scattered in feature screens.

Arabic will use licensed bundled Thmanyah Sans, with the same heading/body roles
as other Sahl apps. Do not redistribute private font build inputs or package an
unlicensed substitute under the Thmanyah name. This initial alpha is English.

Future theme changes should be coordinated across Sahl apps; this document is a
consistency rule, not a claim that every existing app has already been migrated.
