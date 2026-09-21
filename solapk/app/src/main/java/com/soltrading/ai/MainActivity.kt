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
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val SolDark = darkColorScheme(
    primary = Color(0xFF77D5C1),
    secondary = Color(0xFFB7C9FF),
    tertiary = Color(0xFFFFCC80),
    background = Color(0xFF0A0F14),
    surface = Color(0xFF111820),
    surfaceVariant = Color(0xFF18222D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    outline = Color(0xFF5E6B78),
    error = Color(0xFFFFB4AB)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = SolDark) { SolTradingApp() } }
    }
}

@Composable
private fun SolTradingApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val autoStore = remember { AutoPaperStore(context) }

    var settings by remember { mutableStateOf(settingsStore.load()) }
    var account by remember { mutableStateOf(autoStore.loadAccount()) }
    var trades by remember { mutableStateOf(autoStore.loadTrades()) }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf("NVDA") }
    var analysis by remember {
        mutableStateOf(TradingEngine.analyze(selected, settings, account.tick))
    }

    fun persist(newAccount: PaperAccount, newTrades: List<PaperTrade>) {
        account = newAccount
        trades = newTrades
        autoStore.saveAccount(newAccount)
        autoStore.saveTrades(newTrades)
        analysis = TradingEngine.analyze(selected, settings, newAccount.tick)
    }

    fun analyze(symbol: String) {
        selected = symbol.trim().uppercase()
        analysis = TradingEngine.analyze(selected, settings, account.tick)
        tab = 1
    }

    fun addManual(result: AnalysisResult) {
        val e = result.entry ?: return
        val s = result.stop ?: return
        val t = result.target1 ?: return
        val n = result.shares ?: return
        if (result.decision != Decision.LONG && result.decision != Decision.SHORT) return
        if (trades.count { it.status == "OPEN" } >= settings.maxConcurrentTrades) return
        val trade = PaperTrade(
            id = System.currentTimeMillis(),
            symbol = result.symbol,
            side = result.decision.name,
            entry = e,
            stop = s,
            target = t,
            shares = n,
            createdAt = System.currentTimeMillis(),
            source = "MANUAL"
        )
        val next = listOf(trade) + trades
        autoStore.saveTrades(next)
        trades = next
        tab = 2
    }

    LaunchedEffect(account.autoEnabled, settings) {
        while (account.autoEnabled) {
            delay(5000)
            val step = AutoPaperTrader.step(account, trades, settings)
            persist(step.account, step.trades)
        }
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SOL Trading AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (account.autoEnabled) "AUTO PAPER • ON" else "AUTO PAPER • OFF",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (account.autoEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "DEMO DATA • 2.000 $ PAPER • LOCAL DECISION ENGINE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                listOf("Dashboard", "Analyse", "Journal", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(listOf("D", "A", "J", "S")[i], fontWeight = FontWeight.Bold) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DashboardScreen(
                    account = account,
                    trades = trades,
                    onAnalyze = { analyze(it) },
                    onAutoToggle = { enabled ->
                        val next = account.copy(
                            autoEnabled = enabled,
                            lastEvent = if (enabled) "AUTO PAPER gestartet" else "AUTO PAPER pausiert"
                        )
                        persist(next, trades)
                    }
                )
                1 -> AnalysisScreen(
                    initialSymbol = selected,
                    result = analysis,
                    tick = account.tick,
                    onAnalyze = { sym ->
                        selected = sym
                        analysis = TradingEngine.analyze(sym, settings, account.tick)
                    },
                    onPaper = { addManual(it) }
                )
                2 -> JournalScreen(
                    account = account,
                    trades = trades
                )
                else -> SettingsScreen(
                    settings = settings,
                    account = account,
                    onSave = {
                        settings = it
                        settingsStore.save(it)
                        analysis = TradingEngine.analyze(selected, it, account.tick)
                    },
                    onReset = {
                        autoStore.reset()
                        val fresh = PaperAccount()
                        val freshSettings = settings.copy(accountEquity = 2_000.0, maxConcurrentTrades = 2)
                        settingsStore.save(freshSettings)
                        settings = freshSettings
                        persist(fresh, emptyList())
                    }
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    account: PaperAccount,
    trades: List<PaperTrade>,
    onAnalyze: (String) -> Unit,
    onAutoToggle: (Boolean) -> Unit
) {
    val equity = AutoPaperTrader.equity(account, trades)
    val openTrades = trades.count { it.status == "OPEN" }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("Auto Paper Account")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("PAPER EQUITY", style = MaterialTheme.typography.labelMedium)
                            Text(money(equity), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        Switch(
                            checked = account.autoEnabled,
                            onCheckedChange = onAutoToggle,
                            enabled = !account.tradingLocked
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Start: " + money(account.startingBalance))
                        Text("Realized: " + signedMoney(account.realizedPnl))
                    }
                    Text("Offene Trades: " + openTrades + " • Tick: " + account.tick)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = if (account.tradingLocked) MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            account.lastEvent,
                            Modifier.fillMaxWidth().padding(10.dp),
                            color = if (account.tradingLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MarketCard(DemoMarket.quote("SPY", account.tick)!!, Modifier.weight(1f))
                MarketCard(DemoMarket.quote("QQQ", account.tick)!!, Modifier.weight(1f))
            }
        }

        item { SectionTitle("Auto Scan Watchlist") }

        items(DemoMarket.all(account.tick).filter { it.symbol != "SPY" && it.symbol != "QQQ" }) { q ->
            val r = TradingEngine.analyze(q.symbol, TradingSettings(accountEquity = equity), account.tick)
            Card(onClick = { onAnalyze(q.symbol) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.symbol, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(money(q.last) + "  " + signedPct(q.changePct))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("RVOL " + fmt(q.relativeVolume), fontWeight = FontWeight.SemiBold)
                        Text(r.setup.label, style = MaterialTheme.typography.labelSmall)
                        Text(r.decision.name.replace('_', ' '), color = decisionColor(r.decision), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            WarningCard(
                "Nur Simulation",
                "Die Automatik handelt ausschließlich synthetische Demo-Daten mit virtuellem Geld. Keine Broker-Verbindung, keine echten Orders, keine Live-Kurse."
            )
        }
    }
}

@Composable
private fun MarketCard(q: Quote, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(q.symbol, fontWeight = FontWeight.Bold)
            Text(money(q.last), style = MaterialTheme.typography.titleLarge)
            Text(signedPct(q.changePct), color = if (q.changePct >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AnalysisScreen(
    initialSymbol: String,
    result: AnalysisResult,
    tick: Long,
    onAnalyze: (String) -> Unit,
    onPaper: (AnalysisResult) -> Unit
) {
    var symbol by rememberSaveable(initialSymbol) { mutableStateOf(initialSymbol) }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("Decision Engine")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase().take(6) },
                    label = { Text("Symbol") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onAnalyze(symbol) }) { Text("SCAN") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(result.symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(result.setup.label, color = MaterialTheme.colorScheme.secondary)
                        }
                        DecisionBadge(result.decision)
                    }
                    Spacer(Modifier.height(12.dp))
                    MetricGrid(result)
                }
            }
        }

        if (DemoMarket.candles(result.symbol, tick).isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Synthetic Intraday Chart", fontWeight = FontWeight.Bold)
                        Text("Demo-Preisreihe • kein Live-Markt", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(8.dp))
                        DemoChart(DemoMarket.candles(result.symbol, tick))
                    }
                }
            }
        }

        if (result.confirmations.isNotEmpty()) item { ListCard("CONFIRMATION", result.confirmations) }
        if (result.invalidations.isNotEmpty()) item { ListCard("INVALIDATION", result.invalidations) }
        if (result.risks.isNotEmpty()) item { ListCard("RISKS", result.risks) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("ENGINE SUMMARY", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(result.reason)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Confidence = interner Setup-Score, keine Gewinnwahrscheinlichkeit.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        item {
            Button(
                onClick = { onPaper(result) },
                enabled = result.decision == Decision.LONG || result.decision == Decision.SHORT,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("PAPER TRADE JETZT SIMULIEREN")
            }
        }
    }
}

