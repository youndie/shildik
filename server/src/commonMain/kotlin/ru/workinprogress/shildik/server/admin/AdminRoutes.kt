package ru.workinprogress.shildik.server.admin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.core.Koin
import ru.workinprogress.shildik.core.feature.admin.AdminAccess
import ru.workinprogress.shildik.core.feature.admin.AlreadyExists
import ru.workinprogress.shildik.core.feature.admin.CreateClientUseCase
import ru.workinprogress.shildik.core.feature.admin.CreateTenantUseCase
import ru.workinprogress.shildik.core.feature.admin.DeleteClientUseCase
import ru.workinprogress.shildik.core.feature.admin.ImportUserUseCase
import ru.workinprogress.shildik.core.feature.admin.ListClientsUseCase
import ru.workinprogress.shildik.core.feature.admin.ListKeysUseCase
import ru.workinprogress.shildik.core.feature.admin.ListTenantsUseCase
import ru.workinprogress.shildik.core.feature.admin.ListUsersUseCase
import ru.workinprogress.shildik.core.feature.admin.NotFound
import ru.workinprogress.shildik.core.feature.admin.ReencryptKeysUseCase
import ru.workinprogress.shildik.core.feature.admin.RetireKeyUseCase
import ru.workinprogress.shildik.core.feature.admin.RotateClientSecretUseCase
import ru.workinprogress.shildik.core.feature.admin.RotateKeyUseCase
import ru.workinprogress.shildik.core.feature.admin.SetClientAudiencesUseCase
import ru.workinprogress.shildik.core.feature.admin.SetClientRolesUseCase
import ru.workinprogress.shildik.core.feature.admin.SetClientSecretUseCase
import ru.workinprogress.shildik.core.feature.admin.SetPasswordUseCase
import ru.workinprogress.shildik.core.model.ExternalIdentity
import ru.workinprogress.shildik.shared.AdminResource
import ru.workinprogress.shildik.shared.ClientView
import ru.workinprogress.shildik.shared.ClientWithSecret
import ru.workinprogress.shildik.shared.CreateClientRequest
import ru.workinprogress.shildik.shared.CreateTenantRequest
import ru.workinprogress.shildik.shared.ErrorView
import ru.workinprogress.shildik.shared.ExternalIdentityView
import ru.workinprogress.shildik.shared.ImportSecretRequest
import ru.workinprogress.shildik.shared.ImportUserRequest
import ru.workinprogress.shildik.shared.ImportedUserView
import ru.workinprogress.shildik.shared.KeyView
import ru.workinprogress.shildik.shared.ReencryptView
import ru.workinprogress.shildik.shared.SetAudiencesRequest
import ru.workinprogress.shildik.shared.SetPasswordRequest
import ru.workinprogress.shildik.shared.SetRolesRequest
import ru.workinprogress.shildik.shared.TenantView
import ru.workinprogress.shildik.shared.UserView

/**
 * The management contour.
 *
 * It lives **only** on the management port (api/endpoint-admin.md §1): on the public network these
 * routes do not exist at all, and that property is provided by separate engines rather than by a
 * check inside a handler.
 *
 * The URLs come from `:shared`: the CLI uses the same description, so the two cannot drift apart.
 */
fun Application.adminRoutes(koin: Koin) {
    val access = koin.get<AdminAccess>()

    routing {
        // The gate covers the whole `/admin` branch rather than each handler: forgetting it on
        // one route is a matter of time, and the price of forgetting is at its highest here. The
        // resources are registered alongside, at the same level: they carry the `/admin` path
        // themselves (AdminResource), and Ktor reuses the route node it already created — so the
        // plugin applies to them too. That the gate is in place is checked by "without a token the
        // management contour does not answer" in AdminContractTest.
        route("/admin") {
            install(AdminAuth) { this.access = access }
        }

        tenantRoutes(koin)
        clientRoutes(koin)
        userRoutes(koin)
        keyRoutes(koin)
    }
}

