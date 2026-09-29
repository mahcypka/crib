package com.mlaty.cribbage.data

import android.content.Context
import com.mlaty.cribbage.game.Game
import com.mlaty.cribbage.game.Phase
import com.mlaty.cribbage.game.Seat
import com.mlaty.cribbage.model.Card
import com.mlaty.cribbage.model.Rules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SavedGame(val rules: Rules, val game: Game?)

/**
 * Партия и настройки лежат в одном файле во внутреннем хранилище приложения.
 * Запись идёт через временный файл с последующим переименованием, чтобы
 * внезапная выгрузка памяти не оставила половину файла.
 */
object SaveStore {

    private const val FILE = "cribbage.json"
    private const val VERSION = 1

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun hasSave(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 0 }

    fun clear(ctx: Context) {
        file(ctx).delete()
        File(ctx.filesDir, "$FILE.tmp").delete()
    }

    /** game = null — сохранить только настройки. */
    fun save(ctx: Context, rules: Rules, game: Game?) {
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("rules", encodeRules(rules))
        root.put("game", if (game == null) JSONObject.NULL else encodeGame(game))
        val text = root.toString()
        val dst = file(ctx)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(text)
        if (dst.exists() && !dst.delete()) return
        if (!tmp.renameTo(dst)) {
            dst.writeText(text)
            tmp.delete()
        }
    }

    fun load(ctx: Context): SavedGame? {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0) return null
        return try {
            val root = JSONObject(f.readText())
            val rules = decodeRules(root.getJSONObject("rules"))
            val g = root.optJSONObject("game")
            SavedGame(rules, if (g == null) null else decodeGame(rules, g))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- правила

    private fun encodeRules(r: Rules) = JSONObject().apply {
        put("mode", r.winMode.name)
        put("target", r.target)
        put("lowball", r.lowball)
        put("zeroPenalty", r.zeroPenalty)
        put("bonus29", r.bonus29)
        put("difficulty", r.difficulty.name)
    }

    private fun decodeRules(o: JSONObject) = Rules.decode(
        o.optString("mode"),
        o.optInt("target", 121),
        o.optBoolean("lowball", false),
        o.optBoolean("zeroPenalty", false),
        o.optInt("bonus29", 2),
        o.optString("difficulty")
    )

    // ---------------------------------------------------------------- партия

    private fun encodeCards(list: List<Card>) = JSONArray().apply { for (c in list) put(c.id) }

    private fun decodeCards(a: JSONArray) = (0 until a.length()).map { Card.fromId(a.getInt(it)) }

    private fun encodeGame(game: Game): JSONObject {
        val s = game.snapshot()
        return JSONObject().apply {
            put("seed", game.seed)
            put("draws", s.draws)
            put("round", s.round)
            put("playerScore", s.playerScore)
            put("aiScore", s.aiScore)
            put("deck", encodeCards(s.deck))
            put("playerSix", encodeCards(s.playerSix))
            put("aiSix", encodeCards(s.aiSix))
            put("crib", encodeCards(s.crib))
            put("playerFour", encodeCards(s.playerFour))
            put("aiFour", encodeCards(s.aiFour))
            put("starter", if (s.starter == null) JSONObject.NULL else s.starter!!.id)
            put("aiIsDealer", s.aiIsDealer)
            put("phase", s.phase.name)
            put("playerDiscardsLeft", s.playerDiscardsLeft)
            put("aiDiscardsLeft", s.aiDiscardsLeft)
            put("playerLeft", encodeCards(s.playerLeft))
            put("aiLeft", encodeCards(s.aiLeft))
            put("sequence", encodeCards(s.sequence))
            put("count", s.count)
            put("turn", s.turn.name)
            put("announce", s.announce)
            put("showIndex", s.showIndex)
            put("roundPlayerPoints", s.roundPlayerPoints)
            put("roundAiPoints", s.roundAiPoints)
            put("lastShowBonus", s.lastShowBonus)
            put("winner", s.winner)
            put("passes", s.passes)
            put("lastPlayedBy", if (s.lastPlayedBy == null) JSONObject.NULL else s.lastPlayedBy!!.name)
            put("pendingGoSeat", if (s.pendingGoSeat == null) JSONObject.NULL else s.pendingGoSeat!!.name)
            put("targetHitBy", if (s.targetHitBy == null) JSONObject.NULL else s.targetHitBy!!.name)
        }
    }

    private fun decodeGame(rules: Rules, o: JSONObject): Game {
        val raw = o.opt("starter")
        val starter: Card? = if (raw == null || raw == JSONObject.NULL) null else Card.fromId(raw as Int)
        val lp = o.opt("lastPlayedBy")
        val pg = o.opt("pendingGoSeat")
        val th = o.opt("targetHitBy")
        return Game.restore(
            rules,
            o.optLong("seed"),
            Game.State(
                o.optLong("draws"),
                o.optInt("round"),
                o.optInt("playerScore"),
                o.optInt("aiScore"),
                decodeCards(o.getJSONArray("deck")),
                decodeCards(o.getJSONArray("playerSix")),
                decodeCards(o.getJSONArray("aiSix")),
                decodeCards(o.getJSONArray("crib")),
                decodeCards(o.getJSONArray("playerFour")),
                decodeCards(o.getJSONArray("aiFour")),
                starter,
                o.optBoolean("aiIsDealer"),
                Phase.valueOf(o.getString("phase")),
                o.optInt("playerDiscardsLeft"),
                o.optInt("aiDiscardsLeft"),
                decodeCards(o.getJSONArray("playerLeft")),
                decodeCards(o.getJSONArray("aiLeft")),
                decodeCards(o.getJSONArray("sequence")),
                o.optInt("count"),
                Seat.valueOf(o.getString("turn")),
                o.optString("announce"),
                o.optInt("showIndex"),
                o.optInt("roundPlayerPoints"),
                o.optInt("roundAiPoints"),
                o.optString("lastShowBonus"),
                o.optString("winner"),
                o.optInt("passes"),
                if (lp == null || lp == JSONObject.NULL) null else Seat.valueOf(lp as String),
                if (pg == null || pg == JSONObject.NULL) null else Seat.valueOf(pg as String),
                if (th == null || th == JSONObject.NULL) null else Seat.valueOf(th as String)
            )
        )
    }
}
