package com.arata.yukarilauncher.feature.mod.modloader

class ForgeBuildVersion private constructor(
    val major: Int,
    val minor: Int,
    val build: Int,
    val revision: Int
) : Comparable<ForgeBuildVersion> {
    companion object {
        fun parse(versionString: String): ForgeBuildVersion {
            val parts = versionString.split('.', '-').mapNotNull { it.toIntOrNull() }
            return ForgeBuildVersion(
                parts.getOrElse(0) { 0 },
                parts.getOrElse(1) { 0 },
                parts.getOrElse(2) { 0 },
                parts.getOrElse(3) { 0 }
            )
        }
    }

    override fun compareTo(other: ForgeBuildVersion): Int {
        return compareValuesBy(
            this, other,
            { it.major },
            { it.minor },
            { it.build },
            { it.revision }
        )
    }

    override fun toString(): String {
        return "$major.$minor.$build.$revision"
    }
}
