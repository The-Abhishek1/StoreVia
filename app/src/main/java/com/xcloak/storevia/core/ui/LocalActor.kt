package com.xcloak.storevia.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.xcloak.storevia.core.access.Actor
import com.xcloak.storevia.core.access.Permission

/** The person using the app right now. Screens use it to hide what they may not do (the use cases enforce it for real). */
val LocalActor = staticCompositionLocalOf { Actor.LOCAL_OWNER }

@Composable
fun can(p: Permission): Boolean = LocalActor.current.can(p)
