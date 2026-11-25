package dev.yesil.launchsoul.core

/** Is this window of the phone's own launcher its recent apps screen? Decided by class name and window title only. */
fun looksLikeRecents(className: String, title: String): Boolean {
    val c = className.lowercase()
    if ("recent" in c || "overview" in c || "taskview" in c) return true
    val t = fold(title)
    return listOf("recent app", "recents", "overview", "son uygulama", "son kullan", "yakin zamanda").any { it in t }
}

/** Is this the launcher's list of all apps? Decided by the window title only. */
fun looksLikeDrawer(title: String): Boolean {
    val t = fold(title)
    return listOf("apps list", "app list", "all apps", "uygulama listesi", "uygulamalar listesi", "tum uygulamalar").any { it in t }
}
