package com.localphotoai.photomanager.domain.person

/** A person/cluster with the aggregate stats the People screen needs, computed at the data layer. */
data class PersonWithStats(
    val id: Long,
    val name: String?,
    val representativePhotoUri: String?,
    val createdAt: Long,
    val clusterAlgoVersion: Int,
    val photoCount: Int,
    val faceCount: Int,
    /** Average cluster-assignment confidence (cosine similarity to centroid at assignment time) across member faces. */
    val averageConfidence: Float,
)

/** One face belonging to a person, plus enough of its photo to show a thumbnail. */
data class PersonMember(
    val faceId: Long,
    val photoMediaStoreId: Long,
    val photoUri: String,
    val photoFilename: String,
)
