from pathlib import Path
import json, re, sys

root = Path(sys.argv[1])
bot_path = root / "app/src/main/python/bot.py"
cfg_path = root / "app/src/main/assets/bot_seed/config.json"
bot = bot_path.read_text(encoding="utf-8")

def rep(old, new, label):
    global bot
    n = bot.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 anchor, got {n}")
    bot = bot.replace(old, new, 1)

rep(
'''def record_observation(kind, payload):
    OBSERVATIONS.info(json.dumps({"time": now_iso(), "kind": kind, **payload}, allow_nan=False))


def token_entry_allowed(s, key):''',
'''def record_observation(kind, payload):
    OBSERVATIONS.info(json.dumps({"time": now_iso(), "kind": kind, **payload}, allow_nan=False))


POSITION_PATHS = logging.getLogger("position_paths")
POSITION_PATHS.setLevel(logging.INFO)
POSITION_PATHS.propagate = False
if IS_TEST_PROCESS:
    POSITION_PATHS.addHandler(logging.NullHandler())
else:
    _path_handler = RotatingFileHandler(BASE / "position_paths.jsonl", maxBytes=6_000_000, backupCount=4, encoding="utf-8")
    _path_handler.setFormatter(logging.Formatter("%(message)s"))
    POSITION_PATHS.addHandler(_path_handler)


def record_position_path(key, pos, p, net_now, adaptive_stop, mode):
    now = time.time()
    if now - float(pos.get("last_path_log_epoch", 0.0) or 0.0) < float(CONFIG.get("position_path_log_seconds", 10.0)):
        return
    pos["last_path_log_epoch"] = now
    m5_buys, m5_sells, m5_ratio, m5_volume = short_flow_metrics(p)
    POSITION_PATHS.info(json.dumps({
        "time": now_iso(),
        "key": key,
        "symbol": pos.get("symbol"),
        "chain": pos.get("chain_id"),
        "price_usd": float(p.get("priceUsd") or 0),
        "raw_return_pct": float(pos.get("change_pct", 0.0) or 0.0),
        "net_return_pct": float(net_now),
        "peak_net_return_pct": float(pos.get("peak_net_return_pct", net_now)),
        "adaptive_stop_net_pct": float(adaptive_stop),
        "adaptive_stop_mode": mode,
        "m5_pct": metric(p.get("priceChange"), "m5"),
        "h1_pct": metric(p.get("priceChange"), "h1"),
        "m5_buys": m5_buys,
        "m5_sells": m5_sells,
        "m5_buy_sell_ratio": m5_ratio,
        "m5_volume_usd": m5_volume,
        "entry_ai_score": pos.get("entry_ai_score"),
        "entry_ai_active": bool(pos.get("entry_ai_active", False)),
        "entry_quality_rank": pos.get("entry_quality_rank"),
    }, allow_nan=False))


def token_entry_allowed(s, key):''',
"path_logger")

rep(
'''def score_pair(p, chain_id="solana"):
    rules = strategy.rules_for(p, chain_rules(chain_id), CONFIG)''',
'''def short_flow_metrics(p):
    tx = (p.get("txns") or {}).get("m5") or {}
    buys = float(tx.get("buys") or 0)
    sells = float(tx.get("sells") or 0)
    ratio = buys / max(sells, 1.0)
    volume = metric(p.get("volume"), "m5")
    return buys, sells, ratio, volume


def short_flow_ok(p):
    q = CONFIG.get("entry_quality_v818", {})
    buys, sells, ratio, volume = short_flow_metrics(p)
    return (
        buys >= float(q.get("min_m5_buys", 3))
        and ratio >= float(q.get("min_m5_buy_sell_ratio", 1.05))
        and volume > 0
    )


def score_pair(p, chain_id="solana"):
    rules = strategy.rules_for(p, chain_rules(chain_id), CONFIG)''',
"short_flow_helpers")

rep(
'''    reason = (
        f"chain={chain_id};liq={liq:.0f},vol1h={vol:.0f},ratio={ratio:.2f},"
        f"chg1h={h1chg:.1f}%,m5={m5:.1f}%,age={age:.0f}m,turn={turnover:.2f}"
    )''',
'''    m5_buys, m5_sells, m5_ratio, m5_volume = short_flow_metrics(p)
    reason = (
        f"chain={chain_id};liq={liq:.0f},vol1h={vol:.0f},ratio={ratio:.2f},"
        f"chg1h={h1chg:.1f}%,m5={m5:.1f}%,age={age:.0f}m,turn={turnover:.2f},"
        f"m5buys={m5_buys:.0f},m5sells={m5_sells:.0f},m5ratio={m5_ratio:.2f},m5vol={m5_volume:.0f}"
    )''',
"reason_flow")

