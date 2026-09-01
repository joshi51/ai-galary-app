package com.localphotoai.photomanager.data.database

import com.localphotoai.photomanager.data.database.dao.PersonDao
import com.localphotoai.photomanager.domain.person.ExistingClusterCentroid
import com.localphotoai.photomanager.domain.person.FaceEmbeddingForClustering
import com.localphotoai.photomanager.domain.person.PersonMember
import com.localphotoai.photomanager.domain.person.PersonWithStats

fun PersonDao.FaceForClusteringRow.toDomain(): FaceEmbeddingForClustering =
    FaceEmbeddingForClustering(faceId = faceId, vector = bytesToFloatArray(vector))

fun PersonDao.ExistingClusterRow.toDomain(): ExistingClusterCentroid =
    ExistingClusterCentroid(personId = personId, centroidSum = bytesToFloatArray(centroidSum))

fun PersonDao.PersonWithStatsRow.toDomain(): PersonWithStats = PersonWithStats(
    id = id,
    name = name,
    representativePhotoUri = representativePhotoUri,
    createdAt = createdAt,
    clusterAlgoVersion = clusterAlgoVersion,
    photoCount = photoCount,
    faceCount = faceCount,
    averageConfidence = averageConfidence ?: 0f,
)

fun PersonDao.PersonMemberRow.toDomain(): PersonMember = PersonMember(
    faceId = faceId,
    photoMediaStoreId = photoMediaStoreId,
    photoUri = photoUri,
    photoFilename = photoFilename,
)
