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

data class Quote(
    val symbol: String,
    val last: Double,
    val previousClose: Double,
    val bid: Double,
    val ask: Double,
    val relativeVolume: Double,
    val volume: Long
) {
    val changePct get() = if (previousClose > 0) (last - previousClose) / previousClose * 100.0 else 0.0
    val spreadPct get() = if (last > 0) (ask - bid).coerceAtLeast(0.0) / last * 100.0 else 999.0
}

data class TradingSettings(
    val accountEquity: Double = 2_000.0,
    val riskPerTradePct: Double = 0.5,
    val dailyLossLimitPct: Double = 2.0,
    val minimumRR: Double = 2.0,
    val maxConcurrentTrades: Int = 2,
    val maxConsecutiveLosses: Int = 3
)

data class AnalysisResult(
    val symbol: String,
    val decision: Decision,
    val setup: SetupType,
    val entry: Double?,
    val stop: Double?,
    val target1: Double?,
    val target2: Double?,
    val rr: Double?,
    val shares: Int?,
    val confidence: Int,
    val marketRegime: String,
    val catalyst: String,
    val confirmations: List<String>,
    val invalidations: List<String>,
    val risks: List<String>,
    val reason: String
)

data class PaperTrade(
    val id: Long,
    val symbol: String,
    val side: String,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val shares: Int,
    val createdAt: Long,
    val status: String = "OPEN",
    val exit: Double? = null,
    val pnl: Double? = null,
    val closedAt: Long? = null,
    val source: String = "AUTO"
)

data class PaperAccount(
    val startingBalance: Double = 2_000.0,
    val cash: Double = 2_000.0,
    val realizedPnl: Double = 0.0,
    val autoEnabled: Boolean = true,
    val tradingLocked: Boolean = false,
    val consecutiveLosses: Int = 0,
    val tick: Long = 0,
    val lastEvent: String = "AI Auto Paper bereit"
)

object DemoMarket {
    private data class Base(
        val symbol: String,
        val price: Double,
        val previousClose: Double,
        val rvol: Double,
        val volume: Long,
        val phase: Int
    )

    private val bases = listOf(
        Base("SPY", 676.42, 672.10, 1.24, 52_400_000, 1),
        Base("QQQ", 601.18, 596.77, 1.31, 39_800_000, 2),
        Base("NVDA", 187.34, 181.53, 2.80, 47_600_000, 3),
        Base("AAPL", 256.82, 254.41, 1.42, 33_100_000, 5),
        Base("AMD", 215.70, 210.18, 2.12, 28_700_000, 7),
        Base("MSFT", 522.10, 520.44, 1.06, 18_900_000, 11),
        Base("TSLA", 426.55, 418.32, 2.36, 44_300_000, 13)
    )

    fun all(tick: Long = 0): List<Quote> = bases.map { quote(it.symbol, tick)!! }

    fun quote(symbol: String, tick: Long = 0): Quote? {
        val b = bases.firstOrNull { it.symbol == symbol.trim().uppercase() } ?: return null
        val p = dynamicPrice(b, tick)
        val spread = max(0.02, p * 0.00035)
        val rvolWave = (((tick + b.phase) % 7) - 3) * 0.04
        return Quote(
            symbol = b.symbol,
            last = p,
            previousClose = b.previousClose,
            bid = p - spread / 2.0,
            ask = p + spread / 2.0,
            relativeVolume = (b.rvol + rvolWave).coerceAtLeast(0.7),
            volume = b.volume + tick * 12_000
        )
    }

    fun candles(symbol: String, tick: Long = 0): List<Float> {
        val end = tick.coerceAtLeast(20)
        return (end - 19..end).mapNotNull { t -> quote(symbol, t)?.last?.toFloat() }
    }

    private fun dynamicPrice(b: Base, tick: Long): Double {
        if (tick <= 0) return b.price
        val cycle = ((tick + b.phase) % 24).toInt()
        val wave = when (cycle) {
            0 -> -1.9; 1 -> -1.4; 2 -> -0.9; 3 -> -0.4; 4 -> 0.1; 5 -> 0.7
            6 -> 1.3; 7 -> 1.9; 8 -> 2.5; 9 -> 3.0; 10 -> 3.4; 11 -> 3.0
            12 -> 2.4; 13 -> 1.8; 14 -> 1.1; 15 -> 0.4; 16 -> -0.2; 17 -> -0.8
            18 -> -1.4; 19 -> -2.0; 20 -> -2.5; 21 -> -2.1; 22 -> -1.3
            else -> -0.5
        }
        val amplitude = when (b.symbol) {
            "NVDA", "TSLA" -> 0.0065
            "AMD" -> 0.0055
            "AAPL" -> 0.0032
            else -> 0.0025
        }
        return (b.price * (1.0 + wave * amplitude)).coerceAtLeast(1.0)
    }
}