rep(
'''def entry_quality_rank(p, chain_id="solana"):
    """Small transparent ranking preference; never overrides hard risk filters."""
    _, _, h1chg, m5, _, _, ratio, _, _ = pair_metrics(p)
    q = CONFIG.get("entry_quality_v818", {})
    points = 0
    if m5 >= float(q.get("strong_m5_min_pct", 1.0)):
        points += 2
    if ratio <= float(q.get("strong_ratio_max", 2.5)):
        points += 2
    if h1chg >= float(q.get("strong_h1_min_pct", 2.0)):
        points += 1
    return points''',
'''def entry_quality_rank(p, chain_id="solana"):
    """Cost-aware live ranking which discriminates between hard-qualified candidates."""
    _, _, h1chg, m5, _, _, ratio, _, turnover = pair_metrics(p)
    m5_buys, m5_sells, m5_ratio, _ = short_flow_metrics(p)
    q = CONFIG.get("entry_quality_v818", {})
    points = 0
    m5_lo = float(q.get("strong_m5_min_pct", 1.5))
    m5_hi = float(q.get("strong_m5_max_pct", 3.5))
    if m5_lo <= m5 <= m5_hi:
        points += 4
    elif m5 >= float(q.get("fallback_m5_min_pct", 1.2)):
        points += 1
    ratio_lo = float(q.get("strong_ratio_min", 1.25))
    ratio_hi = float(q.get("strong_ratio_max", 1.9))
    if ratio_lo <= ratio <= ratio_hi:
        points += 2
    elif ratio <= float(q.get("fallback_ratio_max", 2.5)):
        points += 1
    h1_lo = float(q.get("strong_h1_min_pct", 4.0))
    h1_hi = float(q.get("strong_h1_max_pct", 10.0))
    if h1_lo <= h1chg <= h1_hi:
        points += 2
    elif h1chg >= float(q.get("fallback_h1_min_pct", 3.5)):
        points += 1
    if m5_ratio >= float(q.get("strong_m5_buy_sell_ratio", 1.20)):
        points += 3
    elif m5_ratio >= float(q.get("min_m5_buy_sell_ratio", 1.05)):
        points += 1
    if float(q.get("ideal_turnover_min", 0.03)) <= turnover <= float(q.get("ideal_turnover_max", 0.25)):
        points += 1
    return points''',
"quality_rank")

rep(
'''        "score": score,
        "entry_ai_score": p.get("ai_score"),
        "entry_ai_abstain": bool((p.get("ai_assessment") or {}).get("abstain", True)),
        "pair_address": p.get("pairAddress"),''',
'''        "score": score,
        "entry_ai_score": p.get("ai_score"),
        "entry_ai_abstain": bool((p.get("ai_assessment") or {}).get("abstain", True)),
        "entry_ai_mode": p.get("ai_status_mode", "observing"),
        "entry_ai_active": (
            p.get("ai_status_mode") == "ranking"
            and p.get("ai_score") is not None
            and not bool((p.get("ai_assessment") or {}).get("abstain", True))
        ),
        "entry_quality_rank": entry_quality_rank(p, chain_id),
        "entry_m5_buys": short_flow_metrics(p)[0],
        "entry_m5_sells": short_flow_metrics(p)[1],
        "entry_m5_buy_sell_ratio": short_flow_metrics(p)[2],
        "pair_address": p.get("pairAddress"),''',
"entry_metadata")

