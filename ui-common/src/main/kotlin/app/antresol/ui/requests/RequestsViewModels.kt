package app.antresol.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.antresol.core.requests.RequestsApi

/** «My Requests» of a kid profile: loads the list when the screen opens. */
class MyRequestsViewModel(api: RequestsApi) : ViewModel() {
    val requests = MyRequests(api, viewModelScope).also { it.load() }
}

/** «Requests» of an administrator: the kids' requests to answer. */
class RequestsViewModel(api: RequestsApi) : ViewModel() {
    val requests = RequestsToAnswer(api, viewModelScope).also { it.load() }
}

/** The count of requests waiting for an administrator, for the badge of the entry to «Requests». */
class PendingRequestsViewModel(api: RequestsApi) : ViewModel() {
    val pending = PendingRequests(api, viewModelScope).also { it.refresh() }
}
