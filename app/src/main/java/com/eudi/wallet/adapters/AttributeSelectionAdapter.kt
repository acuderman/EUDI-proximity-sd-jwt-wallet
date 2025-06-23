package com.eudi.wallet.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.eudi.wallet.databinding.ItemAttributeSelectionBinding

class AttributeSelectionAdapter(
    private val attributes: List<String>
) : RecyclerView.Adapter<AttributeSelectionAdapter.AttributeViewHolder>() {
    
    private val selectedAttributes = mutableSetOf<String>()
    
    init {
        // Pre-select all attributes by default
        selectedAttributes.addAll(attributes)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttributeViewHolder {
        val binding = ItemAttributeSelectionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AttributeViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: AttributeViewHolder, position: Int) {
        holder.bind(attributes[position])
    }
    
    override fun getItemCount(): Int = attributes.size
    
    fun getSelectedAttributes(): List<String> = selectedAttributes.toList()
    
    inner class AttributeViewHolder(
        private val binding: ItemAttributeSelectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(attribute: String) {
            val displayName = attribute.replace("_", " ").split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            
            binding.apply {
                tvAttributeName.text = displayName
                tvAttributeDescription.text = getAttributeDescription(attribute)
                cbSelected.isChecked = selectedAttributes.contains(attribute)
                
                root.setOnClickListener {
                    cbSelected.isChecked = !cbSelected.isChecked
                    if (cbSelected.isChecked) {
                        selectedAttributes.add(attribute)
                    } else {
                        selectedAttributes.remove(attribute)
                    }
                }
                
                cbSelected.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedAttributes.add(attribute)
                    } else {
                        selectedAttributes.remove(attribute)
                    }
                }
            }
        }
        
        private fun getAttributeDescription(attribute: String): String {
            return when (attribute) {
                "given_name" -> "Your first name"
                "family_name" -> "Your last name"
                "date_of_birth" -> "Your date of birth"
                "place_of_birth" -> "Your place of birth"
                "address" -> "Your full address"
                "street_address" -> "Your street address"
                "address_locality" -> "Your city"
                "postal_code" -> "Your postal code"
                "address_country" -> "Your country"
                "nationality" -> "Your nationality"
                "document_number" -> "Your document number"
                else -> "Personal information"
            }
        }
    }
}