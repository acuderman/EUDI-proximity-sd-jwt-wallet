package com.eudi.wallet

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.eudi.wallet.databinding.DialogPinEntryBinding

class PINDialogFragment(
    private val onPINEntered: (String) -> Unit
) : DialogFragment() {
    
    private var _binding: DialogPinEntryBinding? = null
    private val binding get() = _binding!!
    
    private var enteredPIN = ""
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPinEntryBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
    }
    
    private fun setupUI() {
        binding.apply {
            // Number buttons
            listOf(btn0, btn1, btn2, btn3, btn4, btn5, btn6, btn7, btn8, btn9).forEachIndexed { index, button ->
                button.setOnClickListener {
                    if (enteredPIN.length < 4) {
                        enteredPIN += index.toString()
                        updatePINDisplay()
                        
                        if (enteredPIN.length == 4) {
                            onPINEntered(enteredPIN)
                            dismiss()
                        }
                    }
                }
            }
            
            // Backspace button
            btnBackspace.setOnClickListener {
                if (enteredPIN.isNotEmpty()) {
                    enteredPIN = enteredPIN.dropLast(1)
                    updatePINDisplay()
                }
            }
            
            // Cancel button
            btnCancel.setOnClickListener {
                dismiss()
            }
        }
        
        updatePINDisplay()
    }
    
    private fun updatePINDisplay() {
        val display = "●".repeat(enteredPIN.length) + "_".repeat(4 - enteredPIN.length)
        binding.tvPinDisplay.text = display
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}