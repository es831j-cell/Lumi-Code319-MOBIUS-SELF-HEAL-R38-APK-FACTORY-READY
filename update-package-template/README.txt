LUMI LOCAL ZIP UPDATE FORMAT

1. Keep lumi-update.json at the root of the ZIP.
2. Put optional content files under payload/ and list each file in the manifest with its SHA-256 hash and approved target.
3. A manifest signature (lumi-update.sig) is optional for a user-selected local ZIP.
4. Content ZIPs may only update whitelisted preferences and Lumi-private avatar/asset/config targets.
5. A core ZIP must contain a newer compiled Lumi APK under payload/, declare it in files[], and identify it in an "apk" object. The APK must have package com.distressedelk.lumi and the same Lumi signing certificate.
6. Android still asks the user to approve installation of a core APK update.

IMPORTANT: Lumi cannot compile Android source code on the phone. A source-project ZIP is not a core update package. Core update ZIPs must contain a compiled APK.