object TradingEngine {
    fun analyze(symbolInput: String, settings: TradingSettings, tick: Long = 0): AnalysisResult {
        val symbol = symbolInput.trim().uppercase()
        val q = DemoMarket.quote(symbol, tick)
            ?: return noTrade(symbol, "Für dieses Symbol liegen im Demo-Modus keine Daten vor.")
        if (q.last <= 0 || q.bid <= 0 || q.ask <= 0) return noTrade(symbol, "Marktdaten unvollständig.")
        if (q.spreadPct > 0.25) return noTrade(symbol, "Spread ist zu groß (" + fmt(q.spreadPct) + "%).")
        if (q.volume < 500_000 || q.relativeVolume < 0.8) return noTrade(symbol, "Liquidität/RVOL reicht nicht aus.")

        val setup = when (symbol) {
            "NVDA" -> SetupType.VWAP_RECLAIM
            "AMD" -> SetupType.BREAKOUT_RETEST
            "AAPL" -> SetupType.TREND_PULLBACK
            "TSLA" -> SetupType.OPENING_RANGE_BREAKOUT
            else -> SetupType.RELATIVE_STRENGTH
        }

        val momentum = q.changePct
        if (abs(momentum) < 0.65) return AnalysisResult(
            symbol, Decision.WATCH, setup, null, null, null, null, null, null, 55,
            "NEUTRAL / RANGE", "Synthetic technical demo context",
            listOf("Liquidität ausreichend", "Spread akzeptabel"),
            listOf("Kein bestätigter Momentum-Trigger"),
            listOf("DEMO DATA – keine Live-Kurse"),
            "Automatik wartet auf einen klareren Trigger."
        )

        val side = if (momentum > 0) Decision.LONG else Decision.SHORT
        val entry = q.last
        val stopDistance = max(entry * 0.008, 0.40)
        val stop = if (side == Decision.LONG) entry - stopDistance else entry + stopDistance
        val target1 = if (side == Decision.LONG) {
            entry + stopDistance * settings.minimumRR
        } else {
            entry - stopDistance * settings.minimumRR
        }
        val target2 = if (side == Decision.LONG) {
            entry + stopDistance * (settings.minimumRR + 0.75)
        } else {
            entry - stopDistance * (settings.minimumRR + 0.75)
        }
        val rr = abs(target1 - entry) / abs(entry - stop)
        if (rr < settings.minimumRR) return noTrade(symbol, "Reward/Risk unter Mindestwert.")

        val riskAmount = settings.accountEquity * settings.riskPerTradePct / 100.0
        val rawShares = floor(riskAmount / abs(entry - stop)).toInt()
        val maxPositionShares = floor((settings.accountEquity * 0.35) / entry).toInt()
        val shares = minOf(rawShares, maxPositionShares).coerceAtLeast(0)
        if (shares <= 0) return noTrade(symbol, "Positionsgröße mit 2.000 $ Paper Equity nicht sicher darstellbar.")

        val confidence = (
            64 +
                ((q.relativeVolume - 1.0) * 8).toInt() +
                if (abs(momentum) > 2.0) 9 else 4
            ).coerceIn(0, 93)

        return AnalysisResult(
            symbol, side, setup, entry, stop, target1, target2, rr, shares, confidence,
            if (side == Decision.LONG) "BULLISH / TREND" else "BEARISH / TREND",
            "Synthetic momentum • DEMO",
            listOf(
                "Spread " + fmt(q.spreadPct) + "% innerhalb Limit",
                "RVOL " + fmt(q.relativeVolume),
                "Momentum " + signedPct(momentum),
                "R:R " + fmt(rr) + " ≥ " + fmt(settings.minimumRR)
            ),
            listOf("Trade-Idee ungültig an Stop " + money(stop)),
            listOf("DEMO DATA – keine Live-Kurse", "Automatische Trades sind ausschließlich simuliert"),
            "Lokaler Decision-Engine-Score hat Setup, Liquidität, Momentum und R:R validiert."
        )
    }