rep(
'''    # The learned entry model may only tighten risk, never widen it.
    try:
        ai_score = float(pos.get("entry_ai_score"))
        if math.isfinite(ai_score) and not pos.get("entry_ai_abstain") and ai_score < 0.50:
            floor = max(floor, -5.0)
            mode = "ai_cautious"
    except (TypeError, ValueError):
        pass

    # Weak follow-through after entry: cut earlier instead of waiting for the hard stop.
    if held >= 2.0 and m5 <= -0.5 and net_now <= -3.5:
        floor = max(floor, -4.5)
        mode = "weak_flow"
    if held >= 5.0 and peak < 0.75 and m5 <= 0.0 and net_now <= -3.0:
        floor = max(floor, -4.0)
        mode = "failed_followthrough"

    # Once the whole trade was truly profitable after costs, lock real net profit.
    if peak >= 0.75:
        floor = max(floor, 0.10)
        mode = "no_loss_lock"
    if peak >= 2.0:
        floor = max(floor, 0.75)
        mode = "profit_lock"
    if peak >= 4.0:
        giveback = max(1.0, min(2.5, 0.75 + noise * 2.0))
        floor = max(floor, peak - giveback)
        mode = "adaptive_trail"
    if peak >= 8.0:
        giveback = max(1.25, min(3.0, 1.0 + noise * 1.75))
        floor = max(floor, peak - giveback)
        mode = "strong_trend_trail"''',
'''    # Only a validated/ranking model may influence live risk.
    if pos.get("entry_ai_active"):
        try:
            ai_score = float(pos.get("entry_ai_score"))
            if math.isfinite(ai_score) and ai_score < float(CONFIG.get("adaptive_low_ai_threshold", 0.48)):
                floor = max(floor, float(CONFIG.get("adaptive_low_ai_stop_net_pct", -5.0)))
                mode = "ai_cautious"
        except (TypeError, ValueError):
            pass

    if held >= 2.0 and m5 <= -0.5 and net_now <= -3.5:
        floor = max(floor, -4.5)
        mode = "weak_flow"
    if held >= 8.0 and peak < 1.0 and m5 <= -0.25 and net_now <= -3.25:
        floor = max(floor, -4.0)
        mode = "failed_followthrough"

    # Let winners develop: V8.18.4 locked tiny profits too early.
    if peak >= 2.0:
        floor = max(floor, 0.25)
        mode = "breakeven_plus"
    if peak >= 4.0:
        floor = max(floor, 1.0)
        mode = "profit_lock"
    if peak >= 6.0:
        giveback = max(1.25, min(3.0, 0.90 + noise * 2.0))
        floor = max(floor, peak - giveback)
        mode = "adaptive_trail"
    if peak >= 10.0:
        giveback = max(1.50, min(3.5, 1.10 + noise * 1.75))
        floor = max(floor, peak - giveback)
        mode = "strong_trend_trail"''',
"adaptive_exit")

rep(
'''                net_now, adaptive_stop, adaptive_mode, live_noise = adaptive_stop_update(pos, p, ret)
                if net_now <= adaptive_stop:''',
'''                net_now, adaptive_stop, adaptive_mode, live_noise = adaptive_stop_update(pos, p, ret)
                record_position_path(key, pos, p, net_now, adaptive_stop, adaptive_mode)
                if net_now <= adaptive_stop:''',
"path_call")

rep(
'''                assessment=model.assess(features(score,reason))
                prediction=assessment['score'];p["ai_score"]=round(prediction,6)
                p['ai_assessment']=assessment
                abstentions+=int(assessment['abstain'])''',
'''                assessment=model.assess(features(score,reason))
                prediction=assessment['score'];p["ai_score"]=round(prediction,6)
                p['ai_assessment']=assessment
                p['ai_status_mode']=status.get('mode', 'observing')
                abstentions+=int(assessment['abstain'])''',
"ai_mode")

rep(
'''        if buys < float(rules.get("min_h1_buys", 20)) or ratio < float(rules.get("min_buy_sell_ratio", 1.15)):
            _mark_reason(reasons, "buy_pressure"); continue
        max_ratio = rules.get("max_buy_sell_ratio")''',
'''        if buys < float(rules.get("min_h1_buys", 20)) or ratio < float(rules.get("min_buy_sell_ratio", 1.15)):
            _mark_reason(reasons, "buy_pressure"); continue
        if not short_flow_ok(p):
            _mark_reason(reasons, "recent_m5_flow"); continue
        max_ratio = rules.get("max_buy_sell_ratio")''',
"m5_flow_gate")

rep(
'''            turn<=rules['max_h1_volume_to_liquidity'],score_pair(p,chain)[0]>=rules['min_score'],
            strategy.recent_flow_ok(p,CONFIG))
        if not all(checks):return None,"recheck_quality"
        p['_received_epoch']=time.time()
        return p,None''',
'''            turn<=rules['max_h1_volume_to_liquidity'],score_pair(p,chain)[0]>=rules['min_score'],
            strategy.recent_flow_ok(p,CONFIG), short_flow_ok(p))
        if not all(checks):return None,"recheck_quality"
        p['_received_epoch']=time.time()
        for field in ("ai_score", "ai_assessment", "ai_status_mode"):
            if field in previous:
                p[field] = previous[field]
        p["_entry_quality_rank"] = entry_quality_rank(previous, chain)
        return p,None''',
"recheck_ai")

rep(
'''    candidates.sort(key=lambda x:(entry_quality_rank(x[1], x[3]), x[0], metric(x[1].get("liquidity"),"usd")), reverse=True)''',
'''    candidates.sort(
        key=lambda x: (
            entry_quality_rank(x[1], x[3]),
            min(short_flow_metrics(x[1])[2], 4.0),
            metric(x[1].get("priceChange"), "m5"),
            -pair_metrics(x[1])[8],
            x[0],
        ),
        reverse=True,
    )''',
"candidate_sort")

