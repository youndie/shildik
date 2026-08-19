package ru.workinprogress.shildik.core.feature.admin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.workinprogress.shildik.core.port.ClientRepository
import ru.workinprogress.shildik.core.port.TenantRepository
import ru.workinprogress.shildik.crypto.Secrets

/**
 * Access to the management contour (research §R8).
 *
 * While the system has no admin client at all, a bootstrap token is accepted: otherwise there is
 * nothing to create the first administrator with — that would take an administrator. As soon as an
 * admin client exists, the token **stops working**: a long-lived hardcoded secret in a manifest is
 * exactly the trouble this device usually gets blamed for.
 *
 * The "is there an administrator" check goes to storage on every request and is not cached: raise a
 * second pod and it must learn that an administrator already exists, otherwise bootstrap would come
 * back to life after a restart.
 */
class AdminAccess(
    private val bootstrapToken: String,
    private val tenants: TenantRepository,
    private val clients: ClientRepository,
) {
    private val mutex = Mutex()

    /** A client with this role is the administrator; the contour tells no other roles apart. */
    suspend fun isBootstrapPhase(): Boolean =
        mutex.withLock {
            tenants.list().none { tenant -> clients.list(tenant.id).any { ADMIN_ROLE in it.roles } }
        }

    suspend fun accepts(presented: String?): Boolean {
        if (presented.isNullOrBlank()) return false
        if (!isBootstrapPhase()) return false
        return Secrets.matches(bootstrapToken, presented)
    }

    companion object {
        const val ADMIN_ROLE = "shildik:admin"
    }
}
