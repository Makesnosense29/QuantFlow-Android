from pathlib import Path
import json, re, sys

if len(sys.argv) != 2:
    raise SystemExit("usage: v8184_patch.py PROJECT_DIR")
root = Path(sys.argv[1])
bot_path = root / "app/src/main/python/bot.py"
cfg_path = root / "app/src/main/assets/bot_seed/config.json"
gradle_path = root / "app/build.gradle"
main_path = root / "app/src/main/java/de/quantflow/v8182paper/MainActivity.java"
service_path = root / "app/src/main/java/de/quantflow/v8182paper/BotService.java"

def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 anchor, got {n}")
    return text.replace(old, new, 1)

bot = bot_path.read_text(encoding="utf-8")

bot = replace_once(bot,
'''        "token_entries_today": {},
        "accounting_version": 2,
    }''',
'''        "token_entries_today": {},
        "accounting_version": 2,
        "loss_streak": 0,
        "loss_pause_until": 0.0,
    }''', "default_state")

bot = replace_once(bot,
'''    s.setdefault("trades_today", 0)
    s.setdefault("accounting_version", 1)
    restore_token_counts(s)''',
'''    s.setdefault("trades_today", 0)
    s.setdefault("accounting_version", 1)
    s.setdefault("loss_streak", 0)
    s.setdefault("loss_pause_until", 0.0)
    if CONFIG.get("paper_unlimited_testing", False):
        s["daily_halt"] = False
        s["drawdown_halt"] = False
    restore_token_counts(s)''', "load_state")

bot = replace_once(bot,
'''    daily_loss = float(s["day_start_equity_eur"]) - equity
    if daily_loss >= float(CONFIG["max_daily_loss_eur"]):
        s["daily_halt"] = True
    peak = s["peak_equity_eur"]
    if peak > 0 and (peak - equity) / peak * 100 >= float(CONFIG["max_drawdown_pct"]):
        s["drawdown_halt"] = True
    return equity''',
'''    daily_loss = float(s["day_start_equity_eur"]) - equity
    peak = s["peak_equity_eur"]
    if CONFIG.get("paper_unlimited_testing", False):
        s["daily_halt"] = False
        s["drawdown_halt"] = False
    else:
        if daily_loss >= float(CONFIG["max_daily_loss_eur"]):
            s["daily_halt"] = True
        if peak > 0 and (peak - equity) / peak * 100 >= float(CONFIG["max_drawdown_pct"]):
            s["drawdown_halt"] = True
    return equity''', "update_risk")

bot = replace_once(bot,
'''def can_open(s, minimum_eur=None):
    equity = update_risk(s)
    if s.get("daily_halt") or s.get("drawdown_halt"):
        return False''',
'''def can_open(s, minimum_eur=None):
    equity = update_risk(s)
    pause_until = float(s.get("loss_pause_until", 0.0) or 0.0)
    if pause_until > time.time():
        return False
    if pause_until and pause_until <= time.time():
        s["loss_pause_until"] = 0.0
        s["loss_streak"] = 0
    if not CONFIG.get("paper_unlimited_testing", False) and (s.get("daily_halt") or s.get("drawdown_halt")):
        return False''', "can_open")

bot = replace_once(bot,
'''        "opened_at": now_iso(),
        "score": score,
        "pair_address": p.get("pairAddress"),''',
'''        "opened_at": now_iso(),
        "score": score,
        "entry_reason": reason,
        "entry_ai_score": p.get("ai_score"),
        "entry_ai_abstain": bool((p.get("ai_assessment") or {}).get("abstain", True)),
        "adaptive_stop_net_pct": None,
        "adaptive_stop_mode": "initial",
        "live_net_history": [],
        "pair_address": p.get("pairAddress"),''', "open_position metadata")

