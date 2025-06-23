package com.eudi.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eudi.wallet.ble.BLEWalletServer
import com.eudi.wallet.databinding.ActivityMainBinding
import com.eudi.wallet.models.BLEEngagementData
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var credentialManager: CredentialManager
    private lateinit var bleServer: BLEWalletServer
    private var engagementData: BLEEngagementData? = null
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startProximitySharing()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        credentialManager = CredentialManager(this)
        bleServer = BLEWalletServer(this)
        
        setupUI()
        observeBLEServer()
    }
    
    private fun setupUI() {
        binding.apply {
            btnViewCredentials.setOnClickListener {
                showCredentials()
            }
            
            btnStartSharing.setOnClickListener {
                checkPermissionsAndStart()
            }
            
            btnStopSharing.setOnClickListener {
                stopProximitySharing()
            }
            
            updateUI()
        }
    }
    
    private fun updateUI() {
        val credentials = credentialManager.getStoredCredentials()
        binding.apply {
            tvCredentialCount.text = "${credentials.size} credential(s) available"
            tvWalletStatus.text = when (bleServer.serverState.value) {
                is BLEWalletServer.BLEServerState.Stopped -> "Ready to share"
                is BLEWalletServer.BLEServerState.Starting -> "Starting..."
                is BLEWalletServer.BLEServerState.Advertising -> "Waiting for verifier..."
                is BLEWalletServer.BLEServerState.Connected -> "Connected to verifier"
                is BLEWalletServer.BLEServerState.Error -> "Error: ${(bleServer.serverState.value as BLEWalletServer.BLEServerState.Error).message}"
            }
            
            btnStartSharing.isEnabled = bleServer.serverState.value is BLEWalletServer.BLEServerState.Stopped
            btnStopSharing.isEnabled = bleServer.serverState.value !is BLEWalletServer.BLEServerState.Stopped
            
            if (engagementData != null && bleServer.serverState.value is BLEWalletServer.BLEServerState.Advertising) {
                generateQRCode()
                qrCodeContainer.visibility = android.view.View.VISIBLE
            } else {
                qrCodeContainer.visibility = android.view.View.GONE
            }
        }
    }
    
    private fun showCredentials() {
        val credentials = credentialManager.getStoredCredentials()
        val credentialInfo = credentials.mapIndexed { index, credential ->
            "Credential ${index + 1}:\n" +
            "Type: ${credential.type.joinToString(", ")}\n" +
            "Issuer: ${credential.issuer}\n" +
            "Subject: ${credential.credentialSubject.givenName} ${credential.credentialSubject.familyName}\n" +
            "Issued: ${credential.issuanceDate}\n"
        }.joinToString("\n")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Stored Credentials")
            .setMessage(credentialInfo.ifEmpty { "No credentials found" })
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf<String>().apply {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            startProximitySharing()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun startProximitySharing() {
        engagementData = bleServer.startServer()
        if (engagementData == null) {
            Toast.makeText(this, "Failed to start BLE server", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopProximitySharing() {
        bleServer.stopServer()
        engagementData = null
    }
    
    private fun generateQRCode() {
        engagementData?.let { data ->
            try {
                // Create proper EUDI wallet engagement format
                val walletEngagement = mapOf(
                    "type" to "WalletEngagement",
                    "version" to 1,
                    "serviceUuid" to data.serviceUuid,
                    "characteristicUuid" to data.characteristicUuid,
                    "deviceMac" to data.deviceMac,
                    "timestamp" to (data.timestamp / 1000), // Convert to seconds
                    "nonce" to java.util.UUID.randomUUID().toString().replace("-", "").take(16)
                )
                
                val qrContent = Gson().toJson(walletEngagement)
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, 512, 512)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                    }
                }
                
                binding.ivQrCode.setImageBitmap(bitmap)
                binding.tvQrInstructions.text = "Show this QR code to the verifier"
                
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun observeBLEServer() {
        lifecycleScope.launch {
            bleServer.serverState.collect { state ->
                runOnUiThread { updateUI() }
            }
        }
        
        lifecycleScope.launch {
            bleServer.receivedRequest.collect { request ->
                if (request != null) {
                    runOnUiThread {
                        handleVerificationRequest(request)
                    }
                }
            }
        }
    }
    
    private fun handleVerificationRequest(request: com.eudi.wallet.models.VerificationRequest) {
        val intent = Intent(this, AuthenticationActivity::class.java)
        intent.putExtra("verification_request", Gson().toJson(request))
        // Set the shared instance so AuthenticationActivity can use the same BLE server
        BLEWalletServer.sharedInstance = bleServer
        startActivity(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Don't stop BLE server during active verification - let it persist
        Log.d("MainActivity", "onDestroy - keeping BLE server active for verification")
    }
}