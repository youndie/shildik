package io.github.youndie.shildik.oidc

public data class AuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expirationTimeMillis: Long,
)