bot = replace_once(bot,
'''        p['_received_epoch']=time.time()
        return p,None''',
'''        p['_received_epoch']=time.time()
        if "ai_score" in previous:
            p["ai_score"] = previous.get("ai_score")
        if "ai_assessment" in previous:
            p["ai_assessment"] = previous.get("ai_assessment")
        return p,None''', "recheck ai metadata")

bot = replace_once(bot,
'''def set_cooldown(s, key, reason):
    mins = float(CONFIG.get("stop_cooldown_minutes", 60) if reason == "STOP" else CONFIG.get("normal_cooldown_minutes", 20))''',
'''def set_cooldown(s, key, reason):
    is_stop = reason in ("STOP", "AI_STOP")
    mins = float(CONFIG.get("stop_cooldown_minutes", 60) if is_stop else CONFIG.get("normal_cooldown_minutes", 20))''', "stop cooldown")

bot = replace_once(bot,
'''    if pos["remaining_fraction"] <= 1e-9:
        del s["positions"][key]
        set_cooldown(s, key, reason)''',
'''    if pos["remaining_fraction"] <= 1e-9:
        total_trade_pnl = float(pos.get("exit_net_received_eur", 0.0)) - float(pos.get("entry_gross_eur", 0.0))
        if total_trade_pnl < 0:
            s["loss_streak"] = int(s.get("loss_streak", 0)) + 1
        else:
            s["loss_streak"] = 0
        threshold = int(CONFIG.get("global_loss_streak_pause_count", 0) or 0)
        if threshold > 0 and int(s.get("loss_streak", 0)) >= threshold:
            s["loss_pause_until"] = time.time() + float(CONFIG.get("global_loss_streak_pause_minutes", 0) or 0) * 60.0
        del s["positions"][key]
        set_cooldown(s, key, reason)''', "loss streak")

