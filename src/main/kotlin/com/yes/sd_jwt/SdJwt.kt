package com.yes.sd_jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyType
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

fun createHash(value: String): String {
    val hashFunction = MessageDigest.getInstance("SHA-256")
    val messageDigest = hashFunction.digest(value.toByteArray(Charsets.UTF_8))
    return b64Encoder(messageDigest)
}

fun buildSvcAndSdClaims(claims: JSONObject, depth: Int): Pair<JSONObject, JSONObject> {
    val svcClaims = JSONObject()
    val sdClaims = JSONObject()

    val secureRandom = SecureRandom()

    for (key in claims.keys()) {
        if (claims[key] is String || depth == 0) {
            val preproValue = if (claims[key] is String) {
                claims.getString(key)
            } else if (claims[key] is JSONObject) {
               claims[key]
            } else {
                throw Exception("Cannot encode class")
            }

            val randomness = ByteArray(16)
            secureRandom.nextBytes(randomness)
            val salt = b64Encoder(randomness)

            val hashInput = JSONArray().put(salt).put(preproValue).toString()
            svcClaims.put(key, hashInput)

            sdClaims.put(key, createHash(hashInput))
        } else if (claims[key] is JSONObject && depth > 0) {
            val (svcClaimsChild, sdClaimsChild) = buildSvcAndSdClaims(claims.getJSONObject(key), depth - 1)
            svcClaims.put(key, svcClaimsChild)
            sdClaims.put(key, sdClaimsChild)
        } else {
            throw Exception("Cannot encode class")
        }
    }

    return Pair(svcClaims, sdClaims)
}

inline fun <reified T> createCredential(claims: T, holderPubKey: JWK?, issuer: String, issuerKey: JWK, depth: Int = 0): String {
    val jsonClaims = JSONObject(Json.encodeToString(claims))
    val (svcClaims, sdClaims) = buildSvcAndSdClaims(jsonClaims, depth)

    val svc = JSONObject().put("sd_claims", svcClaims)
    val svcEncoded = b64Encoder(svc.toString())

    val date = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
    val claimsSet = JSONObject()
        .put("iss", issuer)
        .put("iat", date)
        .put("exp", date + 3600 * 24)
        .put("sub_jwk", issuerKey.toPublicJWK().toJSONObject())
        .put("sd_claims", sdClaims)
    if (holderPubKey != null) {
        claimsSet.put("holder", jwkThumbprint(holderPubKey))
    }

    val sdJwtEncoded = buildJWT(claimsSet.toString(), issuerKey)

    return "$sdJwtEncoded.$svcEncoded"
}

fun buildReleaseSdClaims(releaseClaims: JSONObject, svc: JSONObject): JSONObject {
    val releaseClaimsResult = JSONObject()

    for (key in releaseClaims.keys()) {
        if (releaseClaims[key] is String && releaseClaims[key] == "disclose") {
            releaseClaimsResult.put(key, svc.getString(key))
        } else if (releaseClaims[key] is JSONObject && svc[key] is String) {
            releaseClaimsResult.put(key, svc.getString(key))
        } else if (releaseClaims[key] is JSONObject) {
            val rCR = buildReleaseSdClaims(releaseClaims.getJSONObject(key), svc.getJSONObject(key))
            releaseClaimsResult.put(key, rCR)
        }
    }
    return releaseClaimsResult
}

inline fun <reified T> createPresentation(credential: String, releaseClaims: T, audience: String, nonce: String, holderKey: JWK?): String {
    // Extract svc as the last part of the credential and parse it as a JSON object
    val credentialParts = credential.split(".")
    val svc = JSONObject(b64Decode(credentialParts[3]))
    val rC = JSONObject(Json.encodeToString(releaseClaims))

    val releaseDocument = JSONObject()
    releaseDocument.put("nonce", nonce)
    releaseDocument.put("aud", audience)
    releaseDocument.put("sd_claims", buildReleaseSdClaims(rC, svc.getJSONObject("sd_claims")))
    if (holderKey != null) {
        releaseDocument.put("sub_jwk", holderKey.toPublicJWK().toJSONObject())
    }
    // TODO Check if credential has holder binding. If so force signing of the SD-JWT Release.
    val releaseDocumentEncoded = buildJWT(releaseDocument.toString(), holderKey)

    return "${credentialParts[0]}.${credentialParts[1]}.${credentialParts[2]}.$releaseDocumentEncoded"
}

fun buildJWT(claims: String, key: JWK?): String {
    if (key == null) {
        val header = b64Encoder("{\"alg\":\"none\"}")
        val body = b64Encoder(claims)
        return "$header.$body."
    }
    return when (key.keyType) {
        KeyType.OKP -> {
            val signer = Ed25519Signer(key as OctetKeyPair)
            val signedSdJwt = JWSObject(JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(key.keyID).build(), Payload(claims))
            signedSdJwt.sign(signer)
            signedSdJwt.serialize()
        }
        KeyType.RSA -> {
            val signer = RSASSASigner(key as RSAKey)
            val signedSdJwt = JWSObject(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), Payload(claims))
            signedSdJwt.sign(signer)
            signedSdJwt.serialize()
        }
        else -> {
            throw NotImplementedError("JWT signing algorithm not implemented")
        }
    }
}

