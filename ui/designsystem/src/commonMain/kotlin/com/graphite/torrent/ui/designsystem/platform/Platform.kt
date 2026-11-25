package com.graphite.torrent.ui.designsystem.platform

enum class PlatformKind { ANDROID, DESKTOP }

/** The platform the UI runs on: Android shows bottom sheets, desktop shows modal panels and hover states. */
expect val currentPlatform: PlatformKind

val isDesktop: Boolean get() = currentPlatform == PlatformKind.DESKTOP