    private fun noTrade(symbol: String, reason: String) = AnalysisResult(
        symbol.ifBlank { "—" }, Decision.NO_TRADE, SetupType.NONE,
        null, null, null, null, null, null, 0,
        "UNCLEAR", "None", emptyList(), listOf(reason),
        listOf("Kein Trade ohne valide Daten und klaren Trigger"), reason
    )
}

object AutoPaperTrader {
    private val universe = listOf("NVDA", "AMD", "AAPL", "TSLA", "MSFT")

    data class StepResult(
        val account: PaperAccount,
        val trades: List<PaperTrade>
    )

    fun step(
        current: PaperAccount,
        trades: List<PaperTrade>,
        settings: TradingSettings,
        now: Long = System.currentTimeMillis()
    ): StepResult {
        val tick = current.tick + 1
        var realized = current.realizedPnl
        var cash = current.cash
        var streak = current.consecutiveLosses
        var event = "Scan " + tick + ": kein neuer Trade"

        val updatedTrades = trades.map { trade ->
            if (trade.status != "OPEN") return@map trade
            val price = DemoMarket.quote(trade.symbol, tick)?.last ?: return@map trade
            val isLong = trade.side == "LONG"
            val stopHit = if (isLong) price <= trade.stop else price >= trade.stop
            val targetHit = if (isLong) price >= trade.target else price <= trade.target
            if (!stopHit && !targetHit) return@map trade

            val exit = if (stopHit) trade.stop else trade.target
            val pnl = if (isLong) {
                (exit - trade.entry) * trade.shares
            } else {
                (trade.entry - exit) * trade.shares
            }
            realized += pnl
            cash += pnl
            streak = if (pnl < 0) streak + 1 else 0
            event = trade.symbol + " " + trade.side + " geschlossen • P&L " + signedMoney(pnl)
            trade.copy(
                status = if (pnl >= 0) "WIN" else "LOSS",
                exit = exit,
                pnl = pnl,
                closedAt = now
            )
        }.toMutableList()

        val lossLock = realized <= -(current.startingBalance * settings.dailyLossLimitPct / 100.0)
        val streakLock = streak >= settings.maxConsecutiveLosses
        val locked = lossLock || streakLock
        if (locked) {
            event = if (lossLock) "TRADING LOCKED • Daily Loss Limit" else "COOLDOWN • Verlustserie"
        }

        val accountBeforeEntry = current.copy(
            cash = cash,
            realizedPnl = realized,
            tradingLocked = locked,
            consecutiveLosses = streak,
            tick = tick,
            lastEvent = event
        )

        if (!current.autoEnabled || locked) return StepResult(accountBeforeEntry, updatedTrades)

        val open = updatedTrades.count { it.status == "OPEN" }
        if (open >= settings.maxConcurrentTrades) {
            return StepResult(accountBeforeEntry.copy(lastEvent = "Scan " + tick + ": Max Open Trades erreicht"), updatedTrades)
        }

        val openSymbols = updatedTrades.filter { it.status == "OPEN" }.map { it.symbol }.toSet()
        val candidates = universe
            .filterNot { it in openSymbols }
            .map { TradingEngine.analyze(it, settings.copy(accountEquity = current.startingBalance + realized), tick) }
            .filter { it.decision == Decision.LONG || it.decision == Decision.SHORT }
            .sortedByDescending { it.confidence }

        val best = candidates.firstOrNull { it.confidence >= 72 }
            ?: return StepResult(accountBeforeEntry, updatedTrades)

        val entry = best.entry ?: return StepResult(accountBeforeEntry, updatedTrades)
        val stop = best.stop ?: return StepResult(accountBeforeEntry, updatedTrades)
        val target = best.target1 ?: return StepResult(accountBeforeEntry, updatedTrades)
        val shares = best.shares ?: return StepResult(accountBeforeEntry, updatedTrades)
        if (shares <= 0) return StepResult(accountBeforeEntry, updatedTrades)

        val trade = PaperTrade(
            id = now + tick,
            symbol = best.symbol,
            side = best.decision.name,
            entry = entry,
            stop = stop,
            target = target,
            shares = shares,
            createdAt = now,
            source = "AUTO"
        )
        updatedTrades.add(0, trade)
        val newEvent = "AUTO ENTRY • " + best.symbol + " " + best.decision.name + " • " + shares + " Stk."
        return StepResult(accountBeforeEntry.copy(lastEvent = newEvent), updatedTrades)
    }