private fun Route.tenantRoutes(koin: Koin) {
    get<AdminResource.Tenants> {
        koin.get<ListTenantsUseCase>()(Unit).respondWith(call) { tenants ->
            tenants.map { TenantView(it.realm) }
        }
    }

    post<AdminResource.Tenants> {
        val request = call.receive<CreateTenantRequest>()
        koin
            .get<CreateTenantUseCase>()(
            CreateTenantUseCase.Params(request.realm, request.registrationOpen),
        ).respondWith(call, HttpStatusCode.Created) {
            TenantView(it.realm, it.registrationOpen)
        }
    }
}

private fun Route.clientRoutes(koin: Koin) {
    get<AdminResource.Tenants.ByTenant.Clients> { resource ->
        koin.get<ListClientsUseCase>()(resource.parent.tenant).respondWith(call) { clients ->
            // The secret is never handed out: it is not stored in the clear and cannot be
            // recovered.
            clients.map {
                ClientView(
                    clientId = it.clientId,
                    roles = it.roles.sorted(),
                    public = it.public,
                    redirectUris = it.redirectUris.sorted(),
                    audiences = it.audiences.sorted(),
                )
            }
        }
    }

    post<AdminResource.Tenants.ByTenant.Clients> { resource ->
        val request = call.receive<CreateClientRequest>()
        koin
            .get<CreateClientUseCase>()(
            CreateClientUseCase.Params(
                realm = resource.parent.tenant,
                clientId = request.clientId,
                roles = request.roles.toSet(),
                public = request.public,
                redirectUris = request.redirectUris.toSet(),
                audiences = request.audiences.toSet(),
            ),
        ).respondWith(call, HttpStatusCode.Created) {
            ClientWithSecret(it.clientId, it.secret, it.roles.sorted())
        }
    }

    post<AdminResource.Tenants.ByTenant.Clients.ByClient.Secret> { resource ->
        val client = resource.parent
        koin
            .get<RotateClientSecretUseCase>()(
            RotateClientSecretUseCase.Params(client.parent.parent.tenant, client.clientId),
        ).respondWith(call) { ClientWithSecret(it.clientId, it.secret, it.roles.sorted()) }
    }

    // Import of a secret from the previous provider — for a migration that does not touch the
    // services (deploy.md §4a). The secret arrives in the body, not in the path: paths end up in
    // Traefik's logs.
    put<AdminResource.Tenants.ByTenant.Clients.ByClient.ImportSecret> { resource ->
        val client = resource.parent
        val request = call.receive<ImportSecretRequest>()
        koin
            .get<SetClientSecretUseCase>()(
            SetClientSecretUseCase.Params(
                client.parent.parent.tenant,
                client.clientId,
                request.secret,
            ),
        ).respondWith(call) { ClientView(it.clientId, it.roles.sorted(), it.public, it.redirectUris.sorted(), it.audiences.sorted()) }
    }

    put<AdminResource.Tenants.ByTenant.Clients.ByClient.Roles> { resource ->
        val client = resource.parent
        val request = call.receive<SetRolesRequest>()
        koin
            .get<SetClientRolesUseCase>()(
            SetClientRolesUseCase.Params(
                client.parent.parent.tenant,
                client.clientId,
                request.roles.toSet(),
            ),
        ).respondWith(call) { ClientView(it.clientId, it.roles.sorted(), it.public, it.redirectUris.sorted(), it.audiences.sorted()) }
    }

    put<AdminResource.Tenants.ByTenant.Clients.ByClient.Audiences> { resource ->
        val client = resource.parent
        val request = call.receive<SetAudiencesRequest>()
        koin
            .get<SetClientAudiencesUseCase>()(
            SetClientAudiencesUseCase.Params(
                client.parent.parent.tenant,
                client.clientId,
                request.audiences.toSet(),
            ),
        ).respondWith(call) { ClientView(it.clientId, it.roles.sorted(), it.public, it.redirectUris.sorted(), it.audiences.sorted()) }
    }

    delete<AdminResource.Tenants.ByTenant.Clients.ByClient> { resource ->
        koin
            .get<DeleteClientUseCase>()(
            DeleteClientUseCase.Params(resource.parent.parent.tenant, resource.clientId),
        ).respondWith<Unit, Unit>(call, HttpStatusCode.NoContent) { }
    }
}

