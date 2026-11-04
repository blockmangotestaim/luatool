# LuaTool

A small GameGuardian-style tool: pick a process, run `.lua` scripts against its memory, floating overlay UI.
Pure Java (no native code, no root), so it runs on any ABI incl. 32-bit armeabi-v7a, and can be cloned into
a virtual environment (BlackBox) like any normal app.

## How to use
1. Install the APK, open it, tap "Overlay permission" (inside a clone: allow "Display over other apps" for the host app in system Settings).
2. "Start floating menu" -> a draggable "Lua" bubble appears over other apps. Tap it to open the panel.
3. "Import .lua file" (file picker) or "Paste script from clipboard".
3b. Or drop .lua files into /sdcard/LuaTool and press Refresh (needs storage permission).
4. In the panel (drag the title to move it, drag the Size grip to resize; size is remembered per orientation): pick a process (name, pid, MB used), pick a script, Run. Stop ends the script and un-freezes values.

Only processes of the same Android user id are visible. That is exactly why it works without root **inside a virtual
environment**: the cloned game and this tool both run under the host app's uid.

## Lua API (subset of GameGuardian's `gg`)
UI: alert, toast, choice, multiChoice, prompt (types: text, number, path, checkbox), isVisible, setVisible, showUiButton, hideUiButton, isClickedUiButton
Memory: setRanges, getRanges, getRangesList, searchNumber, refineNumber, getResultsCount, getResults, clearResults, editAll, getValues, setValues
Freeze: addListItems (freeze=true), removeListItems, clearList
Misc: sleep, getTargetInfo, getTargetPackage, processPause, processResume, copyText, getFile, skipRestoreState
Constants: TYPE_BYTE/WORD/DWORD/FLOAT/QWORD/DOUBLE, REGION_*, SIGN_EQUAL/NOT_EQUAL/GREATER_OR_EQUAL/LESS_OR_EQUAL

Not implemented: fuzzy search, TYPE_XOR/AUTO, pointer search, gg.makeRequest, saved-list UI.
Search values accept `100`, a range `10~20`, or a GG-style group like `0W;65535W;0~255B` (types B/W/D/F/Q/E; the values must follow each other directly, in order; `::distance` is not supported). Lua version is 5.2 (LuaJ): no `|`, `&`, `//` operators (use `+` or `bit32`).
`luajava` is disabled. Scripts can still use io/os, so only run scripts you trust.

LuaJ (MIT) is vendored under app/src/main/java/org/luaj - see LICENSE-luaj.txt.
