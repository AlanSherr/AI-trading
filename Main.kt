package com.krakendesktop

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.prefs.Preferences
import kotlin.random.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// --- Data Classes ---
data class SidebarItem(val label: String, val icon: ImageVector)
data class TradeActivity(val type: String, val asset: String, val amount: Double, val price: Double, val timestamp: String)
data class BotStats(
    val totalTrades: Int = 0,
    val winRate: Double = 0.0,
    val totalProfit: Double = 0.0,
    val dailyReturn: Double = 0.0,
    val maxDrawdown: Double = 0.0,
    val sharpeRatio: Double = 0.0,
    val portfolioHistory: List<Pair<Long, Double>> = emptyList()
)

data class TradeDetail(
    val id: String,
    val timestamp: String,
    val coin: String,
    val action: String, // BUY/SELL
    val amount: Double,
    val price: Double,
    val cost: Double,
    val fee: Double,
    val strategy: String,
    val confidence: Int,
    val pnl: Double = 0.0,
    val status: String = "COMPLETED"
)

data class NewsArticle(
    val title: String,
    val summary: String,
    val sentiment: Double,
    val source: String,
    val timestamp: Long,
    val relevanceScore: Double
)

data class MarketSentiment(
    val overall: Double,
    val fearGreedIndex: Int,
    val socialMediaBuzz: Double,
    val newsVolume: Int,
    val lastUpdate: Long = System.currentTimeMillis()
)

data class StrategyPerformance(
    val name: String,
    val currentSignal: AITradingEngine.TradingSignal?,
    val totalSignals: Int = 0,
    val correctPredictions: Int = 0,
    val avgConfidence: Double = 0.0,
    val profitability: Double = 0.0,
    val lastUpdate: Long = System.currentTimeMillis(),
    val recentTrades: List<Double> = emptyList()
) {
    val accuracy: Double get() = if (totalSignals > 0) (correctPredictions.toDouble() / totalSignals) * 100 else 0.0
}

enum class ConnectionStatus {
    CONNECTED, DISCONNECTED, CONNECTING, ERROR
}

// --- Smart AI Allocation Engine ---
class SmartAllocationEngine {
    data class AllocationDecision(
        val btcPercentage: Double,
        val ethPercentage: Double,
        val reasoning: String,
        val confidence: Int
    )

    suspend fun calculateOptimalAllocation(
        btcSignal: AITradingEngine.TradingSignal,
        ethSignal: AITradingEngine.TradingSignal,
        currentBtcPrice: Double,
        currentEthPrice: Double,
        historicalBtcPrices: List<Double>,
        historicalEthPrices: List<Double>,
        marketSentiment: MarketSentiment? = null
    ): AllocationDecision {

        // Calculate base scores for each asset
        var btcScore = calculateAssetScore(btcSignal, historicalBtcPrices)
        var ethScore = calculateAssetScore(ethSignal, historicalEthPrices)

        // Apply market sentiment influence
        marketSentiment?.let { sentiment ->
            if (sentiment.fearGreedIndex < 30) {
                btcScore += 15.0  // Fear favors BTC (safe haven)
            } else if (sentiment.fearGreedIndex > 70) {
                ethScore += 10.0  // Greed favors ETH (higher risk/reward)
            }
        }

        // Volatility-based adjustment
        val btcVolatility = calculateVolatility(historicalBtcPrices)
        val ethVolatility = calculateVolatility(historicalEthPrices)

        if (btcVolatility < ethVolatility) {
            btcScore += 5.0  // Favor lower volatility
        } else {
            ethScore += 5.0
        }

        // Momentum factor
        val btcMomentum = calculateMomentum(historicalBtcPrices)
        val ethMomentum = calculateMomentum(historicalEthPrices)

        btcScore += btcMomentum * 10
        ethScore += ethMomentum * 10

        // Normalize scores to percentages (ensure minimum 20% each)
        val totalScore = btcScore + ethScore
        val btcPercentage = if (totalScore > 0) (btcScore / totalScore * 100).coerceIn(20.0, 80.0) else 50.0
        val ethPercentage = 100.0 - btcPercentage

        // Generate reasoning
        val reasoning = buildString {
            append("BTC Score: ${"%.1f".format(btcScore)}, ETH Score: ${"%.1f".format(ethScore)}. ")
            when {
                btcPercentage > 65 -> append("BTC favored due to ")
                ethPercentage > 65 -> append("ETH favored due to ")
                else -> append("Balanced allocation due to ")
            }

            if (btcSignal.confidence > ethSignal.confidence) {
                append("stronger BTC signal (${btcSignal.confidence}% vs ${ethSignal.confidence}%). ")
            } else if (ethSignal.confidence > btcSignal.confidence) {
                append("stronger ETH signal (${ethSignal.confidence}% vs ${btcSignal.confidence}%). ")
            }

            marketSentiment?.let {
                append("Market fear/greed: ${it.fearGreedIndex}. ")
            }
        }

        val confidence = ((kotlin.math.abs(btcScore - ethScore) / totalScore * 100).coerceIn(50.0, 95.0)).toInt()

        return AllocationDecision(btcPercentage, ethPercentage, reasoning, confidence)
    }

