package com.rubyhandling.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "bookings")
data class Booking(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val travel: String,
    val contact: String,
    val type: String,
    val flight: String,
    val flightDate: String,
    val passengers: Int,
    val bags: Int,
    val currency: String,
    val fee: Double,
    val paid: Double,
    val notes: String = ""
) {
    val balance: Double get() = (fee - paid).coerceAtLeast(0.0)
    val paymentStatus: String get() = when {
        paid <= 0.0 -> "Belum bayar"
        paid >= fee -> "Lunas"
        else -> "DP / Sebagian"
    }
}

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(
        entity = Booking::class,
        parentColumns = ["id"],
        childColumns = ["bookingId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookingId")]
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookingId: Long,
    val amount: Double,
    val paidAt: String,
    val note: String = ""
)

@Dao
interface BookingDao {
    @Query("SELECT * FROM bookings ORDER BY flightDate ASC, id DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Booking>>

    @Query("SELECT * FROM bookings WHERE id = :id LIMIT 1")
    suspend fun getBooking(id: Long): Booking?

    @Insert
    suspend fun insert(booking: Booking): Long

    @Update
    suspend fun update(booking: Booking)

    @Delete
    suspend fun delete(booking: Booking)

    @Insert
    suspend fun insertPayment(payment: Payment)

    @Transaction
    suspend fun insertBookingWithInitialPayment(booking: Booking): Long {
        val newId = insert(booking)
        if (booking.paid > 0.0) {
            insertPayment(
                Payment(
                    bookingId = newId,
                    amount = booking.paid,
                    paidAt = today(),
                    note = "Pembayaran awal"
                )
            )
        }
        return newId
    }

    @Query("SELECT * FROM payments WHERE bookingId = :bookingId ORDER BY id DESC")
    fun observePayments(bookingId: Long): kotlinx.coroutines.flow.Flow<List<Payment>>

    @Transaction
    suspend fun recordPayment(payment: Payment) {
        val current = getBooking(payment.bookingId) ?: return
        require(payment.amount > 0.0 && payment.amount <= current.balance) {
            "Jumlah pembayaran melebihi sisa tagihan."
        }
        insertPayment(payment)
        update(current.copy(paid = current.paid + payment.amount))
    }
}

// Migrasi dari versi awal: membuat tabel riwayat dan mencatat pembayaran lama
// agar jumlah yang sudah dibayar tidak hilang dari riwayat.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS payments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "bookingId INTEGER NOT NULL, amount REAL NOT NULL, " +
                "paidAt TEXT NOT NULL, note TEXT NOT NULL, " +
                "FOREIGN KEY(bookingId) REFERENCES bookings(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_payments_bookingId ON payments(bookingId)")
        db.execSQL(
            "INSERT INTO payments (bookingId, amount, paidAt, note) " +
                "SELECT id, paid, strftime('%Y-%m-%d %H:%M', 'now', 'localtime'), " +
                "'Pembayaran tercatat sebelum fitur riwayat tersedia' " +
                "FROM bookings WHERE paid > 0"
        )
    }
}

