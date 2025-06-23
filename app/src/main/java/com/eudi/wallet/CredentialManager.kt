package com.eudi.wallet

import android.content.Context
import android.content.SharedPreferences
import com.eudi.wallet.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import java.text.SimpleDateFormat
import java.util.*

class CredentialManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("eudi_credentials", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
    
    // Sample holder DID and key
    private val holderKey: ECKey by lazy {
        val keyJson = prefs.getString("holder_key", null)
        if (keyJson != null) {
            ECKey.parse(keyJson)
        } else {
            val key = ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256)
                .keyID("holder-key-1")
                .generate()
            prefs.edit().putString("holder_key", key.toJSONString()).apply()
            key
        }
    }
    
    private val holderDid: String by lazy {
        prefs.getString("holder_did", null) ?: run {
            val did = "did:key:${UUID.randomUUID()}"
            prefs.edit().putString("holder_did", did).apply()
            did
        }
    }
    
    init {
        initializeSampleCredentials()
    }
    
    private fun initializeSampleCredentials() {
        if (getStoredCredentials().isEmpty()) {
            val sampleCredential = createSampleEUDICredential()
            storeCredential(sampleCredential)
        }
    }
    
    private fun createSampleEUDICredential(): VerifiableCredential {
        val now = Date()
        val expiry = Calendar.getInstance().apply { 
            time = now
            add(Calendar.YEAR, 5) 
        }.time
        
        return VerifiableCredential(
            id = "urn:credential:eudi:${UUID.randomUUID()}",
            issuer = "did:web:issuer.europa.eu",
            issuanceDate = dateFormat.format(now),
            expirationDate = dateFormat.format(expiry),
            credentialSubject = CredentialSubject(
                id = holderDid,
                givenName = "Maria",
                familyName = "García",
                dateOfBirth = "1985-06-15",
                placeOfBirth = "Madrid, Spain",
                address = Address(
                    streetAddress = "Calle Mayor 123",
                    addressLocality = "Madrid",
                    postalCode = "28013",
                    addressCountry = "ES"
                ),
                nationality = "ES",
                documentNumber = "12345678Z"
            ),
            proof = createSampleProof()
        )
    }
    
    private fun createSampleProof(): Proof {
        return Proof(
            created = dateFormat.format(Date()),
            verificationMethod = "did:web:issuer.europa.eu#key-1",
            jws = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWV9.TJVA95OrM7E2cBab30RMHrHDcEfxjoYZgeFONFh7HgQ"
        )
    }
    
    fun getStoredCredentials(): List<VerifiableCredential> {
        val credentialsJson = prefs.getString("credentials", "[]")
        val type = object : TypeToken<List<VerifiableCredential>>() {}.type
        return gson.fromJson(credentialsJson, type) ?: emptyList()
    }
    
    private fun storeCredential(credential: VerifiableCredential) {
        val credentials = getStoredCredentials().toMutableList()
        credentials.add(credential)
        val credentialsJson = gson.toJson(credentials)
        prefs.edit().putString("credentials", credentialsJson).apply()
    }
    
    fun createVerifiablePresentation(
        request: VerificationRequest,
        selectedCredentials: List<VerifiableCredential>
    ): VPToken {
        
        val presentation = VerifiablePresentation(
            holder = holderDid,
            verifiableCredential = selectedCredentials,
            proof = createPresentationProof(request.nonce)
        )
        
        return VPToken(verifiablePresentation = presentation)
    }
    
    private fun createPresentationProof(nonce: String): Proof {
        try {
            val header = JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(holderKey.keyID)
                .build()
            
            val payload = mapOf(
                "iss" to holderDid,
                "aud" to "verifier",
                "nonce" to nonce,
                "iat" to (System.currentTimeMillis() / 1000)
            )
            
            val jwsObject = JWSObject(header, Payload(gson.toJson(payload)))
            jwsObject.sign(ECDSASigner(holderKey))
            
            return Proof(
                created = dateFormat.format(Date()),
                verificationMethod = "$holderDid#${holderKey.keyID}",
                jws = jwsObject.serialize()
            )
        } catch (e: Exception) {
            // Fallback to mock proof if signing fails
            return Proof(
                created = dateFormat.format(Date()),
                verificationMethod = "$holderDid#key-1",
                jws = "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJob2xkZXIiLCJhdWQiOiJ2ZXJpZmllciIsIm5vbmNlIjoiJG5vbmNlIn0.signature"
            )
        }
    }
    
    fun getCredentialsByType(types: List<String>): List<VerifiableCredential> {
        return getStoredCredentials().filter { credential ->
            credential.type.any { it in types }
        }
    }
    
    fun getAvailableAttributes(): List<String> {
        val credentials = getStoredCredentials()
        val attributes = mutableSetOf<String>()
        
        credentials.forEach { credential ->
            credential.credentialSubject.let { subject ->
                attributes.add("given_name")
                attributes.add("family_name")
                attributes.add("date_of_birth")
                subject.placeOfBirth?.let { attributes.add("place_of_birth") }
                subject.address?.let { 
                    attributes.add("address")
                    attributes.add("street_address")
                    attributes.add("address_locality")
                    attributes.add("postal_code")
                    attributes.add("address_country")
                }
                subject.nationality?.let { attributes.add("nationality") }
                subject.documentNumber?.let { attributes.add("document_number") }
            }
        }
        
        return attributes.toList()
    }
}