package com.localphotoai.photomanager.feature.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localphotoai.photomanager.domain.person.PersonMember
import com.localphotoai.photomanager.domain.person.PersonRepository
import com.localphotoai.photomanager.domain.person.PersonWithStats
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val personRepository: PersonRepository,
) : ViewModel() {

    val people: StateFlow<List<PersonWithStats>> = personRepository.observePeopleWithStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeMembers(personId: Long): Flow<List<PersonMember>> = personRepository.observeMembers(personId)

    fun onNamePerson(personId: Long, name: String?) {
        viewModelScope.launch { personRepository.namePerson(personId, name) }
    }

    fun onMergePersons(sourcePersonId: Long, targetPersonId: Long) {
        viewModelScope.launch { personRepository.mergePersons(sourcePersonId, targetPersonId) }
    }

    fun onSplitFace(faceId: Long) {
        viewModelScope.launch { personRepository.splitFaceIntoNewPerson(faceId) }
    }

    fun onMarkFaceIncorrect(faceId: Long) {
        viewModelScope.launch { personRepository.markFaceIncorrect(faceId) }
    }
}
