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
import ru.workinprogress.shildik.core.feature.admin.SetClientScopesUseCase
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
        // THE CLOCK IS LEFT TO ITS DEFAULT, WHICH IS WHY THESE ARE NOT `singleOf`.
        //
        // `singleOf` resolves **every** constructor parameter, including those with defaults, and
        // fails when no Clock definition exists. The note used to stand over `RefreshTokensUseCase`
        // alone, because it was the only use case here that took a clock; the other four took the
        // machine's. Now that they take the port too, the note is about all five.
        single { AuthorizeUseCase(get(), get(), get(), get(), get(), get()) }
        single { IssueUserTokensUseCase(get(), get()) }
        single { VerifyOwnTokenUseCase(get()) }
        single { ExchangeCodeUseCase(get(), get(), get(), get(), get(), get()) }
        single { RefreshTokensUseCase(get(), get(), get(), get(), get()) }
        single { StartAuthorizationUseCase(get(), get(), get(), get()) }
        single { CompleteAuthorizationUseCase(get(), get(), get()) }
        single { SubmitLoginUseCase(get(), get(), get(), get()) }
        singleOf(::EndSessionUseCase)
        singleOf(::ListUsersUseCase)
        singleOf(::SetClientRolesUseCase)
        singleOf(::SetClientAudiencesUseCase)
        singleOf(::SetClientScopesUseCase)
        singleOf(::DeleteClientUseCase)
        singleOf(::ListClientsUseCase)
        single { RotateKeyUseCase(get(), get(), get(), get(), get()) }
        single { RetireKeyUseCase(get(), get()) }
        singleOf(::ListKeysUseCase)
        singleOf(::ReencryptKeysUseCase)

        single {
            AdminAccess(
                get<ru.workinprogress.shildik.core.config.ShildikConfig>().effectiveBootstrapToken,
                get(),
                get(),
            )
        }
    }