    fun equity(account: PaperAccount, trades: List<PaperTrade>): Double {
        var unrealized = 0.0
        trades.filter { it.status == "OPEN" }.forEach { trade ->
            val p = DemoMarket.quote(trade.symbol, account.tick)?.last ?: trade.entry
            unrealized += if (trade.side == "LONG") {
                (p - trade.entry) * trade.shares
            } else {
                (trade.entry - p) * trade.shares
            }
        }
        return account.startingBalance + account.realizedPnl + unrealized
    }
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("sol_settings", Context.MODE_PRIVATE)

    fun load(): TradingSettings {
        if (!p.getBoolean("paper_2k_v2", false)) {
            p.edit()
                .putBoolean("paper_2k_v2", true)
                .putFloat("equity", 2000f)
                .putInt("maxTrades", 2)
                .apply()
        }
        return TradingSettings(
            accountEquity = p.getFloat("equity", 2000f).toDouble(),
            riskPerTradePct = p.getFloat("risk", 0.5f).toDouble(),
            dailyLossLimitPct = p.getFloat("daily", 2f).toDouble(),
            minimumRR = p.getFloat("rr", 2f).toDouble(),
            maxConcurrentTrades = p.getInt("maxTrades", 2),
            maxConsecutiveLosses = p.getInt("losses", 3)
        )
    }

    fun save(s: TradingSettings) {
        p.edit()
            .putFloat("equity", s.accountEquity.toFloat())
            .putFloat("risk", s.riskPerTradePct.toFloat())
            .putFloat("daily", s.dailyLossLimitPct.toFloat())
            .putFloat("rr", s.minimumRR.toFloat())
            .putInt("maxTrades", s.maxConcurrentTrades)
            .putInt("losses", s.maxConsecutiveLosses)
            .apply()
    }
}

class AutoPaperStore(context: Context) {
    private val p = context.getSharedPreferences("sol_auto_paper_v2", Context.MODE_PRIVATE)

    fun loadAccount(): PaperAccount = PaperAccount(
        startingBalance = p.getFloat("start", 2000f).toDouble(),
        cash = p.getFloat("cash", 2000f).toDouble(),
        realizedPnl = p.getFloat("realized", 0f).toDouble(),
        autoEnabled = p.getBoolean("auto", true),
        tradingLocked = p.getBoolean("locked", false),
        consecutiveLosses = p.getInt("streak", 0),
        tick = p.getLong("tick", 0),
        lastEvent = p.getString("event", "AI Auto Paper bereit") ?: "AI Auto Paper bereit"
    )

    fun saveAccount(a: PaperAccount) {
        p.edit()
            .putFloat("start", a.startingBalance.toFloat())
            .putFloat("cash", a.cash.toFloat())
            .putFloat("realized", a.realizedPnl.toFloat())
            .putBoolean("auto", a.autoEnabled)
            .putBoolean("locked", a.tradingLocked)
            .putInt("streak", a.consecutiveLosses)
            .putLong("tick", a.tick)
            .putString("event", a.lastEvent)
            .apply()
    }

    fun loadTrades(): List<PaperTrade> {
        return p.getStringSet("trades", emptySet()).orEmpty().mapNotNull { s ->
            val a = s.split("|")
            runCatching {
                PaperTrade(
                    id = a[0].toLong(),
                    symbol = a[1],
                    side = a[2],
                    entry = a[3].toDouble(),
                    stop = a[4].toDouble(),
                    target = a[5].toDouble(),
                    shares = a[6].toInt(),
                    createdAt = a[7].toLong(),
                    status = a[8],
                    exit = a[9].takeIf { it != "-" }?.toDouble(),
                    pnl = a[10].takeIf { it != "-" }?.toDouble(),
                    closedAt = a[11].takeIf { it != "-" }?.toLong(),
                    source = a.getOrElse(12) { "AUTO" }
                )
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun saveTrades(trades: List<PaperTrade>) {
        val set = trades.map { t ->
            listOf(
                t.id, t.symbol, t.side, t.entry, t.stop, t.target, t.shares, t.createdAt,
                t.status, t.exit ?: "-", t.pnl ?: "-", t.closedAt ?: "-", t.source
            ).joinToString("|")
        }.toSet()
        p.edit().putStringSet("trades", set).apply()
    }

    fun reset() {
        p.edit().clear().apply()
    }
}

fun money(v: Double?): String = if (v == null) "—" else "$" + "%.2f".format(v)
fun fmt(v: Double): String = "%.2f".format(v)
fun signedPct(v: Double): String = if (v >= 0) "+" + fmt(v) + "%" else fmt(v) + "%"
fun signedMoney(v: Double): String = if (v >= 0) "+$" + fmt(v) else "-$" + fmt(abs(v))
