package es.inled.pulsarphone.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import es.inled.pulsarphone.R
import es.inled.pulsarphone.data.PcApp
import es.inled.pulsarphone.databinding.ItemPcAppBinding

class AppAdapter(
    private val onAppClick: (PcApp) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private val apps = mutableListOf<PcApp>()

    fun setApps(newApps: List<PcApp>) {
        apps.clear()
        apps.addAll(newApps)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemPcAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size

    inner class AppViewHolder(private val binding: ItemPcAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: PcApp) {
            binding.appName.text = app.name

            // Load icon from base64 data URI if available
            val b64Data = app.iconData
            if (!b64Data.isNullOrBlank() && b64Data.contains("base64,")) {
                try {
                    val rawB64 = b64Data.substringAfter("base64,")
                    val decodedBytes = Base64.decode(rawB64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    if (bitmap != null) {
                        binding.appIcon.setImageBitmap(bitmap)
                    } else {
                        binding.appIcon.setImageResource(R.drawable.ic_launcher)
                    }
                } catch (e: Exception) {
                    binding.appIcon.setImageResource(R.drawable.ic_launcher)
                }
            } else {
                binding.appIcon.setImageResource(R.drawable.ic_launcher)
            }

            binding.root.setOnClickListener {
                onAppClick(app)
            }
        }
    }
}
