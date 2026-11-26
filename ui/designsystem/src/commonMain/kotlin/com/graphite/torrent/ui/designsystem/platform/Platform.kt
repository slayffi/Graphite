package com.graphite.torrent.ui.designsystem.platform

enum class PlatformKind { ANDROID, DESKTOP }

/** The platform the UI runs on: Android shows bottom sheets, desktop shows modal panels and hover states. */
expect val currentPlatform: PlatformKind

/** Lets tests render the phone layout on the desktop (null = the real platform). */
var platformOverride: PlatformKind? = null

val isDesktop: Boolean get() = (platformOverride ?: currentPlatform) == PlatformKind.DESKTOP