    private fun calculateAssetScore(signal: AITradingEngine.TradingSignal, prices: List<Double>): Double {
        var score = 50.0 // Base score

        // Signal influence
        when (signal.action) {
            "BUY" -> score += (signal.confidence / 100.0) * 30
            "SELL" -> score -= (signal.confidence / 100.0) * 20
            "HOLD" -> score += 0
        }

        // Strategy bonus
        when (signal.strategy) {
            "Hybrid AI" -> score += 5
            "Neural Network" -> score += 3
            "AI Ensemble" -> score += 4
            "News Sentiment" -> score += 2
        }

        return score.coerceIn(0.0, 100.0)
    }

    private fun calculateVolatility(prices: List<Double>): Double {
        if (prices.size < 2) return 0.0
        val returns = prices.zipWithNext { a, b -> (b - a) / a }
        val mean = returns.average()
        val variance = returns.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }

    private fun calculateMomentum(prices: List<Double>): Double {
        if (prices.size < 5) return 0.0
        val recent = prices.takeLast(3).average()
        val older = prices.takeLast(6).take(3).average()
        return if (older != 0.0) (recent - older) / older else 0.0
    }
}

// --- Kraken API Client Interface ---
interface KrakenApiClientInterface {
    suspend fun getBalance(): Map<String, Double>
    suspend fun getCurrentPrice(pair: String): Double
    suspend fun getTicker(pair: String): Double?
    suspend fun getOHLC(pair: String): List<Pair<Long, Double>>
    suspend fun placeBuyOrder(pair: String, amount: Double): String
    suspend fun placeSellOrder(pair: String, amount: Double): String
}

// --- Real Kraken API Client ---
class RealKrakenApiClient(private val apiKey: String, private val apiSecret: String) : KrakenApiClientInterface {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://api.kraken.com"

    private fun generateSignature(urlPath: String, data: String, nonce: String): String {
        val secretDecoded = java.util.Base64.getDecoder().decode(apiSecret)
        val sha256 = MessageDigest.getInstance("SHA-256")
        val hash = sha256.digest((nonce + data).toByteArray())
        val hmac = Mac.getInstance("HmacSHA512")
        hmac.init(SecretKeySpec(secretDecoded, "HmacSHA512"))
        val signature = hmac.doFinal(urlPath.toByteArray() + hash)
        return java.util.Base64.getEncoder().encodeToString(signature)
    }

    private suspend fun makePrivateRequest(endpoint: String, params: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val nonce = (System.currentTimeMillis() * 1000).toString()
        val data = (params + ("nonce" to nonce)).map { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.joinToString("&")
        val urlPath = "/0/private/$endpoint"
        val signature = generateSignature(urlPath, data, nonce)

        val request = Request.Builder()
            .url("$baseUrl$urlPath")
            .addHeader("API-Key", apiKey)
            .addHeader("API-Sign", signature)
            .post(RequestBody.create("application/x-www-form-urlencoded".toMediaType(), data))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val json = JSONObject(responseBody)

        if (json.has("error") && json.getJSONArray("error").length() > 0) {
            throw Exception("Kraken API Error: ${json.getJSONArray("error").join(", ")}")
        }

        responseBody
    }

    private suspend fun makePublicRequest(endpoint: String, params: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val urlBuilder = StringBuilder("$baseUrl/0/public/$endpoint")
        if (params.isNotEmpty()) {
            urlBuilder.append("?")
            urlBuilder.append(params.map { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.joinToString("&"))
        }

        val request = Request.Builder().url(urlBuilder.toString()).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        response.body?.string() ?: throw Exception("Empty response")
    }

    override suspend fun getBalance(): Map<String, Double> {
        try {
            val response = makePrivateRequest("Balance")
            val json = JSONObject(response)
            val result = json.getJSONObject("result")
            val balances = mutableMapOf<String, Double>()

            result.keys().forEach { asset ->
                val balance = result.getString(asset).toDoubleOrNull() ?: 0.0
                if (balance > 0.0) {
                    balances[asset] = balance
                }
            }

            return balances
        } catch (e: Exception) {
            println("Error fetching balance: ${e.message}")
            throw e
        }
    }

    override suspend fun getCurrentPrice(pair: String): Double {
        try {
            val response = makePublicRequest("Ticker", mapOf("pair" to pair))
            val json = JSONObject(response)
            val result = json.getJSONObject("result")
            val pairData = result.getJSONObject(result.keys().next())
            return pairData.getJSONArray("c").getString(0).toDouble()
        } catch (e: Exception) {
            println("Error fetching price for $pair: ${e.message}")
            throw e
        }
    }

    override suspend fun getTicker(pair: String): Double? {
        return try {
            getCurrentPrice(pair)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getOHLC(pair: String): List<Pair<Long, Double>> {
        try {
            val response = makePublicRequest("OHLC", mapOf("pair" to pair, "interval" to "60"))
            val json = JSONObject(response)
            val result = json.getJSONObject("result")
            val pairKey = result.keys().next()
            val ohlcArray = result.getJSONArray(pairKey)

            val ohlcData = mutableListOf<Pair<Long, Double>>()
            for (i in 0 until ohlcArray.length()) {
                val candle = ohlcArray.getJSONArray(i)
                val time = candle.getLong(0)
                val close = candle.getString(4).toDouble()
                ohlcData.add(Pair(time, close))
            }

            return ohlcData
        } catch (e: Exception) {
            println("Error fetching OHLC for $pair: ${e.message}")
            return emptyList()
        }
    }
