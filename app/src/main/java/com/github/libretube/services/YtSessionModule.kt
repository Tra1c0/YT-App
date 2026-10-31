package com.github.libretube.services

/**
 * YT (package D): the time box ("Heute habe ich … Minuten"): its end points and holding autoplay
 * once the time is used up. Empty until package D fills it, so every hook keeps its default.
 */
object YtSessionModule : YtServiceModule
