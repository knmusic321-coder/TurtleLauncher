package com.endiq.turtlelauncher.event.value

/**
 * Posted when a background clip's sound setting changes. Subscribers apply the stored mute
 * flags to the players that are already running, so toggling the switch never restarts the
 * clip from its first frame.
 */
class VideoSoundChangeEvent
