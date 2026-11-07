package com.github.libretube.obj.update

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// YT: every field has a default so that a release without assets, a null body or a changed
// response shape still parses (the shared Json ignores unknown keys and coerces nulls).
@Serializable
@Parcelize
data class UpdateInfo(
    val name: String = "",
    @SerialName("tag_name") val tagName: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<UpdateAsset> = emptyList()
) : Parcelable

/** One file attached to a GitHub release (the APK, mapping.txt, ...). */
@Serializable
@Parcelize
data class UpdateAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    /** "sha256:<hex>", provided by GitHub for newer uploads; null for old ones. */
    val digest: String? = null
) : Parcelable
