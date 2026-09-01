package com.localphotoai.photomanager.tools

import com.localphotoai.photomanager.domain.person.ClusteringResult
import com.localphotoai.photomanager.domain.person.ExistingClusterCentroid
import com.localphotoai.photomanager.domain.person.FaceEmbeddingForClustering
import com.localphotoai.photomanager.domain.person.PersonMember
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.person.PersonWithStats
import com.localphotoai.photomanager.domain.photo.IndexingProgress
import com.localphotoai.photomanager.domain.photo.Photo
import com.localphotoai.photomanager.domain.search.PhotoSearchFilter
import com.localphotoai.photomanager.domain.search.PhotoSortOrder
import com.localphotoai.photomanager.domain.search.SearchPhotosUseCase
import com.localphotoai.photomanager.domain.search.SearchRepository
import com.localphotoai.photomanager.domain.tool.ToolCall
import com.localphotoai.photomanager.domain.tool.ToolName
import com.localphotoai.photomanager.domain.tool.ToolOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testPhoto(id: Long) = Photo(
    mediaStoreId = id, uri = "content://$id", filename = "$id.jpg", mimeType = "image/jpeg",
    sizeBytes = 100L, width = 10, height = 10, dateAddedMs = 1L, dateModifiedMs = 1L,
    dateTakenMs = null, orientationDegrees = 0, latitude = null, longitude = null,
    lastIndexedAtMs = 1L, indexError = null,
)

private class SearchToolFakeSearchRepository(private val results: List<Photo>) : SearchRepository {
    var lastFilter: PhotoSearchFilter? = null
    override fun observeSearchResults(filter: PhotoSearchFilter) = flowOf(androidx.paging.PagingData.empty<Photo>())
    override suspend fun fetchOnce(filter: PhotoSearchFilter, limit: Int): List<Photo> {
        lastFilter = filter
        return results
    }
}

private class SearchToolFakePersonRepository(private val people: List<PersonWithStats>) : PersonRepository {
    override fun observePeopleWithStats(): Flow<List<PersonWithStats>> = flowOf(people)
    override fun observeMembers(personId: Long): Flow<List<PersonMember>> = flowOf(emptyList())
    override suspend fun fetchFacesNeedingClustering(): List<FaceEmbeddingForClustering> = emptyList()
    override suspend fun fetchExistingClusters(): List<ExistingClusterCentroid> = emptyList()
    override suspend fun applyClusteringResult(faces: List<FaceEmbeddingForClustering>, result: ClusteringResult) {}
    override suspend fun namePerson(personId: Long, name: String?) {}
    override suspend fun mergePersons(sourcePersonId: Long, targetPersonId: Long) {}
    override suspend fun splitFaceIntoNewPerson(faceId: Long): Long = 0
    override suspend fun markFaceIncorrect(faceId: Long) {}
    override fun observeClusteringProgress(): Flow<IndexingProgress> = flowOf(IndexingProgress.IDLE)
    override suspend fun updateClusteringProgress(progress: IndexingProgress) {}
}

private fun person(id: Long, name: String?) = PersonWithStats(
    id = id, name = name, representativePhotoUri = null, createdAt = 0L,
    clusterAlgoVersion = 1, photoCount = 0, faceCount = 0, averageConfidence = 1f,
)

class SearchPhotosToolTest {

    @Test
    fun `resolves a matching person name case-insensitively`() = runBlocking {
        val searchRepository = SearchToolFakeSearchRepository(listOf(testPhoto(1)))
        val personRepository = SearchToolFakePersonRepository(listOf(person(7L, "Rahul")))
        val tool = SearchPhotosTool(SearchPhotosUseCase(searchRepository), personRepository)

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, people = listOf("rahul")))

        assertTrue(result is ToolOutcome.Photos)
        assertEquals(setOf(7L), searchRepository.lastFilter?.personIds)
    }

    @Test
    fun `returns an explicit error when a person name doesn't resolve`() = runBlocking {
        val searchRepository = SearchToolFakeSearchRepository(emptyList())
        val personRepository = SearchToolFakePersonRepository(listOf(person(7L, "Priya")))
        val tool = SearchPhotosTool(SearchPhotosUseCase(searchRepository), personRepository)

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, people = listOf("Rahul")))

        assertTrue(result is ToolOutcome.Error)
        assertTrue((result as ToolOutcome.Error).message.contains("Rahul"))
    }

    @Test
    fun `supports a person-less query for size sorting`() = runBlocking {
        val searchRepository = SearchToolFakeSearchRepository(listOf(testPhoto(1), testPhoto(2)))
        val personRepository = SearchToolFakePersonRepository(emptyList())
        val tool = SearchPhotosTool(SearchPhotosUseCase(searchRepository), personRepository)

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, sortBy = "largest"))

        assertTrue(result is ToolOutcome.Photos)
        assertEquals(2, (result as ToolOutcome.Photos).photos.size)
        assertEquals(PhotoSortOrder.LARGEST, searchRepository.lastFilter?.sortBy)
    }

    @Test
    fun `rejects an invalid sortBy value`() = runBlocking {
        val tool = SearchPhotosTool(
            SearchPhotosUseCase(SearchToolFakeSearchRepository(emptyList())),
            SearchToolFakePersonRepository(emptyList()),
        )

        val result = tool.execute(ToolCall(tool = ToolName.SEARCH_PHOTOS, sortBy = "biggest"))

        assertTrue(result is ToolOutcome.Error)
    }
}
