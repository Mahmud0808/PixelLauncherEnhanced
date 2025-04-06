package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.URLSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin

/**
 * A custom preference acting as "footer" of a page. It has a field for icon and text. It is added
 * to screen as the last preference.
 */
class FooterPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    Preference(context, attrs, R.attr.footerPreferenceStyle) {
    @VisibleForTesting
    var mLearnMoreListener: View.OnClickListener? = null

    @VisibleForTesting
    var mIconVisibility: Int = View.VISIBLE
    private var mContentDescription: CharSequence? = null
    private var mLearnMoreText: CharSequence? = null
    private var mLearnMoreSpan: FooterLearnMoreSpan? = null

    init {
        init()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val title = holder.itemView.findViewById<TextView>(android.R.id.title)
        if (title != null && !TextUtils.isEmpty(mContentDescription)) {
            title.contentDescription = mContentDescription
        }

        val learnMore = holder.itemView.findViewById<TextView>(R.id.settingslib_learn_more)
        if (learnMore != null) {
            if (mLearnMoreListener != null) {
                learnMore.visibility = View.VISIBLE
                if (TextUtils.isEmpty(mLearnMoreText)) {
                    mLearnMoreText = learnMore.text
                } else {
                    learnMore.text = mLearnMoreText
                }
                val learnMoreText = SpannableString(mLearnMoreText)
                if (mLearnMoreSpan != null) {
                    learnMoreText.removeSpan(mLearnMoreSpan)
                }
                mLearnMoreSpan = FooterLearnMoreSpan(mLearnMoreListener)
                learnMoreText.setSpan(
                    mLearnMoreSpan, 0,
                    learnMoreText.length, 0
                )
                learnMore.text = learnMoreText
            } else {
                learnMore.visibility = View.GONE
            }
        }

        val icon = holder.itemView.findViewById<View>(R.id.icon_frame)
        if (icon != null) {
            icon.visibility = mIconVisibility
        }

        setFirstAndLastItemMargin(holder)
    }

    override fun setSummary(summary: CharSequence?) {
        title = summary
    }

    override fun setSummary(summaryResId: Int) {
        setTitle(summaryResId)
    }

    override fun getSummary(): CharSequence? {
        return title
    }

    @get:VisibleForTesting
    var contentDescription: CharSequence?
        /**
         * Return the content description of footer preference.
         */
        get() = mContentDescription
        /**
         * To set content description of the [FooterPreference]. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param contentDescription The resource id of the content description.
         */
        set(contentDescription) {
            if (!TextUtils.equals(mContentDescription, contentDescription)) {
                mContentDescription = contentDescription
                notifyChanged()
            }
        }

    /**
     * Sets the learn more text.
     *
     * @param learnMoreText The string of the learn more text.
     */
    fun setLearnMoreText(learnMoreText: CharSequence?) {
        if (!TextUtils.equals(mLearnMoreText, learnMoreText)) {
            mLearnMoreText = learnMoreText
            notifyChanged()
        }
    }

    /**
     * Assign an action for the learn more link.
     */
    fun setLearnMoreAction(listener: View.OnClickListener) {
        if (mLearnMoreListener !== listener) {
            mLearnMoreListener = listener
            notifyChanged()
        }
    }

    /**
     * Set visibility of footer icon.
     */
    fun setIconVisibility(iconVisibility: Int) {
        if (mIconVisibility == iconVisibility) {
            return
        }
        mIconVisibility = iconVisibility
        notifyChanged()
    }

    private fun init() {
        layoutResource = R.layout.preference_footer
        if (icon == null) {
            setIcon(R.drawable.ic_info)
        }
        order = ORDER_FOOTER
        if (TextUtils.isEmpty(key)) {
            key = KEY_FOOTER
        }
        isSelectable = false
    }

    /**
     * The builder is convenient to create a dynamic FooterPreference.
     */
    class Builder(private val mContext: Context) {
        private var mKey: String? = null
        private var mTitle: CharSequence? = null
        private var mContentDescription: CharSequence? = null
        private var mLearnMoreText: CharSequence? = null

        /**
         * To set the key value of the [FooterPreference].
         *
         * @param key The key value.
         */
        fun setKey(key: String): Builder {
            mKey = key
            return this
        }

        /**
         * To set the title of the [FooterPreference].
         *
         * @param title The title.
         */
        fun setTitle(title: CharSequence?): Builder {
            mTitle = title
            return this
        }

        /**
         * To set the title of the [FooterPreference].
         *
         * @param titleResId The resource id of the title.
         */
        fun setTitle(@StringRes titleResId: Int): Builder {
            mTitle = mContext.getText(titleResId)
            return this
        }

        /**
         * To set content description of the [FooterPreference]. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param contentDescription The resource id of the content description.
         */
        fun setContentDescription(contentDescription: CharSequence?): Builder {
            mContentDescription = contentDescription
            return this
        }

        /**
         * To set content description of the [FooterPreference]. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param contentDescriptionResId The resource id of the content description.
         */
        fun setContentDescription(@StringRes contentDescriptionResId: Int): Builder {
            mContentDescription = mContext.getText(contentDescriptionResId)
            return this
        }

        /**
         * To set learn more string of the learn more text. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param learnMoreText The resource id of the learn more string.
         */
        fun setLearnMoreText(learnMoreText: CharSequence?): Builder {
            mLearnMoreText = learnMoreText
            return this
        }

        /**
         * To set learn more string of the [FooterPreference]. This can use for talkback
         * environment if developer wants to have a customization content.
         *
         * @param learnMoreTextResId The resource id of the learn more string.
         */
        fun setLearnMoreText(@StringRes learnMoreTextResId: Int): Builder {
            mLearnMoreText = mContext.getText(learnMoreTextResId)
            return this
        }


        /**
         * To generate the [FooterPreference].
         */
        fun build(): FooterPreference {
            val footerPreference = FooterPreference(mContext)
            footerPreference.isSelectable = false
            require(!TextUtils.isEmpty(mTitle)) { "Footer title cannot be empty!" }
            footerPreference.title = mTitle
            if (!TextUtils.isEmpty(mKey)) {
                footerPreference.key = mKey
            }

            if (!TextUtils.isEmpty(mContentDescription)) {
                footerPreference.contentDescription = mContentDescription
            }

            if (!TextUtils.isEmpty(mLearnMoreText)) {
                footerPreference.setLearnMoreText(mLearnMoreText)
            }
            return footerPreference
        }
    }

    /**
     * A [URLSpan] that opens a support page when clicked
     */
    internal class FooterLearnMoreSpan // sets the url to empty string so we can prevent any other span processing from
    // clearing things we need in this string.
        (private val mClickListener: View.OnClickListener?) : URLSpan("") {
        override fun onClick(widget: View) {
            mClickListener?.onClick(widget)
        }
    }

    companion object {
        const val KEY_FOOTER: String = "footer_preference"
        const val ORDER_FOOTER: Int = Int.MAX_VALUE - 1
    }
}
