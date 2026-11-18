package com.vighnesh1236.campusconnect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vighnesh1236.campusconnect.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

data class CampusState(
    val user: User? = null,
    val announcements: List<Announcement> = emptyList(),
    val events: List<CampusEvent> = emptyList(),
    val assignments: List<Assignment> = emptyList(),
    val students: List<User> = emptyList(),
    val eventRegistrations: List<EventRegistration> = emptyList(),
    val busy: Boolean = false,
    val authenticated: Boolean = SessionStore.token() != null,
    val restoringSession: Boolean = SessionStore.token() != null,
    val error: String? = null,
    val info: String? = null
)

class CampusViewModel : ViewModel() {
    private val api = ApiClient.create()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        CampusState(
            user = if (SessionStore.token() != null) {
                User(SessionStore.userId(), SessionStore.userName(), "", null, null, SessionStore.userRole())
            } else null
        )
    )
    val state = _state.asStateFlow()

    init {
        if (SessionStore.token() != null) refresh()
    }

    fun authenticate(email: String, password: String, name: String, roll: String, department: String, registering: Boolean) {
        if (_state.value.busy) return
        viewModelScope.launch {
            update { copy(busy = true, error = null) }
            try {
                val response = if (registering) {
                    api.register(RegisterRequest(name.trim(), email.trim(), password, roll.trim(), department.trim()))
                } else api.login(AuthRequest(email.trim(), password))
                SessionStore.save(response)
                update {
                    copy(
                        user = response.user, authenticated = true, restoringSession = false,
                        busy = false, info = "Welcome to CampusConnect"
                    )
                }
                refresh()
            } catch (error: Exception) {
                update { copy(busy = false, error = errorMessage(error)) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            update { copy(busy = true, error = null) }
            try {
                val profile = api.profile()
                val announcements = api.announcements()
                val events = api.events()
                val assignments = api.assignments()
                val students = if (profile.role == "admin") runCatching { api.students() }.getOrElse { emptyList() } else emptyList()
                val registrations = if (profile.role == "admin") {
                    runCatching { api.eventRegistrations() }.getOrElse { emptyList() }
                } else emptyList()
                update {
                    copy(
                        user = profile, announcements = announcements,
                        events = events, assignments = assignments, students = students,
                        eventRegistrations = registrations, busy = false, restoringSession = false
                    )
                }
            } catch (error: Exception) {
                if (error is HttpException && error.code() == 401) {
                    SessionStore.clear()
                    update {
                        copy(authenticated = false, restoringSession = false, user = null, busy = false, error = "Your session expired. Please sign in again.")
                    }
                } else update { copy(busy = false, restoringSession = false, error = errorMessage(error)) }
            }
        }
    }

    fun setAssignmentCompleted(id: Int, completed: Boolean) = perform("Assignment updated") {
        api.updateAssignmentStatus(id, mapOf("status" to if (completed) "completed" else "pending"))
        set(refreshData = "assignments")
    }

    fun setEventRegistered(event: CampusEvent, registered: Boolean) =
        perform(if (registered) "You're registered for ${event.title}" else "Registration cancelled") {
            if (registered) api.registerForEvent(event.id) else api.cancelEventRegistration(event.id)
            refresh()
        }

    fun saveProfile(name: String, roll: String, department: String) = perform("Profile saved") {
        val updated = api.updateProfile(ProfileRequest(name.trim(), roll.trim(), department.trim()))
        update { copy(user = updated) }
    }

    fun saveProfilePhoto(profilePhoto: String?) = perform("Profile photo updated") {
        val updated = api.updateProfilePhoto(ProfilePhotoRequest(profilePhoto))
        update { copy(user = updated) }
    }

    fun reportError(message: String) = update { copy(error = message) }

    fun saveAnnouncement(id: Int?, title: String, description: String, category: String) =
        perform("Announcement saved") {
            val body = CreateAnnouncement(title.trim(), description.trim(), category.trim())
            if (id == null) api.createAnnouncement(body) else api.updateAnnouncement(id, body)
            refresh()
        }

    fun deleteAnnouncement(id: Int) = perform("Announcement removed") {
        api.deleteAnnouncement(id)
        refresh()
    }

    fun saveEvent(id: Int?, title: String, description: String, date: String, time: String, venue: String) =
        perform("Event saved") {
            val body = CreateEvent(title.trim(), description.trim(), date.trim(), time.trim(), venue.trim())
            if (id == null) api.createEvent(body) else api.updateEvent(id, body)
            refresh()
        }

    fun deleteEvent(id: Int) = perform("Event removed") {
        api.deleteEvent(id)
        refresh()
    }

    fun saveAssignment(id: Int?, title: String, subject: String, description: String, deadline: String) =
        perform("Assignment saved") {
            val body = CreateAssignment(title.trim(), subject.trim(), description.trim(), deadline.trim())
            if (id == null) api.createAssignment(body) else api.updateAssignment(id, body)
            refresh()
        }

    fun deleteAssignment(id: Int) = perform("Assignment removed") {
        api.deleteAssignment(id)
        refresh()
    }

    fun logout() {
        SessionStore.clear()
        _state.value = CampusState(authenticated = false, restoringSession = false, error = "Signed out")
    }

    fun dismissMessages() = update { copy(error = null, info = null) }

    private fun set(refreshData: String) {
        if (refreshData == "assignments") viewModelScope.launch {
            runCatching { api.assignments() }.onSuccess { values ->
                update { copy(assignments = values) }
            }.onFailure { error -> update { copy(error = errorMessage(error)) } }
        }
    }

    private fun perform(message: String, action: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            update { copy(busy = true, error = null, info = null) }
            try {
                action()
                update { copy(busy = false, info = message) }
            } catch (error: Exception) {
                update { copy(busy = false, error = errorMessage(error)) }
            }
        }
    }

    private fun update(transform: CampusState.() -> CampusState) {
        _state.value = _state.value.transform()
    }

    private fun errorMessage(error: Throwable): String {
        if (error is HttpException) {
            val message = runCatching {
                com.google.gson.JsonParser.parseString(error.response()?.errorBody()?.string())
                    .asJsonObject.get("message").asString
            }.getOrNull()
            return message ?: "Request failed (${error.code()})"
        }
        return if (error is java.io.IOException) {
            "Could not reach the CampusConnect server. Check your connection and API URL."
        } else error.message ?: "Something went wrong. Please try again."
    }
}