rep(
'''    for score,p,reason,chain in candidates:
        if opened>=int(CONFIG["max_new_positions_per_scan"]) or not can_open(s):break
        fresh, rejection = recheck_entry(p, chain)''',
'''    for score,p,reason,chain in candidates:
        if opened>=int(CONFIG["max_new_positions_per_scan"]) or not can_open(s):break
        assessment = p.get("ai_assessment") or {}
        if (
            CONFIG.get("ai_entry_veto_enabled", True)
            and p.get("ai_status_mode") == "ranking"
            and not assessment.get("abstain", True)
            and float(p.get("ai_score", 1.0)) < float(CONFIG.get("ai_entry_veto_score", 0.45))
        ):
            _mark_reason(reasons, "ai_veto")
            record_observation("entry_rejected", {"chain":chain,"pair":p.get("pairAddress"),"reason":"ai_veto","ai_score":p.get("ai_score")})
            continue
        fresh, rejection = recheck_entry(p, chain)''',
"ai_veto")

rep(
'''        "reason": f"{LABELS[chain_id]};score={score};{reason};profile={plan['profile']};stop={plan['stop_loss_pct']};tp1={plan['take_profit_1_pct']:.4f};tp2={plan['take_profit_2_pct']:.4f};version={CONFIG['version']}",''',
'''        "reason": (
            f"{LABELS[chain_id]};score={score};{reason};"
            f"entry_quality={entry_quality_rank(p, chain_id)};"
            f"ai_score={p.get('ai_score')};ai_mode={p.get('ai_status_mode', 'observing')};"
            f"ai_abstain={bool((p.get('ai_assessment') or {}).get('abstain', True))};"
            f"profile={plan['profile']};stop={plan['stop_loss_pct']};"
            f"tp1={plan['take_profit_1_pct']:.4f};tp2={plan['take_profit_2_pct']:.4f};version={CONFIG['version']}"
        ),''',
"buy_reason")

rep(
'''                "m5_pct": pos.get("last_m5_pct"),
                "live_tracking_epoch": pos.get("live_tracking_epoch"),''',
'''                "m5_pct": pos.get("last_m5_pct"),
                "entry_ai_score": pos.get("entry_ai_score"),
                "entry_ai_active": bool(pos.get("entry_ai_active", False)),
                "entry_quality_rank": pos.get("entry_quality_rank"),
                "live_tracking_epoch": pos.get("live_tracking_epoch"),''',
"snapshot")

bot_path.write_text(bot, encoding="utf-8")

cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
cfg.update({
    "version": "V8.18.5-adaptive-quality",
    "dashboard_version": "8.18.5-adaptive-quality",
    "entry_confirmations": 4,
    "confirmation_min_price_move_pct": 0.05,
    "confirmation_max_price_move_pct": 0.8,
    "position_path_log_seconds": 10.0,
    "ai_entry_veto_enabled": True,
    "ai_entry_veto_score": 0.45,
    "adaptive_low_ai_stop_net_pct": -5.0,
    "adaptive_low_ai_threshold": 0.48,
    "max_trades_per_day": 999999999,
    "max_daily_loss_eur": 999999999.0,
    "paper_unlimited_testing": True,
    "max_drawdown_pct": 99.0,
    "risk_per_trade_pct": 0.10,
    "max_exposure_pct": 3.0,
})
cfg["fast_v812"] = {
    "entries_per_minute": 1,
    "quick_take_net_pct": 4.0,
    "quick_take_fraction": 0.15,
    "protect_arm_net_pct": 3.0,
    "protect_floor_net_pct": 0.75,
    "protect_giveback_net_pct": 1.5,
}
for rules in cfg.get("chain_rules", {}).values():
    rules["min_m5_price_change_pct"] = 1.2
    rules["min_h1_price_change_pct"] = 3.5
    rules["max_buy_sell_ratio"] = 2.5
    rules["min_score"] = 9
cfg["entry_quality_v818"] = {
    "strong_m5_min_pct": 1.5,
    "strong_m5_max_pct": 3.5,
    "fallback_m5_min_pct": 1.2,
    "strong_ratio_min": 1.25,
    "strong_ratio_max": 1.9,
    "fallback_ratio_max": 2.5,
    "strong_h1_min_pct": 4.0,
    "strong_h1_max_pct": 10.0,
    "fallback_h1_min_pct": 3.5,
    "ideal_turnover_min": 0.03,
    "ideal_turnover_max": 0.25,
    "min_m5_buys": 3,
    "min_m5_buy_sell_ratio": 1.05,
    "strong_m5_buy_sell_ratio": 1.20,
}
cfg_path.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print("V8.18.5 patch applied")
