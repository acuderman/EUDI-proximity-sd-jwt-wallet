package com.eudi.wallet

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.eudi.wallet.adapters.AttributeSelectionAdapter
import com.eudi.wallet.ble.BLEWalletServer
import com.eudi.wallet.databinding.ActivityAuthenticationBinding
import com.eudi.wallet.models.VerificationRequest
import com.google.gson.Gson

class AuthenticationActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAuthenticationBinding
    private lateinit var credentialManager: CredentialManager
    private lateinit var bleServer: BLEWalletServer
    private lateinit var verificationRequest: VerificationRequest
    private lateinit var attributeAdapter: AttributeSelectionAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthenticationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        credentialManager = CredentialManager(this)
        // Use shared BLE server instance to maintain connection
        bleServer = BLEWalletServer.sharedInstance ?: BLEWalletServer(this)
        
        val requestJson = intent.getStringExtra("verification_request")
        if (requestJson == null) {
            finish()
            return
        }
        
        verificationRequest = Gson().fromJson(requestJson, VerificationRequest::class.java)
        
        setupUI()
    }
    
    private fun setupUI() {
        binding.apply {
            // Display verifier information
            tvVerifierName.text = verificationRequest.verifierName
            tvVerificationPurpose.text = verificationRequest.presentationDefinition.purpose
            
            // Setup attribute selection
            val availableAttributes = credentialManager.getAvailableAttributes()
            attributeAdapter = AttributeSelectionAdapter(availableAttributes)
            
            rvAttributes.layoutManager = LinearLayoutManager(this@AuthenticationActivity)
            rvAttributes.adapter = attributeAdapter
            
            // Button handlers
            btnReject.setOnClickListener {
                rejectRequest()
            }
            
            btnApprove.setOnClickListener {
                val selectedAttributes = attributeAdapter.getSelectedAttributes()
                if (selectedAttributes.isEmpty()) {
                    Toast.makeText(this@AuthenticationActivity, "Please select at least one attribute", Toast.LENGTH_SHORT).show()
                } else {
                    showPINDialog(selectedAttributes)
                }
            }
        }
    }
    
    private fun showPINDialog(selectedAttributes: List<String>) {
        val pinDialog = PINDialogFragment { pin ->
            if (validatePIN(pin)) {
                approveRequest(selectedAttributes)
            } else {
                Toast.makeText(this, "Invalid PIN", Toast.LENGTH_SHORT).show()
            }
        }
        pinDialog.show(supportFragmentManager, "pin_dialog")
    }
    
    private fun validatePIN(pin: String): Boolean {
        // Simple PIN validation - in real app this would be more secure
        return pin == "1234" || pin.length == 4
    }
    
    private fun approveRequest(selectedAttributes: List<String>) {
        try {
            val credentials = credentialManager.getStoredCredentials()
            val vpToken = credentialManager.createVerifiablePresentation(
                verificationRequest,
                credentials // In real app, filter by selected attributes
            )
            
            val vpTokenJson = Gson().toJson(vpToken)
            bleServer.sendResponse(vpTokenJson)
            
            Toast.makeText(this, "Credentials shared successfully", Toast.LENGTH_SHORT).show()
            finish()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share credentials: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun rejectRequest() {
        Toast.makeText(this, "Request rejected", Toast.LENGTH_SHORT).show()
        finish()
    }
}