package com.soltrading.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

private val SolDark=darkColorScheme(
    primary=Color(0xFF77D5C1),secondary=Color(0xFFB7C9FF),
    tertiary=Color(0xFFFFCC80),background=Color(0xFF0A0F14),
    surface=Color(0xFF111820),surfaceVariant=Color(0xFF18222D),
    onBackground=Color(0xFFE6EDF3),onSurface=Color(0xFFE6EDF3),
    outline=Color(0xFF5E6B78),error=Color(0xFFFFB4AB)
)

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        setContent{MaterialTheme(colorScheme=SolDark){SolTradingApp()}}
    }
}

@Composable
private fun SolTradingApp(){
    val context=androidx.compose.ui.platform.LocalContext.current
    val settingsStore=remember{SettingsStore(context)}
    val tradeStore=remember{PaperTradeStore(context)}
    var settings by remember{mutableStateOf(settingsStore.load())}
    var trades by remember{mutableStateOf(tradeStore.load())}
    var tab by rememberSaveable{mutableIntStateOf(0)}
    var selected by rememberSaveable{mutableStateOf("NVDA")}
    var analysis by remember{mutableStateOf(TradingEngine.analyze(selected,settings))}

    fun analyze(symbol:String){
        selected=symbol.trim().uppercase()
        analysis=TradingEngine.analyze(selected,settings)
        tab=1
    }
    fun paper(side:String,result:AnalysisResult){
        val r=if(side=="SHORT")TradingEngine.shortFrom(result) else result
        val e=r.entry?:return
        val s=r.stop?:return
        val t=r.target1?:return
        val n=r.shares?:return
        tradeStore.add(PaperTrade(System.currentTimeMillis(),r.symbol,side,e,s,t,n,System.currentTimeMillis()))
        trades=tradeStore.load()
        tab=2
    }

    Scaffold(
        topBar={
            Surface(shadowElevation=3.dp){
                Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                        Text("SOL Trading AI",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text("MARKET • DEMO",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("DEMO DATA",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.tertiary)
                        Text("  •  ANALYSE + PAPER TRADING",style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        bottomBar={
            NavigationBar{
                listOf("Dashboard","Analyse","Journal","Settings").forEachIndexed{i,label->
                    NavigationBarItem(
                        selected=tab==i,onClick={tab=i},
                        icon={Text(listOf("D","A","J","S")[i],fontWeight=FontWeight.Bold)},
                        label={Text(label)}
                    )
                }
            }
        }
    ){padding->
        Box(Modifier.padding(padding).fillMaxSize()){
            when(tab){
                0->DashboardScreen(onAnalyze={analyze(it)})
                1->AnalysisScreen(
                    initialSymbol=selected,result=analysis,
                    onAnalyze={sym->selected=sym;analysis=TradingEngine.analyze(sym,settings)},
                    onPaperLong={paper("LONG",it)},onPaperShort={paper("SHORT",it)}
                )
                2->JournalScreen(trades=trades,onClear={tradeStore.clear();trades=emptyList()})
                else->SettingsScreen(settings=settings,onSave={
                    settings=it;settingsStore.save(it);analysis=TradingEngine.analyze(selected,it)
                })
            }
        }
    }
}

@Composable
private fun DashboardScreen(onAnalyze:(String)->Unit){
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)
    ){
        item{
            SectionTitle("Market Overview")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                MarketCard(DemoMarket.quote("SPY")!!,Modifier.weight(1f))
                MarketCard(DemoMarket.quote("QQQ")!!,Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            InfoCard("MARKTREGIME","BULLISH / TREND","Volatilität: NORMAL • Demo-Kontext")
        }
        item{SectionTitle("Watchlist")}
        items(DemoMarket.all().filter{it.symbol!="SPY"&&it.symbol!="QQQ"}){q->
            Card(onClick={onAnalyze(q.symbol)},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(14.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text(q.symbol,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                        Text(money(q.last)+"  "+signed(q.changePct),style=MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment=Alignment.End){
                        Text("RVOL "+fmt(q.relativeVolume),fontWeight=FontWeight.SemiBold)
                        Text(setupFor(q.symbol),style=MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("WATCH",color=MaterialTheme.colorScheme.secondary,fontWeight=FontWeight.Bold)
                }
            }
        }
        item{
            WarningCard("Demo-Modus","Alle angezeigten Kurse sind fest definierte Simulationsdaten und dürfen nicht als Live-Marktdaten interpretiert werden.")
        }
    }
}

@Composable
private fun MarketCard(q:Quote,modifier:Modifier=Modifier){
    Card(modifier){
        Column(Modifier.padding(14.dp)){
            Text(q.symbol,fontWeight=FontWeight.Bold)
            Text(money(q.last),style=MaterialTheme.typography.titleLarge)
            Text(signed(q.changePct),color=if(q.changePct>=0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AnalysisScreen(
    initialSymbol:String,result:AnalysisResult,
    onAnalyze:(String)->Unit,onPaperLong:(AnalysisResult)->Unit,onPaperShort:(AnalysisResult)->Unit
){
    var symbol by rememberSaveable(initialSymbol){mutableStateOf(initialSymbol)}
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)
    ){
        item{
            SectionTitle("AI + Rule Analysis")
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                OutlinedTextField(
                    value=symbol,onValueChange={symbol=it.uppercase().take(6)},
                    label={Text("Symbol")},singleLine=true,
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Characters),
                    modifier=Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick={onAnalyze(symbol)}){Text("ANALYSIEREN")}
            }
        }
        item{
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Column{
                            Text(result.symbol,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                            Text(result.setup.label,color=MaterialTheme.colorScheme.secondary)
                        }
                        DecisionBadge(result.decision)
                    }
                    Spacer(Modifier.height(12.dp))
                    MetricGrid(result)
                }
            }
        }
        if(DemoMarket.candles(result.symbol).isNotEmpty()){
            item{
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(14.dp)){
                        Text("5m Demo Chart",fontWeight=FontWeight.Bold)
                        Text("Visualisierung synthetischer Demo-Punkte",style=MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        DemoChart(DemoMarket.candles(result.symbol))
                    }
                }
            }
        }
        item{
            InfoCard("MARKET REGIME",result.marketRegime,result.catalyst)
        }
        if(result.confirmations.isNotEmpty())item{ListCard("CONFIRMATION",result.confirmations)}
        if(result.invalidations.isNotEmpty())item{ListCard("INVALIDATION",result.invalidations)}
        if(result.risks.isNotEmpty())item{ListCard("RISKS",result.risks)}
        item{
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp)){
                    Text("REASON",fontWeight=FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(result.reason)
                    Spacer(Modifier.height(10.dp))
                    Text("Confidence ist ein interner Setup-Qualitätswert – keine Gewinnwahrscheinlichkeit.",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(
                    onClick={onPaperLong(result)},enabled=result.decision==Decision.LONG,
                    modifier=Modifier.weight(1f)
                ){Text("PAPER LONG")}
                OutlinedButton(
                    onClick={onPaperShort(result)},enabled=result.entry!=null&&result.stop!=null,
                    modifier=Modifier.weight(1f)
                ){Text("PAPER SHORT")}
            }
            Spacer(Modifier.height(4.dp))
            Text("Diese Buttons erzeugen ausschließlich virtuelle Trades. Es wird keine Broker-Order gesendet.",style=MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MetricGrid(r:AnalysisResult){
    val rows=listOf(
        "ENTRY" to money(r.entry),"STOP" to money(r.stop),
        "TARGET 1" to money(r.target1),"TARGET 2" to money(r.target2),
        "R:R" to (r.rr?.let{fmt(it)}?:"—"),"POSITION" to (r.shares?.let{it.toString()+" shares"}?:"—"),
        "CONFIDENCE" to (if(r.confidence>0)r.confidence.toString()+"/100" else "—"),"MODE" to "PAPER"
    )
    rows.chunked(2).forEach{pair->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            pair.forEach{(k,v)->
                Surface(Modifier.weight(1f),shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceVariant){
                    Column(Modifier.padding(10.dp)){
                        Text(k,style=MaterialTheme.typography.labelSmall)
                        Text(v,fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun JournalScreen(trades:List<PaperTrade>,onClear:()->Unit){
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                SectionTitle("Paper Journal")
                if(trades.isNotEmpty())TextButton(onClick=onClear){Text("Löschen")}
            }
            InfoCard("SAMPLE SIZE",trades.size.toString()+" Trades",if(trades.size<30)"Zu kleine Stichprobe für belastbare Statistik." else "Größere Stichprobe – Ergebnisse trotzdem kritisch prüfen.")
        }
        if(trades.isEmpty())item{
            WarningCard("Noch keine Paper Trades","Öffne die Analyse und lege einen simulierten Trade an.")
        } else items(trades){t->
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(14.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text(t.symbol+" • "+t.side,fontWeight=FontWeight.Bold)
                        Text(t.status,color=MaterialTheme.colorScheme.tertiary)
                    }
                    Text("Entry "+money(t.entry)+"  Stop "+money(t.stop))
                    Text("Target "+money(t.target)+"  Size "+t.shares)
                    Text(formatTime(t.createdAt),style=MaterialTheme.typography.labelSmall)
                    Text("SIMULATED TRADE",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings:TradingSettings,onSave:(TradingSettings)->Unit){
    var equity by remember(settings){mutableStateOf(settings.accountEquity.toString())}
    var risk by remember(settings){mutableStateOf(settings.riskPerTradePct.toString())}
    var daily by remember(settings){mutableStateOf(settings.dailyLossLimitPct.toString())}
    var rr by remember(settings){mutableStateOf(settings.minimumRR.toString())}
    var maxTrades by remember(settings){mutableStateOf(settings.maxConcurrentTrades.toString())}
    var losses by remember(settings){mutableStateOf(settings.maxConsecutiveLosses.toString())}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{SectionTitle("Risk Settings")}
        item{NumberField("Account Size",equity){equity=it}}
        item{NumberField("Risk per Trade %",risk){risk=it}}
        item{NumberField("Daily Loss Limit %",daily){daily=it}}
        item{NumberField("Minimum R:R",rr){rr=it}}
        item{NumberField("Max open Trades",maxTrades){maxTrades=it}}
        item{NumberField("Max consecutive Losses",losses){losses=it}}
        item{
            Button(onClick={
                onSave(TradingSettings(
                    accountEquity=equity.toDoubleOrNull()?.coerceAtLeast(100.0)?:settings.accountEquity,
                    riskPerTradePct=risk.toDoubleOrNull()?.coerceIn(0.1,5.0)?:settings.riskPerTradePct,
                    dailyLossLimitPct=daily.toDoubleOrNull()?.coerceIn(0.5,20.0)?:settings.dailyLossLimitPct,
                    minimumRR=rr.toDoubleOrNull()?.coerceIn(1.0,10.0)?:settings.minimumRR,
                    maxConcurrentTrades=maxTrades.toIntOrNull()?.coerceIn(1,10)?:settings.maxConcurrentTrades,
                    maxConsecutiveLosses=losses.toIntOrNull()?.coerceIn(1,10)?:settings.maxConsecutiveLosses
                ))
            },modifier=Modifier.fillMaxWidth()){Text("SETTINGS SPEICHERN")}
        }
        item{
            WarningCard("Security","Diese installierbare Demo-Version enthält keine API-Schlüssel und keine Broker-Anbindung. Echte Orders sind nicht implementiert.")
        }
    }
}

@Composable
private fun NumberField(label:String,value:String,onChange:(String)->Unit){
    OutlinedTextField(
        value=value,onValueChange={onChange(it.filter{c->c.isDigit()||c=='.'||c==','}.replace(',','.'))},
        label={Text(label)},singleLine=true,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
        modifier=Modifier.fillMaxWidth()
    )
}

@Composable
private fun DemoChart(values:List<Float>){
    val line=MaterialTheme.colorScheme.primary
    val grid=MaterialTheme.colorScheme.outline.copy(alpha=0.25f)
    Canvas(Modifier.fillMaxWidth().height(170.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))){
        if(values.size<2)return@Canvas
        for(i in 1..3){
            val y=size.height*i/4f
            drawLine(grid,Offset(0f,y),Offset(size.width,y),strokeWidth=1f)
        }
        val lo=values.minOrNull()?:0f
        val hi=values.maxOrNull()?:1f
        val range=max(0.01f,hi-lo)
        val step=size.width/(values.size-1)
        for(i in 0 until values.lastIndex){
            val x1=i*step
            val x2=(i+1)*step
            val y1=size.height-(values[i]-lo)/range*size.height
            val y2=size.height-(values[i+1]-lo)/range*size.height
            drawLine(line,Offset(x1,y1),Offset(x2,y2),strokeWidth=5f)
        }
    }
}

@Composable
private fun DecisionBadge(d:Decision){
    val c=when(d){
        Decision.LONG->MaterialTheme.colorScheme.primary
        Decision.SHORT->MaterialTheme.colorScheme.error
        Decision.WATCH->MaterialTheme.colorScheme.secondary
        Decision.NO_TRADE->MaterialTheme.colorScheme.tertiary
    }
    Surface(color=c.copy(alpha=0.15f),shape=RoundedCornerShape(50)){
        Text(d.name.replace('_',' '),Modifier.padding(horizontal=12.dp,vertical=7.dp),color=c,fontWeight=FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text:String){
    Text(text,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}
@Composable
private fun InfoCard(title:String,value:String,detail:String){
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp)){
            Text(title,style=MaterialTheme.typography.labelMedium)
            Text(value,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            if(detail.isNotBlank())Text(detail,style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun ListCard(title:String,items:List<String>){
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp)){
            Text(title,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            items.forEach{Text("• "+it,style=MaterialTheme.typography.bodyMedium);Spacer(Modifier.height(3.dp))}
        }
    }
}
@Composable
private fun WarningCard(title:String,text:String){
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.tertiary.copy(alpha=0.10f)){
        Column(Modifier.padding(14.dp)){Text(title,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.tertiary);Text(text)}
    }
}
private fun signed(v:Double)=if(v>=0)"+"+fmt(v)+"%" else fmt(v)+"%"
private fun setupFor(symbol:String)=when(symbol){
    "NVDA"->"VWAP Reclaim";"AMD"->"Breakout + Retest";"AAPL"->"Trend Pullback";"TSLA"->"Opening Range";else->"Relative Strength"
}
private fun formatTime(ms:Long):String{
    val f=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    return f.format(Instant.ofEpochMilli(ms))
}
