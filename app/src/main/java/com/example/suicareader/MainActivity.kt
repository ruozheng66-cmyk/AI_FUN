package com.example.suicareader

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.suicareader.data.AppDatabase
import com.example.suicareader.data.ScanRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

// --- 颜色定义 ---
val SuicaGreen = Color(0xFF00843D)
val BackgroundGray = Color(0xFFF5F5F5)
val TextDark = Color(0xFF333333)
val CardWhite = Color(0xFFFFFFFF)
val AmountRed = Color(0xFFD32F2F) // 支出颜色
val AmountGreen = Color(0xFF388E3C) // 收入颜色

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var database: AppDatabase

    // UI State
    private var currentBalance by mutableStateOf<Int?>(null)
    private var recentTransactions = mutableStateListOf<SuicaTransaction>()
    private var showHistorySheet by mutableStateOf(false)
    private var showAboutDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        database = AppDatabase.getDatabase(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = SuicaGreen, background = BackgroundGray)
            ) {
                MainScreen(
                    balance = currentBalance,
                    transactions = recentTransactions,
                    onHistoryClick = { showHistorySheet = true },
                    onAboutClick = { showAboutDialog = true },
                    onResetClick = {
                        // 重置状态，准备扫描下一张
                        currentBalance = null
                        recentTransactions.clear()
                    }
                )

                if (showHistorySheet) {
                    HistoryScreen(db = database, onDismiss = { showHistorySheet = false })
                }

                if (showAboutDialog) {
                    AboutDialog(onDismiss = { showAboutDialog = false })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcReader()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    private fun enableNfcReader() {
        val options = Bundle()
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> onTagDiscovered(tag) },
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            options
        )
    }

    private fun onTagDiscovered(tag: Tag) {
        val data = SuicaNfcReader.readTag(tag)
        if (data != null) {
            lifecycleScope.launch(Dispatchers.Main) {
                currentBalance = data.balance
                recentTransactions.clear()
                recentTransactions.addAll(data.history)

                launch(Dispatchers.IO) {
                    database.scanDao().insert(
                        ScanRecord(balance = data.balance, cardId = "Suica")
                    )
                }
                Toast.makeText(this@MainActivity, "读取成功", Toast.LENGTH_SHORT).show()
            }
        } else {
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "读取失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// --- UI 组件 ---

@Composable
fun MainScreen(
    balance: Int?,
    transactions: List<SuicaTransaction>,
    onHistoryClick: () -> Unit,
    onAboutClick: () -> Unit,
    onResetClick: () -> Unit // 新增回调
) {
    Scaffold(
        containerColor = BackgroundGray,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onHistoryClick) {
                    Icon(Icons.Default.History, "历史", tint = TextDark)
                }
            }
        },
        bottomBar = {
            Row(modifier = Modifier.padding(16.dp)) {
                IconButton(onClick = onAboutClick) {
                    Icon(Icons.Default.Info, "关于", tint = Color.Gray)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // 卡片区域
            SuicaCardView(balance)

            Spacer(modifier = Modifier.height(30.dp))

            // 状态判断：根据是否有余额显示不同内容
            if (balance == null) {
                // 1. 等待扫描状态 -> 显示水波纹动画
                RippleScanAnimation()
                Spacer(modifier = Modifier.height(16.dp))
                Text("请将交通卡贴在手机背面", color = Color.Gray, fontSize = 16.sp)
            } else {
                // 2. 扫描成功状态 -> 显示按钮 + 交易列表

                // 重置按钮
                OutlinedButton(
                    onClick = onResetClick,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SuicaGreen)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("扫描下一张卡")
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "最近交易",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(transactions) { item ->
                        TransactionItem(item)
                    }
                }
            }
        }
    }
}

// 💧 水波纹动画组件
@Composable
fun RippleScanAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "ripple")

    // 两个波纹，稍微错开一点
    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "scale1"
    )
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "alpha1"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        // 动态波纹
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = SuicaGreen.copy(alpha = alpha1),
                radius = size.minDimension / 2 * scale1,
                style = Stroke(width = 4.dp.toPx())
            )
        }

        // 中心固定图标
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(SuicaGreen.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Nfc, null, tint = SuicaGreen, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun TransactionItem(item: SuicaTransaction) {
    val numberFormat = NumberFormat.getNumberInstance(Locale.JAPAN)

    // 判断颜色和符号
    val isIncome = item.amount > 0
    val amountText = when {
        item.amount > 0 -> "+${numberFormat.format(item.amount)}円"
        item.amount < 0 -> "${numberFormat.format(item.amount)}円" // 负数自带减号
        else -> "----" // 无法计算的第一条记录
    }
    val amountColor = if (isIncome) AmountGreen else AmountRed

    Card(
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：日期 + 类型
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.date, fontSize = 14.sp, color = Color.Gray)
                Spacer(modifier = Modifier.width(12.dp))

                // 类型标签背景
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BackgroundGray)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(item.type, fontSize = 12.sp, color = TextDark)
                }
            }

            // 右侧：金额
            Text(
                text = amountText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor
            )
        }
    }
}

// 保持 SuicaCardView, HistoryScreen, AboutDialog 不变 (或者你可以直接用之前的)
// ... 下面这些是之前写好的辅助组件，为了完整性我还是列出来，如果没有变动可以不用改 ...

@Composable
fun SuicaCardView(balance: Int?) {
    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SuicaGreen),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Nfc, null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Suica / IC", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Column {
                Text("当前余额", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                Text(
                    text = if (balance != null) "¥ ${NumberFormat.getNumberInstance(Locale.JAPAN).format(balance)}" else "----",
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(db: AppDatabase, onDismiss: () -> Unit) {
    val history by db.scanDao().getAll().collectAsState(initial = emptyList())
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text("扫描历史记录", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextDark)
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(history) { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardWhite, RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(dateFormat.format(Date(record.timestamp)), fontSize = 14.sp, color = Color.Gray)
                        Text("余额: ¥${record.balance}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SuicaGreen)
                    }
                }
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于本项目") },
        text = { Text("Suica Reader v1.1\n隐私声明：数据仅本地存储。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定", color = SuicaGreen) } },
        containerColor = CardWhite
    )
}