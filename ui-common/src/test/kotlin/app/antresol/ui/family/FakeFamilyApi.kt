package app.antresol.ui.family

import app.antresol.core.api.ApiException
import app.antresol.core.family.FamilyApi
import app.antresol.core.model.FamilyMember
import app.antresol.core.model.Progress
import kotlinx.coroutines.CompletableDeferred

/** A scripted [FamilyApi]: every call recorded; a call waits for its gate when one is set. */
class FakeFamilyApi : FamilyApi {
    val calls = ArrayList<String>()

    /** The members each item answers. */
    val answers = HashMap<Long, List<FamilyMember>>()
    var membersFailure: Exception? = null
    var membersGate: CompletableDeferred<Unit>? = null
    var adoptFailure: Exception? = null
    var adoptGate: CompletableDeferred<Unit>? = null

    override suspend fun members(itemId: Long): List<FamilyMember> {
        calls += "members $itemId"
        membersGate?.await()
        membersFailure?.let { throw it }
        return answers[itemId].orEmpty()
    }

    override suspend fun adopt(itemId: Long, userId: Long): Progress {
        calls += "adopt $itemId $userId"
        adoptGate?.await()
        adoptFailure?.let { throw it }
        val theirs = answers[itemId].orEmpty().firstOrNull { it.userId == userId } ?: throw ApiException(404, "Not found")
        return Progress(positionMs = theirs.positionMs, durationMs = theirs.durationMs, updatedAt = "2026-11-25T19:00:00Z")
    }
}
