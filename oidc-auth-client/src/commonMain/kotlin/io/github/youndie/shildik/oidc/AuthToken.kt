package io.github.youndie.shildik.oidc

data class AuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expirationTimeMillis: Long,
)
