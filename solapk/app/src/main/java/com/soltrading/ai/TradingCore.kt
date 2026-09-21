package com.soltrading.ai

import android.content.Context
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

enum class Decision { LONG, SHORT, WATCH, NO_TRADE }
enum class SetupType(val label: String) {
    OPENING_RANGE_BREAKOUT("Opening Range Breakout"),
    VWAP_RECLAIM("VWAP Reclaim"),
    TREND_PULLBACK("Trend Pullback"),
    BREAKOUT_RETEST("Breakout + Retest"),
    RELATIVE_STRENGTH("Relative Strength / Weakness"),
    NONE("No valid setup")
}
data class Quote(val symbol:String,val last:Double,val previousClose:Double,val bid:Double,val ask:Double,val relativeVolume:Double,val volume:Long) {
    val changePct get()=if(previousClose>0)(last-previousClose)/previousClose*100.0 else 0.0
    val spreadPct get()=if(last>0)(ask-bid).coerceAtLeast(0.0)/last*100.0 else 999.0
}
data class TradingSettings(
    val accountEquity:Double=25_000.0,
    val riskPerTradePct:Double=0.5,
    val dailyLossLimitPct:Double=2.0,
    val minimumRR:Double=2.0,
    val maxConcurrentTrades:Int=3,
    val maxConsecutiveLosses:Int=3
)
data class AnalysisResult(
    val symbol:String,val decision:Decision,val setup:SetupType,
    val entry:Double?,val stop:Double?,val target1:Double?,val target2:Double?,
    val rr:Double?,val shares:Int?,val confidence:Int,val marketRegime:String,
    val catalyst:String,val confirmations:List<String>,val invalidations:List<String>,
    val risks:List<String>,val reason:String
)
data class PaperTrade(
    val id:Long,val symbol:String,val side:String,val entry:Double,val stop:Double,
    val target:Double,val shares:Int,val createdAt:Long,val status:String="OPEN"
)

object DemoMarket {
    private val quotes=linkedMapOf(
        "SPY" to Quote("SPY",676.42,672.10,676.38,676.46,1.24,52_400_000),
        "QQQ" to Quote("QQQ",601.18,596.77,601.12,601.23,1.31,39_800_000),
        "NVDA" to Quote("NVDA",187.34,181.53,187.31,187.38,2.80,47_600_000),
        "AAPL" to Quote("AAPL",256.82,254.41,256.78,256.85,1.42,33_100_000),
        "AMD" to Quote("AMD",215.70,210.18,215.64,215.75,2.12,28_700_000),
        "MSFT" to Quote("MSFT",522.10,520.44,522.02,522.15,1.06,18_900_000),
        "TSLA" to Quote("TSLA",426.55,418.32,426.42,426.69,2.36,44_300_000)
    )
    fun all():List<Quote> = quotes.values.toList()
    fun quote(symbol:String):Quote? = quotes[symbol.trim().uppercase()]
    fun candles(symbol:String):List<Float>{
        val q=quote(symbol)?:return emptyList()
        val offsets=listOf(-1.6f,-1.1f,-1.35f,-0.7f,-0.25f,-0.55f,0.15f,0.6f,0.35f,1.1f,1.45f,1.2f,1.9f,2.3f,2.05f,2.7f,3.0f,2.65f,3.25f,3.55f)
        return offsets.map{(q.last+it).toFloat()}
    }
}