adaptive_engine = r'''

def _median(values):
    vals = sorted(float(x) for x in values if math.isfinite(float(x)))
    if not vals:
        return 0.0
    mid = len(vals) // 2
    return vals[mid] if len(vals) % 2 else (vals[mid - 1] + vals[mid]) / 2.0


def adaptive_stop_update(pos, p):
    """Local AI-assisted PAPER stop.

    Uses the frozen entry ensemble score, current 5-minute flow, peak net return,
    holding time and short-horizon quote noise. The stop may only tighten while
    a position is open; it is never widened after risk has been reduced.
    """
    price = float(p.get("priceUsd") or 0)
    net_now = position_net_return(pos, price)
    now = time.time()

    hist = []
    for item in pos.get("live_net_history", []):
        try:
            stamp, value = float(item[0]), float(item[1])
            if now - stamp <= 120.0 and math.isfinite(value):
                hist.append([stamp, value])
        except (TypeError, ValueError, IndexError):
            continue
    hist.append([now, net_now])
    hist = hist[-90:]
    pos["live_net_history"] = hist

    deltas = [abs(hist[i][1] - hist[i - 1][1]) for i in range(1, len(hist))]
    noise = max(0.15, min(1.50, (_median(deltas) * 3.0) if deltas else 0.15))
    m5 = metric(p.get("priceChange"), "m5")
    h1 = metric(p.get("priceChange"), "h1")
    held = opened_minutes(pos)
    peak = max(float(pos.get("peak_net_return_pct", net_now)), net_now)
    pos["peak_net_return_pct"] = peak

    floor = float(CONFIG.get("adaptive_initial_stop_net_pct", -4.5))
    mode = "initial"

    try:
        ai_score = float(pos.get("entry_ai_score"))
        if math.isfinite(ai_score):
            if bool(pos.get("entry_ai_abstain", True)):
                floor = max(floor, float(CONFIG.get("adaptive_abstain_stop_net_pct", -4.0)))
                mode = "ai_abstain"
            elif ai_score < float(CONFIG.get("adaptive_low_ai_threshold", 0.48)):
                floor = max(floor, float(CONFIG.get("adaptive_low_ai_stop_net_pct", -4.0)))
                mode = "ai_cautious"
            elif ai_score >= float(CONFIG.get("adaptive_high_ai_threshold", 0.62)) and m5 > 0:
                mode = "ai_confident"
    except (TypeError, ValueError):
        pass

    if held >= 1.0 and m5 <= -0.75 and net_now <= -3.5:
        floor = max(floor, -3.85)
        mode = "flow_reversal"
    if held >= 4.0 and peak < 0.25 and m5 <= 0.0 and net_now <= -3.4:
        floor = max(floor, -3.70)
        mode = "failed_followthrough"
    if held >= 8.0 and peak < 0.50 and h1 < 0.0:
        floor = max(floor, -3.55)
        mode = "trend_failed"

    if peak >= 0.75:
        floor = max(floor, -0.10)
        mode = "near_breakeven_lock"
    if peak >= 1.50:
        floor = max(floor, 0.30)
        mode = "profit_lock"
    if peak >= 3.0:
        giveback = max(0.75, min(1.80, 0.55 + noise * 1.50))
        floor = max(floor, peak - giveback)
        mode = "adaptive_trail"
    if peak >= 6.0:
        giveback = max(1.00, min(2.20, 0.80 + noise * 1.35))
        floor = max(floor, peak - giveback)
        mode = "strong_trend_trail"

    previous = pos.get("adaptive_stop_net_pct")
    stop = floor if previous is None else max(float(previous), floor)
    previous_mode = pos.get("adaptive_stop_mode")
    pos["adaptive_stop_net_pct"] = round(stop, 4)
    pos["adaptive_stop_mode"] = mode
    pos["adaptive_volatility_pct"] = round(noise, 4)
    pos["last_m5_pct"] = round(m5, 4)
    pos["last_h1_pct"] = round(h1, 4)
    pos["live_tracking_epoch"] = now
    pos["net_return_pct"] = round(net_now, 4)

    if previous is None or stop > float(previous) + 0.05 or previous_mode != mode:
        record_observation("adaptive_stop", {
            "symbol": pos.get("symbol"),
            "net_return_pct": net_now,
            "peak_net_return_pct": peak,
            "stop_net_pct": stop,
            "mode": mode,
            "noise_pct": noise,
            "m5_pct": m5,
            "h1_pct": h1,
            "held_minutes": held,
            "entry_ai_score": pos.get("entry_ai_score"),
            "entry_ai_abstain": pos.get("entry_ai_abstain"),
        })
    return net_now, stop, mode
'''

bot = replace_once(bot, "\ndef manage_positions(s):", adaptive_engine + "\n\ndef manage_positions(s):", "adaptive engine")

bot = replace_once(bot,
'''            record_observation("position_quote", {"key": key, "pair": pair_address, "price": price, "return_pct": ret})

            # No artificial minimum hold. A real stop is allowed immediately,
            # but only on the exact pair stored at entry.
            if ret <= float(exit_cfg.get("stop_loss_pct", -6.0)):''',
'''            record_observation("position_quote", {"key": key, "pair": pair_address, "price": price, "return_pct": ret})

            if CONFIG.get("adaptive_stop_enabled", True):
                net_now, adaptive_stop, adaptive_mode = adaptive_stop_update(pos, p)
                if net_now <= adaptive_stop:
                    sell_fraction(s, key, p, pos["remaining_fraction"], "AI_STOP")
                    continue

            # Independent catastrophic backstop on raw pair price.
            if ret <= float(exit_cfg.get("stop_loss_pct", -6.0)):''', "manage adaptive stop")