private fun Route.userRoutes(koin: Koin) {
    get<AdminResource.Tenants.ByTenant.Users> { resource ->
        koin.get<ListUsersUseCase>()(resource.parent.tenant).respondWith(call) { users ->
            users.map { user ->
                UserView(
                    id = user.id,
                    email = user.email,
                    name = user.name,
                    emailVerified = user.emailVerified,
                    enabled = user.enabled,
                    identities = user.identities.map { ExternalIdentityView(it.provider, it.subject) }.sortedBy { it.provider },
                )
            }
        }
    }

    put<AdminResource.Tenants.ByTenant.Users.Password> { resource ->
        val request = call.receive<SetPasswordRequest>()
        koin
            .get<SetPasswordUseCase>()(
            SetPasswordUseCase.Params(
                realm = resource.parent.parent.tenant,
                userId = resource.userId,
                password = request.password,
            ),
        ).respondWith(call, HttpStatusCode.NoContent) { }
    }

    post<AdminResource.Tenants.ByTenant.Users> { resource ->
        val request = call.receive<ImportUserRequest>()
        koin
            .get<ImportUserUseCase>()(
            ImportUserUseCase.Params(
                realm = resource.parent.tenant,
                id = request.id,
                email = request.email,
                name = request.name,
                emailVerified = request.emailVerified,
                enabled = request.enabled,
                identities = request.identities.map { ExternalIdentity(it.provider, it.subject) }.toSet(),
            ),
        ).respondWith(call) { ImportedUserView(it.user.id, it.changed) }
    }
}

private fun Route.keyRoutes(koin: Koin) {
    get<AdminResource.Tenants.ByTenant.Keys> { resource ->
        koin.get<ListKeysUseCase>()(resource.parent.tenant).respondWith(call) { keys ->
            keys.map {
                KeyView(
                    kid = it.kid,
                    state = it.state.name,
                    createdAt = it.createdAt.toEpochMilliseconds(),
                    retiringSince = it.retiringSince?.toEpochMilliseconds(),
                )
            }
        }
    }

    post<AdminResource.Tenants.ByTenant.Keys.Rotate> { resource ->
        koin.get<RotateKeyUseCase>()(resource.parent.parent.tenant).respondWith(call, HttpStatusCode.Created) { kid ->
            KeyView(kid, "ACTIVE", 0, null)
        }
    }

    post<AdminResource.Tenants.ByTenant.Keys.Retire> { resource ->
        koin
            .get<RetireKeyUseCase>()(
            RetireKeyUseCase.Params(resource.parent.parent.tenant, resource.kid),
        ).respondWith<Unit, Unit>(call, HttpStatusCode.NoContent) { }
    }

    post<AdminResource.ReencryptKeys> {
        koin.get<ReencryptKeysUseCase>()(Unit).respondWith(call) {
            ReencryptView(it.reencrypted, it.untouched)
        }
    }
}

/**
 * One way to turn a domain `Result` into a response.
 *
 * Status codes are derived from the exception type rather than assigned in every handler:
 * otherwise the same "not found" would sooner or later answer 500 somewhere.
 */
private suspend inline fun <T, reified R : Any> Result<T>.respondWith(
    call: ApplicationCall,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
    transform: (T) -> R,
) {
    fold(
        onSuccess = { value ->
            if (successStatus == HttpStatusCode.NoContent) {
                call.respond(successStatus)
            } else {
                call.respond(successStatus, transform(value))
            }
        },
        onFailure = { error ->
            when (error) {
                is NotFound -> call.respond(HttpStatusCode.NotFound, ErrorView(error.message.orEmpty()))
                is AlreadyExists -> call.respond(HttpStatusCode.Conflict, ErrorView(error.message.orEmpty()))
                is IllegalStateException ->
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorView(error.message.orEmpty()))
                // `require(...)` in the domain means "the request is no good", not "the state is".
                is IllegalArgumentException ->
                    call.respond(HttpStatusCode.BadRequest, ErrorView(error.message.orEmpty()))
                else -> throw error
            }
        },
    )
}
