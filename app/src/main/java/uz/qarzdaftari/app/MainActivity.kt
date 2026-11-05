package uz.qarzdaftari.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

private const val BG = 0xFF0B1220.toInt()
private const val PANEL = 0xFF151F32.toInt()
private const val GREEN = 0xFF20C997.toInt()
private const val WHITE = 0xFFF4F7FB.toInt()
private const val MUTED = 0xFF9AA8BC.toInt()

class DebtDb(context: Activity) : SQLiteOpenHelper(context, "qarz_daftar.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE debts(id INTEGER PRIMARY KEY AUTOINCREMENT, person TEXT NOT NULL, amount INTEGER NOT NULL, borrowed TEXT, due TEXT, phone TEXT, note TEXT, active INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE payments(id INTEGER PRIMARY KEY AUTOINCREMENT, debt_id INTEGER NOT NULL, amount INTEGER NOT NULL, paid_at TEXT NOT NULL, note TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun add(person:String, amount:Long, borrowed:String, due:String, phone:String, note:String) {
        val v=ContentValues().apply { put("person",person); put("amount",amount); put("borrowed",borrowed); put("due",due); put("phone",phone); put("note",note) }
        writableDatabase.insert("debts",null,v)
    }
    fun pay(id:Long, amount:Long, date:String, note:String) {
        val v=ContentValues().apply { put("debt_id",id); put("amount",amount); put("paid_at",date); put("note",note) }
        writableDatabase.insert("payments",null,v)
        val total=readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM payments WHERE debt_id=?", arrayOf(id.toString())).use { it.moveToFirst(); it.getLong(0) }
        val original=readableDatabase.rawQuery("SELECT amount FROM debts WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst(); it.getLong(0) }
        val cv=ContentValues().apply { put("active", if(total>=original) 0 else 1) }
        writableDatabase.update("debts",cv,"id=?", arrayOf(id.toString()))
    }
    fun rows(filter:String): List<DebtRow> {
        val result= mutableListOf<DebtRow>()
        val where=when(filter) { "active" -> "WHERE d.active=1"; "closed" -> "WHERE d.active=0"; else -> "" }
        readableDatabase.rawQuery("SELECT d.id,d.person,d.amount,d.borrowed,d.due,COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.debt_id=d.id),0) paid,d.active FROM debts d $where ORDER BY d.id DESC",null).use { c ->
            while(c.moveToNext()) result.add(DebtRow(c.getLong(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getLong(5),c.getInt(6)==1))
        }
        return result
    }
    fun payments(id:Long):String {
        val lines= mutableListOf<String>()
        readableDatabase.rawQuery("SELECT amount,paid_at,note FROM payments WHERE debt_id=? ORDER BY id DESC",arrayOf(id.toString())).use { c ->
            while(c.moveToNext()) lines.add("• ${money(c.getLong(0))} — ${c.getString(1)}" + if(c.getString(2).isNullOrBlank()) "" else "\n  ${c.getString(2)}")
        }
        return if(lines.isEmpty()) "Hozircha to'lov kiritilmagan" else lines.joinToString("\n\n")
    }
    fun summary():Triple<Long,Long,Int> {
        val total=readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM debts",null).use { it.moveToFirst(); it.getLong(0) }
        val paid=readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM payments",null).use { it.moveToFirst(); it.getLong(0) }
        val count=readableDatabase.rawQuery("SELECT COUNT(*) FROM debts WHERE active=1",null).use { it.moveToFirst(); it.getInt(0) }
        return Triple(total-paid,paid,count)
    }
}
data class DebtRow(val id:Long,val person:String,val amount:Long,val borrowed:String,val due:String,val paid:Long,val active:Boolean) {
    val remaining get() = (amount-paid).coerceAtLeast(0)
}
fun money(v:Long)=String.format("%,d",v).replace(',',' ')+" so'm"

class MainActivity : Activity() {
    private lateinit var db:DebtDb
    private lateinit var root:LinearLayout
    private var filter="active"
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        db=DebtDb(this)
        window.statusBarColor=BG; window.navigationBarColor=BG
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(18,18,18,12) }
        setContentView(root)
        render()
    }
    private fun text(s:String,size:Float,color:Int=WHITE,bold:Boolean=false):TextView = TextView(this).apply {
        text=s; textSize=size; setTextColor(color); if(bold) setTypeface(null,Typeface.BOLD)
    }
    private fun button(s:String, onClick:()->Unit):Button = Button(this).apply {
        text=s; isAllCaps=false; setTextColor(WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN)); setOnClickListener { onClick() }
    }
    private fun render() {
        root.removeAllViews()
        root.addView(text("QARZ DAFTARI",22f,GREEN,true))
        root.addView(text("Shaxsiy moliyaviy nazorat",13f,MUTED))
        val stats=db.summary()
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18,16,18,16); setBackgroundColor(PANEL) }
        card.addView(text("JAMI QOLGAN QARZ",12f,MUTED,true))
        card.addView(text(money(stats.first),28f,WHITE,true))
        val line=TextView(this).apply { text="To'langan: ${money(stats.second)}     •     Faol qarzlar: ${stats.third}"; textSize=12f; setTextColor(GREEN); setPadding(0,8,0,0) }
        card.addView(line)
        val lp=LinearLayout.LayoutParams(-1,-2); lp.topMargin=18; root.addView(card,lp)
        val add=button("+  Yangi qarz qo'shish") { addDebt() }
        val ap=LinearLayout.LayoutParams(-1,-2); ap.topMargin=14; root.addView(add,ap)
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(0,12,0,8) }
        listOf("active" to "Faol", "closed" to "Yopilgan", "all" to "Barchasi").forEach { (key,label) ->
            val b=Button(this).apply { text=label; isAllCaps=false; textSize=12f; setTextColor(if(filter==key) GREEN else MUTED); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { filter=key; render() } }
            tabs.addView(b,LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(tabs)
        val scroll=ScrollView(this)
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        db.rows(filter).forEach { d ->
            val item=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(15,13,15,13); setBackgroundColor(PANEL) }
            item.addView(text(d.person,17f,WHITE,true))
            item.addView(text("Qolgan: ${money(d.remaining)}",16f,GREEN,true))
            item.addView(text("Olingan: ${money(d.amount)}  •  To'langan: ${money(d.paid)}",12f,MUTED))
            item.addView(text("Sana: ${d.borrowed}  •  Muddat: ${d.due}",12f,MUTED))
            item.addView(text(if(!d.active) "To'liq yopilgan" else if(d.due.isNotBlank() && d.due < java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())) "MUDDATI O'TGAN" else "Faol qarz",11f,if(!d.active) MUTED else if(d.due.isNotBlank() && d.due < java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())) 0xFFFF7777.toInt() else GREEN,true))
            item.setOnClickListener { detail(d) }
            item.setOnLongClickListener { if(d.active) addPayment(d); true }
            val p=LinearLayout.LayoutParams(-1,-2); p.bottomMargin=10; list.addView(item,p)
        }
        if(db.rows(filter).isEmpty()) list.addView(text("Hozircha qarzlar yo'q.\nYangi qarz qo'shish tugmasini bosing.",14f,MUTED).apply { gravity=Gravity.CENTER; setPadding(12,35,12,35) })
        scroll.addView(list); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(text("Maslahat: to'lov kiritish uchun qarz kartasini bosib, «To'lov kiritish»ni tanlang.",11f,MUTED).apply { setPadding(0,8,0,0) })
    }
    private fun field(hint:String):EditText=EditText(this).apply { this.hint=hint; textSize=15f; setSingleLine(true) }
    private fun addDebt() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,4,20,0) }
        val person=field("Kimdan qarz oldingiz?"); val amount=field("Summa (so'm)"); amount.inputType=2
        val borrowed=field("Olingan sana (YYYY-MM-DD)"); borrowed.setText(today())
        val due=field("Qaytarish muddati (YYYY-MM-DD)")
        val phone=field("Telefon (ixtiyoriy)"); phone.inputType=3
        val note=field("Izoh (ixtiyoriy)")
        listOf(person,amount,borrowed,due,phone,note).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Yangi qarz").setView(box).setNegativeButton("Bekor qilish",null).setPositiveButton("Saqlash") { _,_ ->
            val a=amount.text.toString().toLongOrNull()
            if(person.text.isNullOrBlank() || a==null || a<=0) { toast("Ism va to'g'ri summani kiriting"); return@setPositiveButton }
            db.add(person.text.toString().trim(),a,borrowed.text.toString(),due.text.toString(),phone.text.toString(),note.text.toString()); render()
        }.show()
    }
    private fun addPayment(d:DebtRow) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,4,20,0) }
        val amount=field("To'lov summasi (so'm)"); amount.inputType=2
        val date=field("To'lov sanasi (YYYY-MM-DD)"); date.setText(today())
        val note=field("Izoh (ixtiyoriy)")
        listOf(amount,date,note).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("To'lov — ${d.person}").setMessage("Qolgan qarz: ${money(d.remaining)}").setView(box).setNegativeButton("Bekor qilish",null).setPositiveButton("Saqlash") { _,_ ->
            val a=amount.text.toString().toLongOrNull()
            if(a==null || a<=0 || a>d.remaining) { toast("Summani tekshiring. Eng ko'pi ${money(d.remaining)}"); return@setPositiveButton }
            db.pay(d.id,a,date.text.toString(),note.text.toString()); render()
        }.show()
    }
    private fun detail(d:DebtRow) {
        val msg="Qarz oluvchi: ${d.person}\nAsl summa: ${money(d.amount)}\nTo'langan: ${money(d.paid)}\nQolgan: ${money(d.remaining)}\nOlingan sana: ${d.borrowed}\nQaytarish muddati: ${d.due}\n\nTO'LOVLAR TARIXI\n${db.payments(d.id)}"
        val b=AlertDialog.Builder(this).setTitle("Qarz tafsilotlari").setMessage(msg).setNegativeButton("Yopish",null)
        if(d.active) b.setPositiveButton("To'lov kiritish") { _,_ -> addPayment(d) }
        b.show()
    }
    private fun today()=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(java.util.Date())
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
