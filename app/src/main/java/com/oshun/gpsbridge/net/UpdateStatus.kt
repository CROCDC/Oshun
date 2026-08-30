package com.oshun.gpsbridge.net

/**
 * Whether the phone in your hand is behind the newest published build.
 *
 * The app is sideloaded, so nothing but this asks the question — and the answer decides whether
 * the download button is on the screen at all. Not knowing is not the same as being up to date:
 * only a published build that is no newer than the installed one takes the button away, because
 * hiding it any other time would be claiming something nothing established.
 */
sealed interface UpdateStatus {

    /** A build worth downloading, or one that could not be ruled out. */
    data class Available(val build: Int?, val url: String) : UpdateStatus

    /** What is published is what is already installed. */
    data object UpToDate : UpdateStatus

    /** Nobody answered: no network, no DNS, GitHub rate-limiting us. */
    data object Unknown : UpdateStatus

    companion object {
        /** What the release lookup means to a phone running build [installed]. */
        fun of(apk: Apk?, installed: Int): UpdateStatus = when {
            apk == null -> Unknown
            // An APK whose name we cannot read is still an APK, and offering it beats
            // announcing an up-to-dateness nobody checked.
            apk.build == null -> Available(null, apk.url)
            apk.build > installed -> Available(apk.build, apk.url)
            else -> UpToDate
        }
    }
}