@Composable
private fun MetricGrid(r: AnalysisResult) {
    val rows = listOf(
        "ENTRY" to money(r.entry), "STOP" to money(r.stop),
        "TARGET" to money(r.target1), "R:R" to (r.rr?.let { fmt(it) } ?: "—"),
        "POSITION" to (r.shares?.let { it.toString() + " Stk." } ?: "—"),
        "CONFIDENCE" to (if (r.confidence > 0) r.confidence.toString() + "/100" else "—")
    )

    rows.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (k, v) ->
                Surface(
                    Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(k, style = MaterialTheme.typography.labelSmall)
                        Text(v, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun JournalScreen(account: PaperAccount, trades: List<PaperTrade>) {
    val equity = AutoPaperTrader.equity(account, trades)
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionTitle("Auto Paper Journal")
            InfoCard(
                "ACCOUNT",
                money(equity),
                "Start " + money(account.startingBalance) + " • Realized " + signedMoney(account.realizedPnl)
            )
        }

        if (trades.isEmpty()) {
            item { WarningCard("Noch keine Trades", "Der Auto-Agent scannt alle 5 Sekunden nach validierten Demo-Setups.") }
        } else {
            items(trades) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t.symbol + " • " + t.side, fontWeight = FontWeight.Bold)
                            Text(
                                t.status,
                                color = when (t.status) {
                                    "WIN" -> MaterialTheme.colorScheme.primary
                                    "LOSS" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("Entry " + money(t.entry) + " • Stop " + money(t.stop))
                        Text("Target " + money(t.target) + " • Size " + t.shares)
                        if (t.exit != null) Text("Exit " + money(t.exit) + " • P&L " + signedMoney(t.pnl ?: 0.0))
                        Text(t.source + " • " + formatTime(t.createdAt), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: TradingSettings,
    account: PaperAccount,
    onSave: (TradingSettings) -> Unit,
    onReset: () -> Unit
) {
    var risk by remember(settings) { mutableStateOf(settings.riskPerTradePct.toString()) }
    var daily by remember(settings) { mutableStateOf(settings.dailyLossLimitPct.toString()) }
    var rr by remember(settings) { mutableStateOf(settings.minimumRR.toString()) }
    var maxTrades by remember(settings) { mutableStateOf(settings.maxConcurrentTrades.toString()) }
    var losses by remember(settings) { mutableStateOf(settings.maxConsecutiveLosses.toString()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionTitle("Auto Risk Settings")
            InfoCard("PAPER START CAPITAL", "$2,000.00", "Festes Simulationskonto für diese Version")
        }
        item { NumberField("Risk per Trade %", risk) { risk = it } }
        item { NumberField("Daily Loss Limit %", daily) { daily = it } }
        item { NumberField("Minimum R:R", rr) { rr = it } }
        item { NumberField("Max open Trades", maxTrades) { maxTrades = it } }
        item { NumberField("Max consecutive Losses", losses) { losses = it } }

        item {
            Button(
                onClick = {
                    onSave(
                        TradingSettings(
                            accountEquity = 2_000.0,
                            riskPerTradePct = risk.toDoubleOrNull()?.coerceIn(0.1, 2.0) ?: settings.riskPerTradePct,
                            dailyLossLimitPct = daily.toDoubleOrNull()?.coerceIn(0.5, 5.0) ?: settings.dailyLossLimitPct,
                            minimumRR = rr.toDoubleOrNull()?.coerceIn(1.5, 5.0) ?: settings.minimumRR,
                            maxConcurrentTrades = maxTrades.toIntOrNull()?.coerceIn(1, 3) ?: settings.maxConcurrentTrades,
                            maxConsecutiveLosses = losses.toIntOrNull()?.coerceIn(1, 5) ?: settings.maxConsecutiveLosses
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RISIKO-LIMITS SPEICHERN")
            }
        }

        item {
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("PAPER ACCOUNT AUF 2.000 $ ZURÜCKSETZEN")
            }
        }

        item {
            WarningCard(
                "Auto-Modus",
                "Der Agent arbeitet automatisch, solange die App geöffnet ist. Er stoppt bei Daily-Loss-Lock oder Verlustserie. Keine echte Broker-Anbindung."
            )
        }

        item {
            Text(
                "Aktueller Status: " + if (account.tradingLocked) "TRADING LOCKED" else "READY",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }.replace(',', '.')) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DemoChart(values: List<Float>) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Canvas(
        Modifier.fillMaxWidth().height(170.dp)
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
    ) {
        if (values.size < 2) return@Canvas
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val lo = values.minOrNull() ?: 0f
        val hi = values.maxOrNull() ?: 1f
        val range = max(0.01f, hi - lo)
        val step = size.width / (values.size - 1)
        for (i in 0 until values.lastIndex) {
            val x1 = i * step
            val x2 = (i + 1) * step
            val y1 = size.height - (values[i] - lo) / range * size.height
            val y2 = size.height - (values[i + 1] - lo) / range * size.height
            drawLine(line, Offset(x1, y1), Offset(x2, y2), strokeWidth = 5f)
        }
    }
}

@Composable
private fun DecisionBadge(d: Decision) {
    val c = decisionColor(d)
    Surface(color = c.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Text(
            d.name.replace('_', ' '),
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = c,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun decisionColor(d: Decision): Color = when (d) {
    Decision.LONG -> MaterialTheme.colorScheme.primary
    Decision.SHORT -> MaterialTheme.colorScheme.error
    Decision.WATCH -> MaterialTheme.colorScheme.secondary
    Decision.NO_TRADE -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun InfoCard(title: String, value: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ListCard(title: String, rows: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            rows.forEach {
                Text("• " + it, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun WarningCard(title: String, text: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            Text(text)
        }
    }
}

private fun formatTime(ms: Long): String {
    val f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    return f.format(Instant.ofEpochMilli(ms))
}