fun parseAndVerifySdClaims(sdClaims: JSONObject, svc: JSONObject): JSONObject {
    val sdClaimsParsed = JSONObject()
    for (key in svc.keys()) {
        if (svc[key] is String) {
            // Verify that the hash in the SD-JWT matches the one created from the SD-JWT Release
            if (createHash(svc.getString(key)) != sdClaims.getString(key)) {
                throw Exception("Could not verify credential claims (Claim $key has wrong hash value)")
            }
            val sVArray = JSONArray(svc.getString(key))
            if (sVArray.length() != 2) {
                throw Exception("Could not verify credential claims (Claim $key has wrong number of array entries)")
            }
            sdClaimsParsed.put(key, sVArray[1])
        } else if (svc[key] is JSONObject) {
            val sCPChild = parseAndVerifySdClaims(sdClaims.getJSONObject(key), svc.getJSONObject(key))
            sdClaimsParsed.put(key, sCPChild)
        }
    }
    return sdClaimsParsed
}

inline fun <reified T> verifyPresentation(presentation: String, trustedIssuer: Map<String, String>, expectedNonce: String, expectedAud: String): T {
    val pS = presentation.split(".")
    if (pS.size != 6) {
        throw Exception("Presentation has wrong format (Needed 6 parts separated by '.')")
    }

    // Verify SD-JWT
    val sdJwt = "${pS[0]}.${pS[1]}.${pS[2]}"
    val sdJwtParsed = verifyJWTSignature(sdJwt, trustedIssuer, true)
    verifyJwtClaims(sdJwtParsed)

    // Verify SD-JWT Release
    val sdJwtRelease = "${pS[3]}.${pS[4]}.${pS[5]}"
    val holderBinding = getHolderBinding(sdJwtParsed)
    val sdJwtReleaseParsed = verifyJWTSignature(sdJwtRelease, holderBinding, false)
    verifyJwtClaims(sdJwtReleaseParsed, expectedNonce, expectedAud)

    val sdClaimsParsed = parseAndVerifySdClaims(sdJwtParsed.getJSONObject("sd_claims"), sdJwtReleaseParsed.getJSONObject("sd_claims"))

    return Json.decodeFromString(sdClaimsParsed.toString())
}

fun verifyJWTSignature(jwt: String, trustedIssuer: Map<String, String>, verifyIss: Boolean): JSONObject {
    val splits = jwt.split(".")
    val header = JSONObject(b64Decode(splits[0]))
    val body = JSONObject(b64Decode(splits[1]))

    val jwk: JWK
    val verifier: JWSVerifier
    if (header.getString("alg") == JWSAlgorithm.EdDSA.name) {
        jwk = OctetKeyPair.parse(body.getJSONObject("sub_jwk").toString())
        verifier = Ed25519Verifier(jwk)
    } else if (header.getString("alg") == JWSAlgorithm.RS256.name) {
        jwk = RSAKey.parse(body.getJSONObject("sub_jwk").toString())
        verifier = RSASSAVerifier(jwk)
    } else if (header.getString("alg") == "none") {
        return body
    } else {
        throw Exception("JWT signing algorithm not implemented")
    }

    // Verify issuer key
    val jwkThumbprint = jwkThumbprint(jwk)
    if (
        (verifyIss
                && (body.isNull("iss") || !trustedIssuer.containsKey(body.getString("iss"))
                || trustedIssuer[body.getString("iss")] != jwkThumbprint)
            )
        || (!verifyIss
                && (!body.isNull("iss") || !trustedIssuer.containsKey(jwkThumbprint)
                || trustedIssuer[jwkThumbprint] != jwkThumbprint)
            )
    ) {
        throw Exception("Could not verify JWK key (Thumbprint: $jwkThumbprint)")
    }

    val jwtParsed = SignedJWT.parse(jwt)
    // Verify JWT
    if(!jwtParsed.verify(verifier)) {
        throw Exception("Invalid JWT signature")
    }

    return body
}

fun getHolderBinding(sdJwt: JSONObject): Map<String, String>  {
    return if (sdJwt.isNull("holder")) {
        mapOf()
    } else {
        mapOf(sdJwt.getString("holder") to sdJwt.getString("holder"))
    }
}

fun verifyJwtClaims(claims: JSONObject, expectedNonce: String? = null, expectedAud: String? = null) {
    if (expectedNonce != null && claims.getString("nonce") != expectedNonce) {
        throw Exception("JWT claims verification failed (invalid nonce)")
    }
    if (expectedAud != null && claims.getString("aud") != expectedAud) {
        throw Exception("JWT claims verification failed (invalid audience)")
    }

    val date = Date(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) * 1000)
    // Check that the JWT is already valid with an offset of 30 seconds
    if (!claims.isNull("iat") && !date.after(Date((claims.getLong("iat") - 30) * 1000))) {
        throw Exception("JWT not yet valid")
    }
    if (!claims.isNull("exp") && !date.before(Date(claims.getLong("exp") * 1000))) {
        throw Exception("JWT is expired")
    }
}

fun b64Encoder(str: String): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(str.toByteArray())
}

fun b64Encoder(b: ByteArray): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

fun b64Decode(str: String): String {
    return String(Base64.getUrlDecoder().decode(str))
}

fun jwkThumbprint(jwk: JWK): String {
    return b64Encoder(jwk.computeThumbprint().decode())
}