@Database(entities = [Booking::class, Payment::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookingDao(): BookingDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: android.content.Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "umroh_handling.db"
            ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HandlingApp(AppDatabase.get(this).bookingDao()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandlingApp(dao: BookingDao) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bookings by dao.observeAll().collectAsState(initial = emptyList())
    var showForm by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Booking?>(null) }
    var paymentFor by remember { mutableStateOf<Booking?>(null) }
    var historyFor by remember { mutableStateOf<Booking?>(null) }
    var deleteFor by remember { mutableStateOf<Booking?>(null) }
    var filter by remember { mutableStateOf("Semua") }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                val writer = context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)
                    ?: throw IllegalStateException("File tidak dapat dibuka.")
                writer.use {
                    it.write("\uFEFF") // BOM agar karakter Indonesia terbaca baik di Excel.
                    it.write(bookingsToCsv(bookings))
                }
                Toast.makeText(context, "Laporan berhasil disimpan.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal menyimpan laporan: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val navy = Color(0xFF10243A)
    MaterialTheme(colorScheme = lightColorScheme(primary = navy, secondary = Color(0xFFC49A45))) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("UMROH AIRPORT HANDLING", fontWeight = FontWeight.Bold)
                            Text("Pemesanan & pembayaran travel", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        TextButton(onClick = { showReport = true }) { Text("Laporan") }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { editing = null; showForm = true }) { Text("+") }
            }
        ) { padding ->
            Column(Modifier.padding(padding).padding(16.dp)) {
                SummaryCard(bookings)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Semua", "Kedatangan", "Kepulangan").forEach { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val visible = bookings.filter {
                    filter == "Semua" ||
                        (filter == "Kedatangan" && it.type == "KEDATANGAN") ||
                        (filter == "Kepulangan" && it.type == "KEPULANGAN")
                }
                if (visible.isEmpty()) {
                    Text(
                        if (bookings.isEmpty()) "Belum ada pemesanan. Tekan tombol + untuk menambahkan travel."
                        else "Belum ada data untuk kategori ini."
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visible, key = { it.id }) { booking ->
                            BookingCard(
                                booking,
                                onEdit = { editing = booking; showForm = true },
                                onPayment = { paymentFor = booking },
                                onHistory = { historyFor = booking },
                                onDelete = { deleteFor = booking }
                            )
                        }
                    }
                }
            }
        }

        if (showForm) {
            BookingForm(
                initial = editing,
                onDismiss = { showForm = false; editing = null },
                onSave = { booking ->
                    scope.launch {
                        if (editing == null) {
                            dao.insertBookingWithInitialPayment(booking)
                        } else {
                            dao.update(booking.copy(id = editing!!.id, paid = editing!!.paid))
                        }
                    }
                    showForm = false
                    editing = null
                }
            )
        }

        paymentFor?.let { booking ->
            PaymentForm(
                booking = booking,
                onDismiss = { paymentFor = null },
                onSave = { amount, note ->
                    scope.launch {
                        try {
                            dao.recordPayment(
                                Payment(
                                    bookingId = booking.id,
                                    amount = amount,
                                    paidAt = today(),
                                    note = note
                                )
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "Pembayaran gagal disimpan.", Toast.LENGTH_LONG).show()
                        }
                    }
                    paymentFor = null
                }
            )
        }

        historyFor?.let { booking ->
            PaymentHistoryDialog(booking, dao, onDismiss = { historyFor = null })
        }

        deleteFor?.let { booking ->
            AlertDialog(
                onDismissRequest = { deleteFor = null },
                title = { Text("Hapus pemesanan?") },
                text = { Text("Data ${booking.travel} dan seluruh riwayat pembayarannya akan dihapus dari perangkat ini.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { dao.delete(booking) }
                        deleteFor = null
                    }) { Text("Hapus") }
                },
                dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Batal") } }
            )
        }

        if (showReport) {
            ReportDialog(
                bookings = bookings,
                onDismiss = { showReport = false },
                onExport = {
                    csvLauncher.launch("Laporan_Umroh_Handling_${today().take(10)}.csv")
                    showReport = false
                }
            )
        }
    }
}

@Composable
fun SummaryCard(bookings: List<Booking>) {
    val sar = bookings.filter { it.currency == "SAR" }
    val idr = bookings.filter { it.currency == "IDR" }
    val arrivals = bookings.count { it.type == "KEDATANGAN" }
    val departures = bookings.count { it.type == "KEPULANGAN" }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Ringkasan pemesanan", color = Color.White, fontWeight = FontWeight.Bold)
            Text("${bookings.size} pemesanan • $arrivals kedatangan • $departures kepulangan", color = Color.White)
            HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
            Text(
                "SAR  Tagihan ${money(sar.sumOf { it.fee })}  |  Dibayar ${money(sar.sumOf { it.paid })}  |  Sisa ${money(sar.sumOf { it.balance })}",
                color = Color.White
            )
            Text(
                "IDR  Tagihan ${money(idr.sumOf { it.fee })}  |  Dibayar ${money(idr.sumOf { it.paid })}  |  Sisa ${money(idr.sumOf { it.balance })}",
                color = Color.White
            )
        }
    }
}

