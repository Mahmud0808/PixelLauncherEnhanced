package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.utils.RowBackgrounds
import com.drdisagree.pixellauncherenhanced.data.model.SettingsEntry
import com.drdisagree.pixellauncherenhanced.databinding.FragmentSettingsSearchBinding
import com.drdisagree.pixellauncherenhanced.ui.activities.MainActivity
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.drdisagree.pixellauncherenhanced.utils.SettingsIndex
import com.google.android.material.color.MaterialColors
import java.util.Locale

class SettingsSearch : Fragment() {

    private lateinit var binding: FragmentSettingsSearchBinding
    private val adapter = ResultAdapter()
    private var query = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentSettingsSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString(MainActivity.ARG_SHARED_ELEMENT)?.let { name ->
            binding.searchBar.transitionName = name
            arguments?.remove(MainActivity.ARG_SHARED_ELEMENT)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            root.updatePadding(top = bars.top)
            binding.searchResults.updatePadding(bottom = bars.bottom + dpToPx(16))
            insets
        }

        binding.searchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.searchResults.adapter = adapter
        binding.searchBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.searchClear.setOnClickListener { binding.searchInput.setText("") }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = runSearch(s?.toString().orEmpty())
        })

        runSearch(binding.searchInput.text.toString())
        binding.searchInput.postDelayed({
            if (!isAdded) return@postDelayed
            binding.searchInput.requestFocus()
            requireContext().getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, KEYBOARD_DELAY_MS)
    }

    override fun onPause() {
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        super.onPause()
    }

    private fun runSearch(text: String) {
        query = text.trim()
        binding.searchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE

        val results = SettingsIndex.search(requireContext(), query)
        adapter.submitList(results)

        binding.searchEmpty.visibility = if (query.isNotEmpty() && results.isEmpty()) View.VISIBLE else View.GONE
        binding.searchEmpty.text = getString(R.string.search_no_results, query)
    }

    private fun highlight(text: String): CharSequence {
        if (query.isEmpty()) return text
        val start = text.lowercase(Locale.getDefault()).indexOf(query.lowercase(Locale.getDefault()))
        if (start < 0) return text

        return SpannableString(text).apply {
            val end = start + query.length
            setSpan(
                ForegroundColorSpan(MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private inner class ResultAdapter : ListAdapter<SettingsEntry, RecyclerView.ViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.view_search_result, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = getItem(position)
            val view = holder.itemView

            view.findViewById<TextView>(R.id.result_breadcrumb).text = listOfNotNull(
                entry.screenTitle,
                entry.categoryTitle?.takeIf { it != entry.screenTitle }
            ).joinToString(BREADCRUMB_SEPARATOR)
            view.findViewById<TextView>(R.id.result_title).text = highlight(entry.title)
            view.findViewById<TextView>(R.id.result_summary).apply {
                visibility = if (entry.summary.isEmpty()) View.GONE else View.VISIBLE
                text = entry.summary
            }

            val count = itemCount
            RowBackgrounds.apply(view, position, count)
            (view.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                if (position == count - 1) 0 else dpToPx(2)

            view.setOnClickListener { (activity as? MainActivity)?.openSetting(entry) }
        }
    }

    companion object {
        private const val BREADCRUMB_SEPARATOR = "  ›  "
        private const val KEYBOARD_DELAY_MS = 320L

        private val DIFF = object : DiffUtil.ItemCallback<SettingsEntry>() {
            override fun areItemsTheSame(oldItem: SettingsEntry, newItem: SettingsEntry) =
                oldItem.key == newItem.key && oldItem.fragment == newItem.fragment

            override fun areContentsTheSame(oldItem: SettingsEntry, newItem: SettingsEntry) = false
        }
    }
}
