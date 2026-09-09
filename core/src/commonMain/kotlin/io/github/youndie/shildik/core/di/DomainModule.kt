package io.github.youndie.shildik.core.di

import io.github.youndie.shildik.core.feature.admin.AdminAccess
import io.github.youndie.shildik.core.feature.admin.CreateClientUseCase
import io.github.youndie.shildik.core.feature.admin.CreateTenantUseCase
import io.github.youndie.shildik.core.feature.admin.DeleteClientUseCase
import io.github.youndie.shildik.core.feature.admin.ImportUserUseCase
import io.github.youndie.shildik.core.feature.admin.ListClientsUseCase
import io.github.youndie.shildik.core.feature.admin.ListKeysUseCase
import io.github.youndie.shildik.core.feature.admin.ListTenantsUseCase
import io.github.youndie.shildik.core.feature.admin.ListUsersUseCase
import io.github.youndie.shildik.core.feature.admin.ReencryptKeysUseCase
import io.github.youndie.shildik.core.feature.admin.RetireKeyUseCase
import io.github.youndie.shildik.core.feature.admin.RotateClientSecretUseCase
import io.github.youndie.shildik.core.feature.admin.RotateKeyUseCase
import io.github.youndie.shildik.core.feature.admin.SetClientAudiencesUseCase
import io.github.youndie.shildik.core.feature.admin.SetClientRolesUseCase
import io.github.youndie.shildik.core.feature.admin.SetClientScopesUseCase
import io.github.youndie.shildik.core.feature.admin.SetClientSecretUseCase
import io.github.youndie.shildik.core.feature.admin.SetPasswordUseCase
import io.github.youndie.shildik.core.feature.auth.AuthMethodRegistry
import io.github.youndie.shildik.core.feature.browser.AuthorizeUseCase
import io.github.youndie.shildik.core.feature.browser.CompleteAuthorizationUseCase
import io.github.youndie.shildik.core.feature.browser.EndSessionUseCase
import io.github.youndie.shildik.core.feature.browser.ExchangeCodeUseCase
import io.github.youndie.shildik.core.feature.browser.RefreshTokensUseCase
import io.github.youndie.shildik.core.feature.browser.StartAuthorizationUseCase
import io.github.youndie.shildik.core.feature.browser.SubmitLoginUseCase
import io.github.youndie.shildik.core.feature.keys.ActiveSigningKey
import io.github.youndie.shildik.core.feature.keys.GetJwksUseCase
import io.github.youndie.shildik.core.feature.token.IssueServiceTokenUseCase
import io.github.youndie.shildik.core.feature.token.IssueUserTokensUseCase
import io.github.youndie.shildik.core.feature.token.VerifyOwnTokenUseCase
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/** The domain's use cases. Ports arrive from the storage module — no implementations here. */
public fun domainModule(): Module =
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
                get<io.github.youndie.shildik.core.config.ShildikConfig>().effectiveBootstrapToken,
                get(),
                get(),
            )
        }
    }