@Composable
fun BookingCard(
    b: Booking,
    onEdit: () -> Unit,
    onPayment: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit
) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(b.travel, fontWeight = FontWeight.Bold)
                Text(
                    if (b.type == "KEDATANGAN") "Kedatangan" else "Kepulangan",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (b.contact.isNotBlank()) Text("Kontak: ${b.contact}")
            Text("${b.flight.ifBlank { "Penerbangan belum diisi" }} • ${b.flightDate.ifBlank { "Tanggal belum diisi" }}")
            Text("${b.passengers} jamaah • ${b.bags} koper")
            Text("Tagihan: ${b.currency} ${money(b.fee)}")
            Text("Dibayar: ${b.currency} ${money(b.paid)} • Sisa: ${b.currency} ${money(b.balance)}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(b.paymentStatus) })
                TextButton(onClick = onPayment, enabled = b.balance > 0.0) { Text("Tambah bayar") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onHistory) { Text("Riwayat") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Hapus") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingForm(initial: Booking?, onDismiss: () -> Unit, onSave: (Booking) -> Unit) {
    var travel by remember(initial) { mutableStateOf(initial?.travel ?: "") }
    var contact by remember(initial) { mutableStateOf(initial?.contact ?: "") }
    var type by remember(initial) { mutableStateOf(initial?.type ?: "KEDATANGAN") }
    var flight by remember(initial) { mutableStateOf(initial?.flight ?: "") }
    var date by remember(initial) { mutableStateOf(initial?.flightDate ?: "") }
    var passengers by remember(initial) { mutableStateOf(initial?.passengers?.toString() ?: "") }
    var bags by remember(initial) { mutableStateOf(initial?.bags?.toString() ?: "") }
    var currency by remember(initial) { mutableStateOf(initial?.currency ?: "SAR") }
    var fee by remember(initial) { mutableStateOf(initial?.fee?.toString() ?: "") }
    var initialPaid by remember(initial) { mutableStateOf(initial?.paid?.toString() ?: "0") }
    var notes by remember(initial) { mutableStateOf(initial?.notes ?: "") }
    var expanded by remember { mutableStateOf(false) }
    val parsedFee = parseAmount(fee)
    val parsedInitialPaid = parseAmount(initialPaid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Pemesanan travel baru" else "Edit pemesanan") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(travel, { travel = it }, label = { Text("Nama travel *") }, singleLine = true)
                OutlinedTextField(contact, { contact = it }, label = { Text("Kontak / penanggung jawab") }, singleLine = true)
                Text("Jenis pemesanan")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == "KEDATANGAN", { type = "KEDATANGAN" }, label = { Text("Kedatangan") })
                    FilterChip(type == "KEPULANGAN", { type = "KEPULANGAN" }, label = { Text("Kepulangan") })
                }
                OutlinedTextField(flight, { flight = it }, label = { Text("Maskapai / nomor penerbangan") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Tanggal (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(
                    passengers,
                    { passengers = it.filter(Char::isDigit) },
                    label = { Text("Jumlah jamaah") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    bags,
                    { bags = it.filter(Char::isDigit) },
                    label = { Text("Jumlah koper") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                if (initial != null && initial.paid > 0.0) {
                    OutlinedTextField(
                        currency,
                        {},
                        readOnly = true,
                        label = { Text("Mata uang (terkunci karena sudah ada pembayaran)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            currency,
                            {},
                            readOnly = true,
                            label = { Text("Mata uang") },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("SAR", "IDR").forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c) },
                                    onClick = { currency = c; expanded = false }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    fee,
                    { fee = it },
                    label = { Text("Total biaya handling *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                if (initial == null) {
                    OutlinedTextField(
                        initialPaid,
                        { initialPaid = it },
                        label = { Text("Pembayaran awal (opsional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                } else {
                    Text(
                        "Sudah dibayar: ${initial.currency} ${money(initial.paid)} (pembayaran berikutnya melalui tombol Tambah bayar)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Catatan (opsional)") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = travel.isNotBlank() &&
                    parsedFee != null &&
                    parsedInitialPaid != null &&
                    parsedInitialPaid >= 0.0 &&
                    parsedInitialPaid <= parsedFee &&
                    parsedFee >= (initial?.paid ?: 0.0),
                onClick = {
                    onSave(
                        Booking(
                            id = initial?.id ?: 0,
                            travel = travel.trim(),
                            contact = contact.trim(),
                            type = type,
                            flight = flight.trim(),
                            flightDate = date.trim(),
                            passengers = passengers.toIntOrNull() ?: 0,
                            bags = bags.toIntOrNull() ?: 0,
                            currency = currency,
                            fee = parsedFee ?: 0.0,
                            paid = if (initial == null) parsedInitialPaid ?: 0.0 else initial.paid,
                            notes = notes.trim()
                        )
                    )
                }
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
fun PaymentForm(booking: Booking, onDismiss: () -> Unit, onSave: (Double, String) -> Unit) {
    var amount by remember { mutableStateOf(booking.balance.toString()) }
    var note by remember { mutableStateOf("") }
    val parsedAmount = parseAmount(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah pembayaran") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sisa tagihan: ${booking.currency} ${money(booking.balance)}")
                OutlinedTextField(
                    amount,
                    { amount = it },
                    label = { Text("Jumlah dibayar") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(note, { note = it }, label = { Text("Catatan pembayaran") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedAmount != null && parsedAmount > 0.0 && parsedAmount <= booking.balance,
                onClick = { onSave(parsedAmount ?: 0.0, note.trim()) }
            ) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
fun PaymentHistoryDialog(booking: Booking, dao: BookingDao, onDismiss: () -> Unit) {
    val payments by dao.observePayments(booking.id).collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Riwayat pembayaran") },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(booking.travel, fontWeight = FontWeight.Bold)
                Text("Total tercatat: ${booking.currency} ${money(booking.paid)}")
                if (payments.isEmpty()) {
                    Text("Belum ada catatan pembayaran. Pembayaran berikutnya akan tercatat di sini.")
                } else {
                    payments.forEach { payment ->
                        HorizontalDivider()
                        Text("${booking.currency} ${money(payment.amount)}", fontWeight = FontWeight.SemiBold)
                        Text(payment.paidAt)
                        if (payment.note.isNotBlank()) Text(payment.note)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

@Composable
fun ReportDialog(bookings: List<Booking>, onDismiss: () -> Unit, onExport: () -> Unit) {
    val sar = bookings.filter { it.currency == "SAR" }
    val idr = bookings.filter { it.currency == "IDR" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Laporan pemesanan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Jumlah pemesanan: ${bookings.size}")
                Text("Kedatangan: ${bookings.count { it.type == "KEDATANGAN" }}")
                Text("Kepulangan: ${bookings.count { it.type == "KEPULANGAN" }}")
                HorizontalDivider()
                Text("RINGKASAN SAR", fontWeight = FontWeight.Bold)
                Text("Tagihan: SAR ${money(sar.sumOf { it.fee })}")
                Text("Dibayar: SAR ${money(sar.sumOf { it.paid })}")
                Text("Sisa: SAR ${money(sar.sumOf { it.balance })}")
                HorizontalDivider()
                Text("RINGKASAN IDR", fontWeight = FontWeight.Bold)
                Text("Tagihan: IDR ${money(idr.sumOf { it.fee })}")
                Text("Dibayar: IDR ${money(idr.sumOf { it.paid })}")
                Text("Sisa: IDR ${money(idr.sumOf { it.balance })}")
                Text("Laporan CSV berisi rincian setiap pemesanan.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onExport) { Text("Simpan CSV") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

fun bookingsToCsv(bookings: List<Booking>): String {
    val rows = mutableListOf(
        listOf(
            "ID", "Jenis", "Nama Travel", "Kontak", "Maskapai / Nomor Penerbangan",
            "Tanggal", "Jumlah Jamaah", "Jumlah Koper", "Mata Uang", "Tagihan",
            "Dibayar", "Sisa", "Status", "Catatan"
        )
    )
    bookings.forEach { b ->
        rows += listOf(
            b.id.toString(),
            if (b.type == "KEDATANGAN") "Kedatangan" else "Kepulangan",
            b.travel,
            b.contact,
            b.flight,
            b.flightDate,
            b.passengers.toString(),
            b.bags.toString(),
            b.currency,
            b.fee.toString(),
            b.paid.toString(),
            b.balance.toString(),
            b.paymentStatus,
            b.notes
        )
    }
    return rows.joinToString("\r\n") { row ->
        row.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }
    }
}

fun parseAmount(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()

fun today(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

fun money(value: Double): String = if (value % 1.0 == 0.0) {
    String.format(Locale.US, "%,.0f", value)
} else {
    String.format(Locale.US, "%,.2f", value)
}
