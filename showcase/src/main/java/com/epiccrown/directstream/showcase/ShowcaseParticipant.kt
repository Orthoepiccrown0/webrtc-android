package com.epiccrown.directstream.showcase

import android.content.Context

/** Stable demo identity; production hosts supply a name from their own user model. */
internal object ShowcaseParticipant {
    private val names =
        listOf(
            "MapleFox",
            "CedarOwl",
            "RiverOtter",
            "WillowBear",
            "CoralFinch",
            "AmberLynx",
            "SilverHeron",
            "CloudPanda",
            "SageRobin",
            "OceanWolf",
        )

    @Synchronized
    fun name(context: Context): String {
        val prefs = context.getSharedPreferences("showcase-participant", Context.MODE_PRIVATE)
        return prefs.getString("name", null)
            ?: names.random().also {
                check(prefs.edit().putString("name", it).commit())
            }
    }
}
