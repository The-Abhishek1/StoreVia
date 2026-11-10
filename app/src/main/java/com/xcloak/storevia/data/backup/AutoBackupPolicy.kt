package com.xcloak.storevia.data.backup

import com.xcloak.storevia.data.prefs.StorageMode

internal fun shouldCreateLocalAutoBackup(storageMode: StorageMode?): Boolean =
    storageMode == StorageMode.LOCAL
