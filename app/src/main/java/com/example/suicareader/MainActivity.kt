package com.example.suicareader

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

// --- 颜色定义 (日式扁平风) ---
val SuicaGreen = Color(0xFF00843D)
val BackgroundGray = Color(0xFFF5F5F5)
val TextDark = Color(0xFF333333)
val CardWhite = Color(0xFFFFFFFF)

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
                colorScheme = lightColorScheme(
                    primary = SuicaGreen,
                    background = BackgroundGray
                )
            ) {
                MainScreen(
                    balance = currentBalance,
                    transactions = recentTransactions,
                    onHistoryClick = { showHistorySheet = true },
                    onAboutClick = { showAboutDialog = true }
                )

                // 历史记录弹窗
                if (showHistorySheet) {
                    HistoryScreen(
                        db = database,
                        onDismiss = { showHistorySheet = false }
                    )
                }

                // 关于弹窗
                if (showAboutDialog) {
                    AboutDialog(onDismiss = { showAboutDialog = false })
                }
            }
        }
    }

    // --- NFC 处理生命周期 ---
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
        // 设置 Reader Mode 延迟以提高稳定性
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

                // 保存到数据库
                launch(Dispatchers.IO) {
                    database.scanDao().insert(
                        ScanRecord(balance = data.balance, cardId = "Suica")
                    )
                }

                Toast.makeText(this@MainActivity, "读取成功", Toast.LENGTH_SHORT).show()
            }
        } else {
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "读取失败或卡片不支持", Toast.LENGTH_SHORT).show()
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
    onAboutClick: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundGray,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onHistoryClick) {
                    Icon(Icons.Default.History, contentDescription = "历史记录", tint = TextDark)
                }
            }
        },
        bottomBar = {
            Row(modifier = Modifier.padding(16.dp)) {
                IconButton(onClick = onAboutClick) {
                    Icon(Icons.Default.Info, contentDescription = "关于", tint = Color.Gray)
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

            // 余额卡片
            SuicaCardView(balance)

            Spacer(modifier = Modifier.height(40.dp))

            // 扫描提示 / 交易列表
            if (transactions.isEmpty()) {
                ScanInstructionView()
            } else {
                Text(
                    text = "最近交易",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(transactions) { item ->
                        TransactionItem(item)
                    }
                }
            }
        }
    }
}

@Composable
fun SuicaCardView(balance: Int?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SuicaGreen),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Nfc,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
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

@Composable
fun ScanInstructionView() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 40.dp)
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color.LightGray.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Nfc, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("请将交通卡贴在手机背面", color = Color.Gray, fontSize = 16.sp)
    }
}

@Composable
fun TransactionItem(item: SuicaTransaction) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(item.date, fontSize = 14.sp, color = Color.Gray)
                Text(item.type, fontSize = 16.sp, color = TextDark, fontWeight = FontWeight.Medium)
            }
            // 这里因为没计算具体消费金额，先不显示变动值
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
            Text("历史读取记录", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextDark)
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
                        Text("¥ ${record.balance}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SuicaGreen)
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
        text = {
            Column {
                Text("Suica Reader v1.0")
                Spacer(modifier = Modifier.height(8.dp))
                Text("这是一个简洁的 NFC 读卡工具，数据仅保存在本地。")
                Spacer(modifier = Modifier.height(8.dp))
                Text("隐私声明：本应用不会上传任何卡片数据到服务器。", fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定", color = SuicaGreen)
            }
        },
        containerColor = CardWhite,
        titleContentColor = TextDark,
        textContentColor = TextDark
    )
}