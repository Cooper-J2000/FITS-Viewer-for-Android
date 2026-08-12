package com.fitsviewer.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fitsviewer.app.fits.FitsCard

/**
 * Header 查看 + 关键词搜索 (匹配关键字/值/注释, 不区分大小写)。
 */
class HeaderActivity : AppCompatActivity() {

    private var allCards: List<FitsCard> = emptyList()
    private var shown: List<FitsCard> = emptyList()
    private lateinit var adapter: CardAdapter
    private lateinit var tvCount: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_header)

        val hdu = MainActivity.hduFromIntent(this) ?: run { finish(); return }
        title = "Header — [${hdu.index}] ${hdu.name}"
        allCards = hdu.header.cards
        shown = allCards
        tvCount = findViewById(R.id.tvCount)
        adapter = CardAdapter()
        findViewById<ListView>(R.id.lvCards).adapter = adapter
        updateCount()

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.uppercase() ?: ""
                shown = if (q.isEmpty()) allCards else allCards.filter {
                    it.key.uppercase().contains(q) ||
                            (it.value ?: "").uppercase().contains(q) ||
                            (it.comment ?: "").uppercase().contains(q)
                }
                adapter.notifyDataSetChanged()
                updateCount()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    @SuppressLint("SetTextI18n")
    private fun updateCount() {
        tvCount.text = "共 ${allCards.size} 张卡片, 显示 ${shown.size} 张"
    }

    private inner class CardAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@HeaderActivity)
                .inflate(R.layout.item_card, parent, false)
            view.findViewById<TextView>(R.id.tvCard).text = shown[position].displayString()
            return view
        }
    }
}
