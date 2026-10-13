"""Writes the keyboard's icon resources (Material icon paths) into app/src/main/res. Run once; the output is committed."""
import os

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "res")
V = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="%s"/>
</vector>
"""
ICONS = {
    "ic_mic": "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
    "ic_backspace": "M22,3H7C6.31,3 5.77,3.35 5.41,3.88L0,12l5.41,8.11C5.77,20.64 6.31,21 7,21h15c1.1,0 2,-0.9 2,-2V5C24,3.9 23.1,3 22,3zM22,19H7.07L2.4,12l4.66,-7H22V19zM10.41,17L14,13.41L17.59,17L19,15.59L15.41,12L19,8.41L17.59,7L14,10.59L10.41,7L9,8.41L12.59,12L9,15.59z",
    "ic_return": "M19,7v4H5.83l3.58,-3.59L8,6l-6,6 6,6 1.41,-1.41L5.83,13H21V7z",
    "ic_search": "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    "ic_send": "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z",
    "ic_arrow_forward": "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z",
    "ic_check": "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
    "ic_close": "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,12 17.59,19 19,17.59 13.41,12z",
    "ic_undo": "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
    "ic_emoji": "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM15.5,11c0.83,0 1.5,-0.67 1.5,-1.5S16.33,8 15.5,8 14,8.67 14,9.5s0.67,1.5 1.5,1.5zM8.5,11c0.83,0 1.5,-0.67 1.5,-1.5S9.33,8 8.5,8 7,8.67 7,9.5 7.67,11 8.5,11zM12,17.5c2.33,0 4.31,-1.46 5.11,-3.5L6.89,14c0.8,2.04 2.78,3.5 5.11,3.5z",
    "ic_settings": "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
    "ic_shift_on": "M12,3.2L3.2,12.4H8.1V20.2H15.9V12.4H20.8Z",
    "ic_caps_lock": "M12,2.6L3.6,11.2H8.1V15.4H15.9V11.2H20.4ZM8.1,17.6H15.9V20.6H8.1Z",
}
FILES = {f"drawable/{k}.xml": V % v for k, v in ICONS.items()}
FILES["drawable/ic_shift.xml"] = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="1.7" android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="M12,3.8L4.6,11.6H8.8V19.4H15.2V11.6H19.4Z"/>
</vector>
"""
FILES["drawable/ic_launcher_foreground.xml"] = """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF9DB1FF" android:pathData="M54,30c-5,0 -9,4 -9,9v14c0,5 4,9 9,9s9,-4 9,-9V39C63,34 59,30 54,30z"/>
    <path android:fillColor="#FFF1F1F4" android:pathData="M38,52h4c0,6.6 5.4,12 12,12s12,-5.4 12,-12h4c0,8.1 -6.1,14.8 -14,15.8V74h-4v-6.2C44.1,66.8 38,60.1 38,52z"/>
    <path android:fillColor="#FF5B7CFA" android:pathData="M36,77h36a3,3 0 0,1 0,6H36a3,3 0 0,1 0,-6z"/>
</vector>
"""
FILES["mipmap-anydpi-v26/ic_launcher.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_bg"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
"""

if __name__ == "__main__":
    for p, c in FILES.items():
        path = os.path.join(ROOT, p)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, "w", encoding="utf-8", newline="\n").write(c)
    print(len(FILES), "files written to", ROOT)
