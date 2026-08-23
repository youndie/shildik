package ru.workinprogress.shildik.core.di

import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import ru.workinprogress.shildik.core.feature.admin.AdminAccess
import ru.workinprogress.shildik.core.feature.admin.CreateClientUseCase
import ru.workinprogress.shildik.core.feature.admin.CreateTenantUseCase
import ru.workinprogress.shildik.core.feature.admin.DeleteClientUseCase
import ru.workinprogress.shildik.core.feature.admin.ImportUserUseCase
import ru.workinprogress.shildik.core.feature.admin.ListClientsUseCase
import ru.workinprogress.shildik.core.feature.admin.ListKeysUseCase
import ru.workinprogress.shildik.core.feature.admin.ListTenantsUseCase
import ru.workinprogress.shildik.core.feature.admin.ListUsersUseCase
import ru.workinprogress.shildik.core.feature.admin.ReencryptKeysUseCase
import ru.workinprogress.shildik.core.feature.admin.RetireKeyUseCase
import ru.workinprogress.shildik.core.feature.admin.RotateClientSecretUseCase
import ru.workinprogress.shildik.core.feature.admin.RotateKeyUseCase
import ru.workinprogress.shildik.core.feature.admin.SetClientAudiencesUseCase
import ru.workinprogress.shildik.core.feature.admin.SetClientRolesUseCase
import ru.workinprogress.shildik.core.feature.admin.SetClientSecretUseCase
import ru.workinprogress.shildik.core.feature.admin.SetPasswordUseCase
import ru.workinprogress.shildik.core.feature.auth.AuthMethodRegistry
import ru.workinprogress.shildik.core.feature.browser.AuthorizeUseCase
import ru.workinprogress.shildik.core.feature.browser.CompleteAuthorizationUseCase
import ru.workinprogress.shildik.core.feature.browser.EndSessionUseCase
import ru.workinprogress.shildik.core.feature.browser.ExchangeCodeUseCase
import ru.workinprogress.shildik.core.feature.browser.RefreshTokensUseCase
import ru.workinprogress.shildik.core.feature.browser.StartAuthorizationUseCase
import ru.workinprogress.shildik.core.feature.browser.SubmitLoginUseCase
import ru.workinprogress.shildik.core.feature.keys.ActiveSigningKey
import ru.workinprogress.shildik.core.feature.keys.GetJwksUseCase
import ru.workinprogress.shildik.core.feature.token.IssueServiceTokenUseCase
import ru.workinprogress.shildik.core.feature.token.IssueUserTokensUseCase
import ru.workinprogress.shildik.core.feature.token.VerifyOwnTokenUseCase

/** The domain's use cases. Ports arrive from the storage module — no implementations here. */
fun domainModule(): Module =
    module {
        single { ActiveSigningKey(get(), get()) }
        single { GetJwksUseCase(get(), get(), get(), get()) }
        single { IssueServiceTokenUseCase(get(), get(), get(), get()) }

        singleOf(::CreateTenantUseCase)
        singleOf(::ListTenantsUseCase)
        singleOf(::CreateClientUseCase)
        singleOf(::RotateClientSecretUseCase)
        singleOf(::SetClientSecretUseCase)
        singleOf(::ImportUserUseCase)
        singleOf(::SetPasswordUseCase)
        // An empty registry is a normal state: a sign-in method is wired in where the
        // distribution is assembled rather than "discovered" by reflection (research §R5). It is
        // overridden by the module that brings one.
        single { AuthMethodRegistry() }
        singleOf(::AuthorizeUseCase)
        single { IssueUserTokensUseCase(get(), get()) }
        single { VerifyOwnTokenUseCase(get()) }
        singleOf(::ExchangeCodeUseCase)
        // The clock is passed explicitly: `singleOf` resolves **every** constructor parameter,
        // including those with defaults, and fails when no Clock definition exists.
        single { RefreshTokensUseCase(get(), get(), get(), get(), get()) }
        singleOf(::StartAuthorizationUseCase)
        singleOf(::CompleteAuthorizationUseCase)
        single { SubmitLoginUseCase(get(), get(), get(), get()) }
        singleOf(::EndSessionUseCase)
        singleOf(::ListUsersUseCase)
        singleOf(::SetClientRolesUseCase)
        singleOf(::SetClientAudiencesUseCase)
        singleOf(::DeleteClientUseCase)
        singleOf(::ListClientsUseCase)
        single { RotateKeyUseCase(get(), get(), get(), get(), get()) }
        single { RetireKeyUseCase(get(), get()) }
        singleOf(::ListKeysUseCase)
        singleOf(::ReencryptKeysUseCase)

        single { AdminAccess(get<ru.workinprogress.shildik.core.config.ShildikConfig>().effectiveBootstrapToken, get(), get()) }
    }