bot = replace_once(bot,
'''        "daily_halt": bool(s.get("daily_halt")), "drawdown_halt": bool(s.get("drawdown_halt")),
        "version": CONFIG.get("version", "V8.0-paper"),
    }''',
'''        "daily_halt": bool(s.get("daily_halt")), "drawdown_halt": bool(s.get("drawdown_halt")),
        "version": CONFIG.get("version", "V8.0-paper"),
        "position_monitor_seconds": float(CONFIG.get("scan_interval_seconds", 5)),
        "adaptive_stops": [
            {
                "key": key,
                "symbol": pos.get("symbol", "?"),
                "chain": pos.get("chain_id", split_position_key(key)[0]),
                "net_return_pct": pos.get("net_return_pct"),
                "peak_net_return_pct": pos.get("peak_net_return_pct"),
                "stop_net_pct": pos.get("adaptive_stop_net_pct"),
                "mode": pos.get("adaptive_stop_mode"),
                "m5_pct": pos.get("last_m5_pct"),
                "h1_pct": pos.get("last_h1_pct"),
                "entry_ai_score": pos.get("entry_ai_score"),
                "live_tracking_epoch": pos.get("live_tracking_epoch"),
            } for key, pos in s.get("positions", {}).items()
        ],
    }''', "snapshot adaptive stops")

bot_path.write_text(bot, encoding="utf-8")

cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
cfg.update({
    "max_open_positions": 2,
    "max_trades_per_day": 999999999,
    "max_daily_loss_eur": 999999999.0,
    "stop_cooldown_minutes": 240,
    "normal_cooldown_minutes": 60,
    "entry_confirmations": 3,
    "confirmation_min_price_move_pct": 0.0,
    "confirmation_max_price_move_pct": 1.0,
    "version": "V8.18.4-adaptive-paper",
    "max_drawdown_pct": 99.0,
    "risk_per_trade_pct": 0.10,
    "max_exposure_pct": 3.0,
    "max_entries_per_token_per_day": 2,
    "loss_pause_count": 2,
    "loss_pause_hours": 0.5,
    "dashboard_version": "8.18.4-adaptive-paper",
    "global_loss_streak_pause_count": 0,
    "global_loss_streak_pause_minutes": 0,
    "close_positions_on_daily_halt": False,
    "paper_unlimited_testing": True,
    "adaptive_stop_enabled": True,
    "adaptive_initial_stop_net_pct": -4.5,
    "adaptive_abstain_stop_net_pct": -4.0,
    "adaptive_low_ai_stop_net_pct": -4.0,
    "adaptive_low_ai_threshold": 0.48,
    "adaptive_high_ai_threshold": 0.62,
    "scan_interval_seconds": 2,
})
for profile in cfg.get("chain_rules", {}).values():
    profile["min_score"] = 9
    profile["max_buy_sell_ratio"] = 2.5
cfg_path.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

service = service_path.read_text(encoding="utf-8")
service = service.replace("V8.18.2-forward startet …", "V8.18.4-adaptive-paper startet …")
service = service.replace("V8.18.2-forward läuft · PAPER only", "V8.18.4-adaptive-paper läuft · PAPER only")
service = replace_once(
    service,
    'boolean immutable = out.getName().equals("config.json") || out.getName().endsWith(".html") ||',
    'boolean immutable = out.getName().endsWith(".html") ||',
    "config persistence",
)
service_path.write_text(service, encoding="utf-8")

main = main_path.read_text(encoding="utf-8").replace(
    "V8.18.2-forward · PAPER only", "V8.18.4-adaptive-paper · PAPER only"
)
main_path.write_text(main, encoding="utf-8")

gradle = gradle_path.read_text(encoding="utf-8")
gradle = re.sub(r"versionCode\s+\d+", "versionCode 6", gradle, count=1)
gradle = re.sub(r"versionName\s+'[^']+'", "versionName '8.18.4-adaptive-paper-6'", gradle, count=1)
if "debuggable true" not in gradle:
    gradle = gradle.replace(
        "release {\n            minifyEnabled false",
        "release {\n            debuggable true\n            minifyEnabled false",
    )
gradle_path.write_text(gradle, encoding="utf-8")

print("V8.18.4 adaptive PAPER patch applied")
