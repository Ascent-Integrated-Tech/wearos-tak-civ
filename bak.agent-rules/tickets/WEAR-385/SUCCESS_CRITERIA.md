# WEAR-385 Success Criteria (Logcat)

## Filter Tags
Use these tags to filter relevant logs:
- `WT/CDX/DR`
- `WT/CDX/TSEDS`
- `WT/CDX/TSSR`
- `WT/CDX/TSM`
- `WT/CDX/CAS`

Example logcat filter:
```bash
adb logcat -s WT/CDX/DR WT/CDX/TSEDS WT/CDX/TSSR WT/CDX/TSM WT/CDX/CAS
```

## Required Evidence of Success
A run is considered successful when logcat shows:
- `WT/CDX/TSM` indicates migration check started and either:
  - migration skipped because encrypted store is populated, or
  - migration performed with legacy list length and legacy key cleared, or
  - no legacy list found.
- `WT/CDX/TSEDS` shows encrypted read/write activity for `tak_server_list_json`.
- `WT/CDX/TSSR` shows `setTakServerListJson` invoked with a non-zero length when changes are saved.
- `WT/CDX/DR` shows TAK server list read/write activity (list size and JSON length).
- `WT/CDX/CAS` shows companion settings application calling `storeTakServerList` when a companion payload is applied.

## Notes
- Warnings or errors from other tags do not affect this criterion, as long as the required WT/CDX tags appear as described above.