object TradingEngine {
    fun analyze(symbolInput:String,settings:TradingSettings):AnalysisResult{
        val symbol=symbolInput.trim().uppercase()
        val q=DemoMarket.quote(symbol)?:return noTrade(symbol,"Für dieses Symbol liegen im Demo-Modus keine Daten vor.")
        if(q.last<=0||q.bid<=0||q.ask<=0)return noTrade(symbol,"Marktdaten unvollständig.")
        if(q.spreadPct>0.25)return noTrade(symbol,"Spread ist zu groß ("+fmt(q.spreadPct)+"%).")
        if(q.volume<500_000||q.relativeVolume<0.8)return noTrade(symbol,"Liquidität/RVOL reicht nicht aus.")
        val setup=when(symbol){
            "NVDA"->SetupType.VWAP_RECLAIM
            "AMD"->SetupType.BREAKOUT_RETEST
            "AAPL"->SetupType.TREND_PULLBACK
            "TSLA"->SetupType.OPENING_RANGE_BREAKOUT
            else->SetupType.RELATIVE_STRENGTH
        }
        if(q.changePct<=0.5)return AnalysisResult(
            symbol,Decision.WATCH,setup,null,null,null,null,null,null,55,
            "NEUTRAL / RANGE","Technical demo context",
            listOf("Liquidität ausreichend","Spread akzeptabel"),
            listOf("Kein bestätigter Trigger"),
            listOf("Demo-Daten sind keine Live-Kurse"),
            "Struktur beobachtbar, aber aktuell kein freigegebener Entry."
        )
        val entry=q.last
        val stopDistance=max(entry*0.008,0.50)
        val stop=entry-stopDistance
        val target1=entry+stopDistance*settings.minimumRR
        val target2=entry+stopDistance*(settings.minimumRR+0.75)
        val rr=(target1-entry)/abs(entry-stop)
        if(rr<settings.minimumRR)return noTrade(symbol,"Reward/Risk unter Mindestwert.")
        val riskAmount=settings.accountEquity*settings.riskPerTradePct/100.0
        val rawShares=floor(riskAmount/abs(entry-stop)).toInt()
        val maxPositionShares=floor((settings.accountEquity*0.25)/entry).toInt()
        val shares=minOf(rawShares,maxPositionShares).coerceAtLeast(0)
        if(shares<=0)return noTrade(symbol,"Positionsgröße nicht sicher berechenbar.")
        val confidence=(62+((q.relativeVolume-1.0)*10).toInt()+if(q.changePct>2)8 else 3).coerceIn(0,92)
        return AnalysisResult(
            symbol,Decision.LONG,setup,entry,stop,target1,target2,rr,shares,confidence,
            "BULLISH / TREND","Technical momentum • DEMO",
            listOf(
                "Spread "+fmt(q.spreadPct)+"% innerhalb Limit",
                "RVOL "+fmt(q.relativeVolume),
                "Positive relative Stärke im Demo-Kontext",
                "R:R "+fmt(rr)+" ≥ "+fmt(settings.minimumRR)
            ),
            listOf("Trade-Idee ungültig unter "+money(stop)),
            listOf("DEMO DATA – keine Live-Kurse","Slippage kann reales Ergebnis verschlechtern"),
            "Regelbasiertes Demo-Setup mit bestätigter Liquidität, Struktur und Mindest-R:R."
        )
    }
    fun shortFrom(long:AnalysisResult):AnalysisResult{
        val e=long.entry?:return long.copy(decision=Decision.NO_TRADE)
        val s=long.stop?:return long.copy(decision=Decision.NO_TRADE)
        val risk=abs(e-s)
        return long.copy(
            decision=Decision.SHORT,stop=e+risk,
            target1=e-risk*(long.rr?:2.0),target2=e-risk*((long.rr?:2.0)+0.75),
            reason="Simulierter Short-Gegenentwurf. Nur PAPER TRADING."
        )
    }
    private fun noTrade(symbol:String,reason:String)=AnalysisResult(
        symbol.ifBlank{"—"},Decision.NO_TRADE,SetupType.NONE,null,null,null,null,null,null,0,
        "UNCLEAR","None",emptyList(),listOf(reason),
        listOf("Kein Trade ohne valide Daten und klaren Trigger"),reason
    )
}

class SettingsStore(context:Context){
    private val p=context.getSharedPreferences("sol_settings",Context.MODE_PRIVATE)
    fun load()=TradingSettings(
        accountEquity=p.getFloat("equity",25000f).toDouble(),
        riskPerTradePct=p.getFloat("risk",0.5f).toDouble(),
        dailyLossLimitPct=p.getFloat("daily",2f).toDouble(),
        minimumRR=p.getFloat("rr",2f).toDouble(),
        maxConcurrentTrades=p.getInt("maxTrades",3),
        maxConsecutiveLosses=p.getInt("losses",3)
    )
    fun save(s:TradingSettings){p.edit()
        .putFloat("equity",s.accountEquity.toFloat()).putFloat("risk",s.riskPerTradePct.toFloat())
        .putFloat("daily",s.dailyLossLimitPct.toFloat()).putFloat("rr",s.minimumRR.toFloat())
        .putInt("maxTrades",s.maxConcurrentTrades).putInt("losses",s.maxConsecutiveLosses).apply()}
}

class PaperTradeStore(context:Context){
    private val p=context.getSharedPreferences("sol_paper_trades",Context.MODE_PRIVATE)
    fun load():List<PaperTrade>{
        val raw=p.getStringSet("trades",emptySet()).orEmpty()
        return raw.mapNotNull{s->
            val a=s.split("|")
            runCatching{PaperTrade(a[0].toLong(),a[1],a[2],a[3].toDouble(),a[4].toDouble(),a[5].toDouble(),a[6].toInt(),a[7].toLong(),a.getOrElse(8){"OPEN"})}.getOrNull()
        }.sortedByDescending{it.createdAt}
    }
    fun add(t:PaperTrade){val set=p.getStringSet("trades",emptySet()).orEmpty().toMutableSet()
        set+=listOf(t.id,t.symbol,t.side,t.entry,t.stop,t.target,t.shares,t.createdAt,t.status).joinToString("|")
        p.edit().putStringSet("trades",set).apply()}
    fun clear()=p.edit().remove("trades").apply()
}
fun money(v:Double?):String=if(v==null)"—" else "$"+"%.2f".format(v)
fun fmt(v:Double):String="%.2f".format(v)
