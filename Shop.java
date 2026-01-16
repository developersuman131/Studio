package com.shopcalc

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items 
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ==========================================
// 1. DATA LAYER (ENTITIES & DB)
// ==========================================

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
    val isFavorite: Boolean = false,
    val category: String = "General",
    val stock: Int = 0,
    val barcode: String = "",
    val imageUrl: String = ""
)

@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val customerName: String,
    val customerPhone: String = "",
    val subTotal: Double,
    val discount: Double,
    val tax: Double,
    val finalTotal: Double,
    val itemsJson: String,
    val paymentMethod: String = "Cash"
)

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val description: String,
    val amount: Double,
    val category: String
)

data class CartItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val price: Double,
    val weight: Double,
    val total: Double,
    val quantity: Int = 1
)

@Dao
interface ShopDao {
    @Query("SELECT * FROM products ORDER BY isFavorite DESC, name ASC")
    fun getProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE category = :category ORDER BY name ASC")
    fun getProductsByCategory(category: String): Flow<List<Product>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: Product)

    @Update
    suspend fun updateProduct(product: Product)

    @Delete
    suspend fun deleteProduct(product: Product)

    @Query("SELECT * FROM bills ORDER BY date DESC")
    fun getBills(): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getBillsByDateRange(startDate: Long, endDate: Long): Flow<List<Bill>>

    @Insert
    suspend fun insertBill(bill: Bill)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBill(id: Long)

    @Query("DELETE FROM bills")
    suspend fun deleteAllBills()

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getExpenses(): Flow<List<Expense>>

    @Insert
    suspend fun insertExpense(expense: Expense)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteExpense(id: Long)

    // Dashboard Stats
    @Query("SELECT SUM(finalTotal) FROM bills WHERE date >= :startOfDay")
    fun getTodaySales(startOfDay: Long): Flow<Double?>

    @Query("SELECT SUM(finalTotal) FROM bills WHERE date >= :startOfWeek")
    fun getWeeklySales(startOfWeek: Long): Flow<Double?>

    @Query("SELECT SUM(finalTotal) FROM bills WHERE date >= :startOfMonth")
    fun getMonthlySales(startOfMonth: Long): Flow<Double?>

    @Query("SELECT COUNT(*) FROM bills WHERE date >= :startOfDay")
    fun getTodayBillCount(startOfDay: Long): Flow<Int>

    @Query("SELECT SUM(amount) FROM expenses WHERE date >= :startOfDay")
    fun getTodayExpenses(startOfDay: Long): Flow<Double?>

    @Query("SELECT DISTINCT category FROM products WHERE category != ''")
    fun getCategories(): Flow<List<String>>
}

@Database(entities = [Product::class, Bill::class, Expense::class], version = 3, exportSchema = false)
abstract class ShopDatabase : RoomDatabase() {
    abstract fun dao(): ShopDao

    companion object {
        @Volatile private var INSTANCE: ShopDatabase? = null
        fun get(context: Context): ShopDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, ShopDatabase::class.java, "shop_v3.db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

// ==========================================
// 2. VIEWMODEL (LOGIC CORE)
// ==========================================

class ShopViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = ShopDatabase.get(application).dao()
    private val gson = Gson()
    private val vibrator = application.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // --- Business State ---
    val products = dao.getProducts().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val bills = dao.getBills().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val expenses = dao.getExpenses().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val categories = dao.getCategories().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    // Dashboard Logic
    val todaySales = dao.getTodaySales(getStartOfDay()).map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)
    val weeklySales = dao.getWeeklySales(getStartOfWeek()).map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)
    val monthlySales = dao.getMonthlySales(getStartOfMonth()).map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)
    val todayBillCount = dao.getTodayBillCount(getStartOfDay())
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    val todayExpenses = dao.getTodayExpenses(getStartOfDay()).map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    // Cart Logic
    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart = _cart.asStateFlow()
    
    var discountPercent by mutableStateOf(0.0)
    var taxPercent by mutableStateOf(0.0)
    var customerName by mutableStateOf("")
    var customerPhone by mutableStateOf("")
    var paymentMethod by mutableStateOf("Cash")

    val cartSubTotal = _cart.map { it.sumOf { item -> item.total } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val cartFinalTotal = combine(cartSubTotal, snapshotFlow { discountPercent }, snapshotFlow { taxPercent }) { sub, disc, tax ->
        val afterDisc = sub - (sub * (disc / 100))
        afterDisc + (afterDisc * (tax / 100))
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    // Inputs
    var inputPrice by mutableStateOf("")
    var inputWeight by mutableStateOf("")
    var inputQuantity by mutableStateOf("1")
    var productSearchQuery by mutableStateOf("")
    var selectedCategory by mutableStateOf("All")

    // Calculation Mode
    var calcMode by mutableStateOf("Weight") // "Weight" or "Quantity"

    // Live Calculation for Home Screen
    val calcTotal: Double get() = try {
        when (calcMode) {
            "Weight" -> {
                val p = inputPrice.toDoubleOrNull() ?: 0.0
                val w = inputWeight.toDoubleOrNull() ?: 0.0
                if (w > 0) (p * w) / 1000.0 else 0.0
            }
            "Quantity" -> {
                val p = inputPrice.toDoubleOrNull() ?: 0.0
                val q = inputQuantity.toDoubleOrNull() ?: 0.0
                p * q
            }
            else -> 0.0
        }
    } catch (e: Exception) {
        0.0
    }

    // Standard Calculator State
    var calcDisplay by mutableStateOf("0")
    private var calcOperand1: Double? = null
    private var calcOperator: String? = null
    private var shouldResetDisplay = false
    private var calcHistory = mutableStateListOf<String>()

    // Scientific Calculator State
    var scientificMode by mutableStateOf(false)
    var angleMode by mutableStateOf("DEG") // DEG or RAD

    // --- Actions ---
    
    fun hapticFeedback() {
        try {
            vibrator?.let {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    it.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    it.vibrate(30)
                }
            }
        } catch (e: Exception) {
            // Ignore haptic errors
        }
    }

    fun addToCart(productName: String? = null) {
        try {
            val p = inputPrice.toDoubleOrNull() ?: return
            if (p <= 0) return

            val name = productName ?: "Custom Item"
            
            val item = when (calcMode) {
                "Weight" -> {
                    val w = inputWeight.toDoubleOrNull() ?: return
                    if (w <= 0) return
                    val total = (p * w) / 1000.0
                    CartItem(name = name, price = p, weight = w, total = total, quantity = 1)
                }
                "Quantity" -> {
                    val q = inputQuantity.toIntOrNull() ?: return
                    if (q <= 0) return
                    val total = p * q
                    CartItem(name = name, price = p, weight = 0.0, total = total, quantity = q)
                }
                else -> return
            }
            
            _cart.value += item
            
            inputPrice = ""
            inputWeight = ""
            inputQuantity = "1"
            hapticFeedback()
        } catch (e: Exception) {
            // Handle error silently
        }
    }

    fun removeFromCart(item: CartItem) {
        _cart.value -= item
        hapticFeedback()
    }

    fun updateCartItemQuantity(item: CartItem, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeFromCart(item)
            return
        }
        val updated = item.copy(
            quantity = newQuantity,
            total = item.price * newQuantity
        )
        _cart.value = _cart.value.map { if (it.id == item.id) updated else it }
    }

    fun clearCart() {
        _cart.value = emptyList()
        discountPercent = 0.0
        taxPercent = 0.0
        customerName = ""
        customerPhone = ""
        paymentMethod = "Cash"
    }

    fun saveBill() {
        val currentCart = _cart.value
        if (currentCart.isEmpty()) return

        try {
            val sub = currentCart.sumOf { it.total }
            val discAmt = sub * (discountPercent / 100)
            val afterDisc = sub - discAmt
            val taxAmt = afterDisc * (taxPercent / 100)
            val final = afterDisc + taxAmt

            val json = gson.toJson(currentCart)
            
            viewModelScope.launch {
                dao.insertBill(Bill(
                    date = System.currentTimeMillis(),
                    customerName = customerName.ifEmpty { "Walk-in" },
                    customerPhone = customerPhone,
                    subTotal = sub,
                    discount = discAmt,
                    tax = taxAmt,
                    finalTotal = final,
                    itemsJson = json,
                    paymentMethod = paymentMethod
                ))
                clearCart()
            }
        } catch (e: Exception) {
            // Handle error
        }
    }

    // Calculator Logic - Enhanced
    fun onCalcBtn(btn: String) {
        hapticFeedback()
        try {
            when(btn) {
                "C" -> { 
                    calcDisplay = "0"
                    calcOperand1 = null
                    calcOperator = null
                    shouldResetDisplay = false
                }
                "⌫" -> { 
                    calcDisplay = if (calcDisplay.length > 1) calcDisplay.dropLast(1) else "0"
                }
                "+", "-", "×", "÷", "^" -> {
                    calcOperand1 = calcDisplay.toDoubleOrNull()
                    calcOperator = btn
                    shouldResetDisplay = true
                }
                "=" -> {
                    val op2 = calcDisplay.toDoubleOrNull()
                    if (calcOperand1 != null && op2 != null && calcOperator != null) {
                        val res = when(calcOperator) {
                            "+" -> calcOperand1!! + op2
                            "-" -> calcOperand1!! - op2
                            "×" -> calcOperand1!! * op2
                            "÷" -> if(op2 != 0.0) calcOperand1!! / op2 else Double.NaN
                            "^" -> calcOperand1!!.pow(op2)
                            else -> 0.0
                        }
                        
                        if (res.isNaN() || res.isInfinite()) {
                            calcDisplay = "Error"
                        } else {
                            val formatted = formatCalcResult(res)
                            calcHistory.add(0, "$calcOperand1 $calcOperator $op2 = $formatted")
                            if (calcHistory.size > 20) calcHistory.removeLast()
                            calcDisplay = formatted
                        }
                        calcOperand1 = null
                        calcOperator = null
                    }
                }
                "." -> if (!calcDisplay.contains(".")) calcDisplay += "."
                "±" -> {
                    val num = calcDisplay.toDoubleOrNull()
                    if (num != null) calcDisplay = formatCalcResult(-num)
                }
                "%" -> {
                    val num = calcDisplay.toDoubleOrNull()
                    if (num != null) calcDisplay = formatCalcResult(num / 100)
                }
                // Scientific functions
                "sin" -> scientificCalc { sin(toRadians(it)) }
                "cos" -> scientificCalc { cos(toRadians(it)) }
                "tan" -> scientificCalc { tan(toRadians(it)) }
                "√" -> scientificCalc { sqrt(it) }
                "ln" -> scientificCalc { ln(it) }
                "log" -> scientificCalc { log10(it) }
                "x²" -> scientificCalc { it * it }
                "1/x" -> scientificCalc { 1 / it }
                "π" -> calcDisplay = PI.toString()
                "e" -> calcDisplay = E.toString()
                else -> {
                    if (calcDisplay == "0" || shouldResetDisplay || calcDisplay == "Error") {
                        calcDisplay = btn
                        shouldResetDisplay = false
                    } else {
                        if (calcDisplay.length < 12) calcDisplay += btn
                    }
                }
            }
        } catch (e: Exception) {
            calcDisplay = "Error"
        }
    }

    private fun scientificCalc(operation: (Double) -> Double) {
        val num = calcDisplay.toDoubleOrNull()
        if (num != null) {
            val result = operation(num)
            calcDisplay = if (result.isNaN() || result.isInfinite()) "Error" else formatCalcResult(result)
        }
    }

    private fun toRadians(degrees: Double): Double {
        return if (angleMode == "RAD") degrees else Math.toRadians(degrees)
    }

    private fun formatCalcResult(value: Double): String {
        return when {
            value.isNaN() || value.isInfinite() -> "Error"
            value % 1.0 == 0.0 && abs(value) < 1e10 -> value.toLong().toString()
            abs(value) < 0.0001 -> String.format("%.2e", value)
            else -> {
                val formatted = String.format("%.8f", value).trimEnd('0').trimEnd('.')
                if (formatted.length > 12) String.format("%.2e", value) else formatted
            }
        }
    }

    // Product Management
    fun addProduct(name: String, price: Double, category: String, stock: Int = 0) {
        if (name.isBlank() || price <= 0) return
        viewModelScope.launch { 
            dao.insertProduct(Product(
                name = name.trim(), 
                price = price, 
                category = category.trim().ifEmpty { "General" },
                stock = stock
            )) 
        }
    }
    
    fun updateProduct(product: Product) {
        viewModelScope.launch { dao.updateProduct(product) }
    }
    
    fun toggleFavorite(product: Product) {
        viewModelScope.launch { dao.insertProduct(product.copy(isFavorite = !product.isFavorite)) }
    }
    
    fun deleteProduct(product: Product) = viewModelScope.launch { dao.deleteProduct(product) }
    
    fun addExpense(description: String, amount: Double, category: String) {
        if (description.isBlank() || amount <= 0) return
        viewModelScope.launch {
            dao.insertExpense(Expense(
                date = System.currentTimeMillis(),
                description = description,
                amount = amount,
                category = category
            ))
        }
    }
    
    fun deleteExpense(expense: Expense) = viewModelScope.launch { dao.deleteExpense(expense.id) }
    fun deleteBill(bill: Bill) = viewModelScope.launch { dao.deleteBill(bill.id) }
    fun nukeData() = viewModelScope.launch { 
        dao.deleteAllBills()
    }

    fun getStartOfDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun getStartOfWeek(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun getStartOfMonth(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

// ==========================================
// 3. MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[ShopViewModel::class.java]
        setContent { ShopCalcApp(viewModel) }
    }
}

@Composable
fun ShopCalcApp(vm: ShopViewModel) {
    val navController = rememberNavController()
    var isDarkMode by remember { mutableStateOf(false) }

    MaterialTheme(
        colorScheme = if (isDarkMode) darkColorScheme(
            primary = Color(0xFF6750A4),
            secondary = Color(0xFF625B71),
            tertiary = Color(0xFF7D5260)
        ) else lightColorScheme(
            primary = Color(0xFF6750A4),
            secondary = Color(0xFF625B71),
            tertiary = Color(0xFF7D5260)
        )
    ) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") { HomeScreen(navController, vm) }
            composable("simple_calc") { SimpleCalcScreen(navController, vm) }
            composable("scientific_calc") { ScientificCalcScreen(navController, vm) }
            composable("cart") { CartScreen(navController, vm) }
            composable("history") { HistoryScreen(navController, vm) }
            composable("products") { ProductScreen(navController, vm) }
            composable("expenses") { ExpensesScreen(navController, vm) }
            composable("analytics") { AnalyticsScreen(navController, vm) }
            composable("settings") { SettingsScreen(navController, vm, isDarkMode) { isDarkMode = !isDarkMode } }
            composable("change_calc") { ChangeReturnScreen(navController) }
        }
    }
}

// ==========================================
// 4. UI SCREENS - HOME
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, vm: ShopViewModel) {
    var showMenu by remember { mutableStateOf(false) }
    val products by vm.products.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    val todaySales by vm.todaySales.collectAsState()
    val todayBills by vm.todayBillCount.collectAsState()
    val cartItems by vm.cart.collectAsState()

    val filteredProducts = products.filter { 
        it.isFavorite && (vm.selectedCategory == "All" || it.category == vm.selectedCategory)
    }
    val categories = products.map { it.category }.distinct().filter { it.isNotBlank() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("ShopCalc Pro", fontWeight = FontWeight.Bold)
                        Text(
                            "₹${String.format("%.0f", todaySales)} • $todayBills bills",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    // Cart Badge
                    BadgedBox(
                        badge = {
                            if (cartItems.isNotEmpty()) {
                                Badge { Text("${cartItems.size}") }
                            }
                        }
                    ) {
                        IconButton(onClick = { navController.navigate("cart") }) { 
                            Icon(Icons.Default.ShoppingCart, "Cart") 
                        }
                    }
                    IconButton(onClick = { navController.navigate("simple_calc") }) { 
                        Icon(Icons.Default.Calculate, "Calc") 
                    }
                    IconButton(onClick = { showMenu = true }) { 
                        Icon(Icons.Default.MoreVert, "Menu") 
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("📊 Dashboard") },
                            onClick = { showMenu = false; navController.navigate("history") }
                        )
                        DropdownMenuItem(
                            text = { Text("📦 Products") },
                            onClick = { showMenu = false; navController.navigate("products") }
                        )
                        DropdownMenuItem(
                            text = { Text("💰 Expenses") },
                            onClick = { showMenu = false; navController.navigate("expenses") }
                        )
                        DropdownMenuItem(
                            text = { Text("📈 Analytics") },
                            onClick = { showMenu = false; navController.navigate("analytics") }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("🔬 Scientific Calc") },
                            onClick = { showMenu = false; navController.navigate("scientific_calc") }
                        )
                        DropdownMenuItem(
                            text = { Text("💵 Change Calculator") },
                            onClick = { showMenu = false; navController.navigate("change_calc") }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("⚙️ Settings") },
                            onClick = { showMenu = false; navController.navigate("settings") }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            
            // Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = vm.calcMode == "Weight",
                    onClick = { vm.calcMode = "Weight"; vm.hapticFeedback() },
                    label = { Text("By Weight (g)") },
                    leadingIcon = { Icon(Icons.Default.Scale, null) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = vm.calcMode == "Quantity",
                    onClick = { vm.calcMode = "Quantity"; vm.hapticFeedback() },
                    label = { Text("By Quantity") },
                    leadingIcon = { Icon(Icons.Default.Numbers, null) },
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(12.dp))

            // TOTAL DISPLAY
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth().height(100.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        "Total",
                        Modifier.align(Alignment.TopStart),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "₹${String.format("%.2f", vm.calcTotal)}",
                        modifier = Modifier.align(Alignment.BottomEnd),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // INPUTS
            AnimatedVisibility(visible = vm.calcMode == "Weight") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.inputPrice,
                        onValueChange = { vm.inputPrice = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price/KG") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Default.CurrencyRupee, null) },
                        trailingIcon = { 
                            IconButton(onClick = { showPicker = true }) { 
                                Icon(Icons.Default.List, "Pick") 
                            } 
                        }
                    )
                    OutlinedTextField(
                        value = vm.inputWeight,
                        onValueChange = { vm.inputWeight = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Grams") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Default.Scale, null) }
                    )
                }
            }

            AnimatedVisibility(visible = vm.calcMode == "Quantity") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.inputPrice,
                        onValueChange = { vm.inputPrice = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Price/Unit") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Default.CurrencyRupee, null) },
                        trailingIcon = { 
                            IconButton(onClick = { showPicker = true }) { 
                                Icon(Icons.Default.List, "Pick") 
                            } 
                        }
                    )
                    OutlinedTextField(
                        value = vm.inputQuantity,
                        onValueChange = { vm.inputQuantity = it.filter { c -> c.isDigit() } },
                        label = { Text("Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Default.Numbers, null) }
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))

            // QUICK WEIGHTS/QUANTITIES
            AnimatedVisibility(visible = vm.calcMode == "Weight") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(50, 100, 250, 500, 750, 1000)) { g ->
                        SuggestionChip(
                            onClick = { vm.inputWeight = g.toString(); vm.hapticFeedback() },
                            label = { Text(if (g >= 1000) "${g/1000}KG" else "${g}g") }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = vm.calcMode == "Quantity") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(1, 2, 3, 5, 10, 12, 24, 50)) { q ->
                        SuggestionChip(
                            onClick = { vm.inputQuantity = q.toString(); vm.hapticFeedback() },
                            label = { Text("$q") }
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(12.dp))

            // ACTIONS
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { 
                        vm.inputPrice = ""
                        vm.inputWeight = ""
                        vm.inputQuantity = "1"
                        vm.hapticFeedback()
                    },
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { 
                    Icon(Icons.Default.Clear, "Clear")
                    Spacer(Modifier.width(4.dp))
                    Text("Clear") 
                }

                Button(
                    onClick = { vm.addToCart() },
                    modifier = Modifier.weight(2f).height(56.dp),
                    enabled = vm.calcTotal > 0
                ) {
                    Icon(Icons.Default.AddShoppingCart, null)
                    Spacer(Modifier.width(8.dp))
                    Text("ADD TO BILL", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            
            // Category Filter
            if (categories.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = vm.selectedCategory == "All",
                            onClick = { vm.selectedCategory = "All" },
                            label = { Text("All") }
                        )
                    }
                    items(categories) { cat ->
                        FilterChip(
                            selected = vm.selectedCategory == cat,
                            onClick = { vm.selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            
            // QUICK FAVORITES
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Quick Favorites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = { navController.navigate("products") }) {
                    Text("Manage")
                }
            }
            
            // Grid for Favorites
            if (filteredProducts.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3), 
                    verticalArrangement = Arrangement.spacedBy(8.dp), 
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    items(filteredProducts) { p ->
                        Card(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable { 
                                    vm.inputPrice = p.price.toString()
                                    vm.hapticFeedback()
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    p.name,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    "₹${p.price}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.StarBorder,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No Favorites Yet", color = Color.Gray)
                        TextButton(onClick = { navController.navigate("products") }) {
                            Text("Add Products")
                        }
                    }
                }
            }
        }
    }

    if(showPicker) {
        ProductPickerDialog(vm) { showPicker = false }
    }
}

// ==========================================
// 5. CALCULATOR SCREENS
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleCalcScreen(navController: NavController, vm: ShopViewModel) {
    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Calculator") },
                navigationIcon = { 
                    IconButton(onClick = { navController.popBackStack() }) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "") 
                    } 
                },
                actions = {
                    TextButton(onClick = { navController.navigate("scientific_calc") }) {
                        Text("Scientific →")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            // Display
            Card(
                modifier = Modifier.fillMaxWidth().weight(0.3f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        vm.calcDisplay,
                        Modifier.align(Alignment.BottomEnd),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Keypad
            val buttons = listOf(
                listOf("C", "⌫", "%", "÷"),
                listOf("7", "8", "9", "×"),
                listOf("4", "5", "6", "-"),
                listOf("1", "2", "3", "+"),
                listOf("±", "0", ".", "=")
            )
            
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(0.7f)
            ) {
                buttons.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        row.forEach { btn ->
                            Button(
                                onClick = { vm.onCalcBtn(btn) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape(16.dp),
                                colors = when(btn) {
                                    "=" -> ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                    in listOf("+", "-", "×", "÷") -> ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    in listOf("C", "⌫", "%", "±") -> ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    else -> ButtonDefaults.filledTonalButtonColors()
                                }
                            ) {
                                Text(btn, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScientificCalcScreen(navController: NavController, vm: ShopViewModel) {
    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Scientific Calculator") },
                navigationIcon = { 
                    IconButton(onClick = { navController.popBackStack() }) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "") 
                    } 
                },
                actions = {
                    FilterChip(
                        selected = vm.angleMode == "DEG",
                        onClick = { vm.angleMode = if (vm.angleMode == "DEG") "RAD" else "DEG" },
                        label = { Text(vm.angleMode) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            // Display
            Card(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        vm.calcDisplay,
                        Modifier.align(Alignment.BottomEnd),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Scientific Functions Row 1
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sin", "cos", "tan", "ln").forEach { btn ->
                    Button(
                        onClick = { vm.onCalcBtn(btn) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    ) { Text(btn, fontSize = 14.sp) }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Scientific Functions Row 2
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("√", "x²", "^", "1/x").forEach { btn ->
                    Button(
                        onClick = { vm.onCalcBtn(btn) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    ) { Text(btn, fontSize = 14.sp) }
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            // Standard Keypad
            val buttons = listOf(
                listOf("C", "⌫", "π", "÷"),
                listOf("7", "8", "9", "×"),
                listOf("4", "5", "6", "-"),
                listOf("1", "2", "3", "+"),
                listOf("e", "0", ".", "=")
            )
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                buttons.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        row.forEach { btn ->
                            Button(
                                onClick = { vm.onCalcBtn(btn) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape(12.dp),
                                colors = when(btn) {
                                    "=" -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    in listOf("+", "-", "×", "÷") -> ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    else -> ButtonDefaults.filledTonalButtonColors()
                                }
                            ) { Text(btn, fontSize = 20.sp) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeReturnScreen(navController: NavController) {
    var billAmount by remember { mutableStateOf("") }
    var givenAmount by remember { mutableStateOf("") }
    
    val bill = billAmount.toDoubleOrNull() ?: 0.0
    val given = givenAmount.toDoubleOrNull() ?: 0.0
    val change = given - bill

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Change Calculator") },
                navigationIcon = { 
                    IconButton(onClick = { navController.popBackStack() }) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "") 
                    } 
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = billAmount,
                onValueChange = { billAmount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Bill Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Receipt, null) }
            )
            
            OutlinedTextField(
                value = givenAmount,
                onValueChange = { givenAmount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount Given") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Payments, null) }
            )
            
            // Quick amount buttons
            Text("Quick Amounts", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(100, 200, 500, 1000, 2000, 5000)) { amount ->
                    SuggestionChip(
                        onClick = { givenAmount = amount.toString() },
                        label = { Text("₹$amount") }
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            if(given > 0 && bill > 0) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if(change >= 0) 
                            MaterialTheme.colorScheme.primaryContainer
                        else 
                            MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (change >= 0) "Return to Customer" else "Additional Payment Needed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "₹${String.format("%.2f", abs(change))}",
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (change >= 0) 
                                MaterialTheme.colorScheme.primary
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 6. CART SCREEN
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(navController: NavController, vm: ShopViewModel) {
    val cart by vm.cart.collectAsState()
    val subTotal by vm.cartSubTotal.collectAsState()
    val finalTotal by vm.cartFinalTotal.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Bill (${cart.size} items)") },
                navigationIcon = { 
                    IconButton(onClick = { navController.popBackStack() }) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "") 
                    } 
                },
                actions = {
                    if (cart.isNotEmpty()) {
                        IconButton(onClick = { vm.clearCart() }) {
                            Icon(Icons.Default.DeleteSweep, "Clear All")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (cart.isNotEmpty()) {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    Divider()
                    
                    // Tax & Discount
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (vm.discountPercent == 0.0) "" else vm.discountPercent.toString(),
                            onValueChange = { vm.discountPercent = it.toDoubleOrNull() ?: 0.0 },
                            label = { Text("Discount %") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = if (vm.taxPercent == 0.0) "" else vm.taxPercent.toString(),
                            onValueChange = { vm.taxPercent = it.toDoubleOrNull() ?: 0.0 },
                            label = { Text("Tax %") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    
                    // Totals
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal:", style = MaterialTheme.typography.bodyLarge)
                            Text("₹${String.format("%.2f", subTotal)}", fontWeight = FontWeight.Bold)
                        }
                        if (vm.discountPercent > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Discount (${vm.discountPercent}%):", color = Color.Green)
                                Text("-₹${String.format("%.2f", subTotal * vm.discountPercent / 100)}", color = Color.Green)
                            }
                        }
                        if (vm.taxPercent > 0) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tax (${vm.taxPercent}%):")
                                Text("+₹${String.format("%.2f", (subTotal - subTotal * vm.discountPercent / 100) * vm.taxPercent / 100)}")
                            }
                        }
                        Divider(Modifier.padding(vertical = 8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TOTAL:", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("₹${String.format("%.2f", finalTotal)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    
                    Button(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text("SAVE BILL", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        if (cart.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.ShoppingCart,
                        null,
                        modifier = Modifier.size(80.dp),
                        tint = Color.Gray
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Cart is Empty", style = MaterialTheme.typography.headlineSmall, color = Color.Gray)
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("Add Items")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(cart, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold)
                                Text(
                                    if (item.weight > 0)
                                        "${item.weight}g @ ₹${item.price}/kg"
                                    else
                                        "${item.quantity} × ₹${item.price}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                            Text(
                                "₹${String.format("%.2f", item.total)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { vm.removeFromCart(item) }) {
                                Icon(Icons.Default.Delete, "", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    
    if (showSaveDialog) {
        SaveBillDialog(vm, onDismiss = { showSaveDialog = false }, onSave = {
            vm.saveBill()
            showSaveDialog = false
            navController.popBackStack()
        })
    }
}

@Composable
fun SaveBillDialog(vm: ShopViewModel, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finalize Bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vm.customerName,
                    onValueChange = { vm.customerName = it },
                    label = { Text("Customer Name (Optional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = vm.customerPhone,
                    onValueChange = { vm.customerPhone = it.filter { c -> c.isDigit() } },
                    label = { Text("Phone (Optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                
                Text("Payment Method", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Cash", "Card", "UPI", "Other").forEach { method ->
                        FilterChip(
                            selected = vm.paymentMethod == method,
                            onClick = { vm.paymentMethod = method },
                            label = { Text(method) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("Save & Print")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// 7. HISTORY & DASHBOARD
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController, vm: ShopViewModel) {
    val bills by vm.bills.collectAsState()
    val todaySales by vm.todaySales.collectAsState()
    val weeklySales by vm.weeklySales.collectAsState()
    val monthlySales by vm.monthlySales.collectAsState()
    val todayBills by vm.todayBillCount.collectAsState()
    val todayExpenses by vm.todayExpenses.collectAsState()
    val context = LocalContext.current
    val df = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                navigationIcon = { 
                    IconButton(onClick = { navController.popBackStack() }) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "") 
                    } 
                },
                actions = {
                    IconButton(onClick = {
                        val csv = "Date,Customer,Phone,Subtotal,Discount,Tax,Total,Payment\n" +
                            bills.joinToString("\n") {
                                "${df.format(Date(it.date))},${it.customerName},${it.customerPhone},${it.subTotal},${it.discount},${it.tax},${it.finalTotal},${it.paymentMethod}"
                            }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_TEXT, csv)
                            putExtra(Intent.EXTRA_SUBJECT, "Sales Report")
                        }
                        context.startActivity(Intent.createChooser(intent, "Export CSV"))
                    }) {
                        Icon(Icons.Default.Share, "Export")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            // Stats Cards
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Today
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("TODAY", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "₹${String.format("%.0f", todaySales)}",
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text("$todayBills bills • ₹${String.format("%.0f", todayExpenses)} expenses")
                                }
                                Icon(Icons.Default.Today, null, modifier = Modifier.size(48.dp))
                            }
                        }
                    }
                    
                    // Week & Month
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("WEEK", style = MaterialTheme.typography.labelSmall)
                                Text("₹${String.format("%.0f", weeklySales)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        Card(modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("MONTH", style = MaterialTheme.typography.labelSmall)
                                Text("₹${String.format("%.0f", monthlySales)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    
                    Divider()
                    
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Transactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            // Bills List
            items(bills) { bill ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                bill.customerName,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(df.format(Date(bill.date)), style = MaterialTheme.typography.bodySmall)
                            if (bill.customerPhone.isNotBlank()) {
                                Text("📞 ${bill.customerPhone}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${bill.paymentMethod}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "₹${String.format("%.2f", bill.finalTotal)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val items = try {
                                Gson().fromJson(bill.itemsJson, Array<CartItem>::class.java)?.size ?: 0
                            } catch (e: Exception) { 0 }
                            Text("$items items", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.deleteBill(bill) }) {
                            Icon(Icons.Default.Delete, "", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            
            item {
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

// ==========================================
// 8. PRODUCTS SCREEN
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductScreen(navController: NavController, vm: ShopViewModel) {
    val products by vm.products.collectAsState()
    val categories by vm.categories.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("All") }

    val filteredProducts = products.filter {
        it.name.contains(searchQuery, true) &&
        (categoryFilter == "All" || it.category == categoryFilter)
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Products (${products.size})") },
                navigationIcon = { 
                    IconButton(onClick = { navController.popBackStack() }) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "") 
                    } 
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, "Add Product")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Products") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                leadingIcon = { Icon(Icons.Default.Search, "") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "")
                        }
                    }
                }
            )
            
            // Category Filter
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = categoryFilter == "All",
                        onClick = { categoryFilter = "All" },
                        label = { Text("All (${products.size})") }
                    )
                }
                items(categories) { cat ->
                    FilterChip(
                        selected = categoryFilter == cat,
                        onClick = { categoryFilter = cat },
                        label = { Text("$cat (${products.count { it.category == cat }})") }
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Products List
            if (filteredProducts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Inventory, null, modifier = Modifier.size(64.dp), tint = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text("No Products Found", color = Color.Gray)
                    }
                }
            } else {
                LazyColumn {
                    items(filteredProducts) { p ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text("₹${p.price}/kg", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(p.category, style = MaterialTheme.typography.bodySmall)
                                        if (p.stock > 0) {
                                            Text("• Stock: ${p.stock}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                Row {
                                    IconButton(onClick = { vm.toggleFavorite(p) }) {
                                        Icon(
                                            if (p.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                            "",
                                            tint = if (p.isFavorite) Color(0xFFFFD700) else Color.Gray
                                        )
                                    }
                                    IconButton(onClick = { vm.deleteProduct(p) }) {
                                        Icon(Icons.Default.Delete, "", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    
                    item {
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddProductDialog(vm, onDismiss = { showAdd = false })
    }
}

@Composable
fun AddProductDialog(vm: ShopViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("General") }
    var stock by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Product") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Product Name *") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Price/KG *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.CurrencyRupee, null) }
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = stock,
                    onValueChange = { stock = it.filter { c -> c.isDigit() } },
                    label = { Text("Stock (Optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && price.toDoubleOrNull() != null) {
                        vm.addProduct(
                            name,
                            price.toDouble(),
                            category,
                            stock.toIntOrNull() ?: 0
                        )
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank() && price.toDoubleOrNull() != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ProductPickerDialog(vm: ShopViewModel, onDismiss: () -> Unit) {
    val products by vm.products.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    
    val filtered = products.filter { it.name.contains(searchQuery, true) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Product") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(filtered) { p ->
                        ListItem(
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text(p.category) },
                            trailingContent = { Text("₹${p.price}", fontWeight = FontWeight.Bold) },
                            modifier = Modifier.clickable {
                                vm.inputPrice = p.price.toString()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// ==========================================
// 9. EXPENSES SCREEN
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(navController: NavController, vm: ShopViewModel) {
    val expenses by vm.expenses.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    val df = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    
    val totalExpenses = expenses.sumOf { it.amount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expenses") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, "Add Expense")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Total Card
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Total Expenses", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "₹${String.format("%.2f", totalExpenses)}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("${expenses.size} entries", style = MaterialTheme.typography.bodySmall)
                }
            }
            
            // Expenses List
            LazyColumn {
                items(expenses) { expense ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(expense.description, fontWeight = FontWeight.Bold)
                                Text(df.format(Date(expense.date)), style = MaterialTheme.typography.bodySmall)
                                Text(expense.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                "₹${String.format("%.2f", expense.amount)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            IconButton(onClick = { vm.deleteExpense(expense) }) {
                                Icon(Icons.Default.Delete, "", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                
                item {
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
    
    if (showAdd) {
        AddExpenseDialog(vm, onDismiss = { showAdd = false })
    }
}

@Composable
fun AddExpenseDialog(vm: ShopViewModel, onDismiss: () -> Unit) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Operating") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description *") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.CurrencyRupee, null) }
                )
                
                Text("Category", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Operating", "Rent", "Salary", "Utilities", "Other")) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (description.isNotBlank() && amount.toDoubleOrNull() != null) {
                        vm.addExpense(description, amount.toDouble(), category)
                        onDismiss()
                    }
                },
                enabled = description.isNotBlank() && amount.toDoubleOrNull() != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// 10. ANALYTICS SCREEN
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(navController: NavController, vm: ShopViewModel) {
    val bills by vm.bills.collectAsState()
    val expenses by vm.expenses.collectAsState()
    
    val todayRevenue = bills.filter {
        it.date >= vm.getStartOfDay()
    }.sumOf { it.finalTotal }
    
    val todayExpenses = expenses.filter {
        it.date >= vm.getStartOfDay()
    }.sumOf { it.amount }
    
    val profit = todayRevenue - todayExpenses
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(16.dp)) {
            item {
                Text("Today's Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
            }
            
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.TrendingUp, null, tint = Color.Green)
                            Spacer(Modifier.height(8.dp))
                            Text("Revenue", style = MaterialTheme.typography.labelMedium)
                            Text("₹${String.format("%.0f", todayRevenue)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Icon(Icons.Default.TrendingDown, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text("Expenses", style = MaterialTheme.typography.labelMedium)
                            Text("₹${String.format("%.0f", todayExpenses)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (profit >= 0) Color(0xFF4CAF50) else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Net Profit/Loss", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(
                            "₹${String.format("%.2f", profit)}",
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            
            item {
                Text("Payment Methods", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
            }
            
            item {
                val paymentMethods = bills.groupBy { it.paymentMethod }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    paymentMethods.forEach { (method, billsList) ->
                        Card {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(method, fontWeight = FontWeight.Medium)
                                Text("₹${String.format("%.0f", billsList.sumOf { it.finalTotal})}", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 11. SETTINGS SCREEN
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: ShopViewModel, isDark: Boolean, onToggle: () -> Unit) {
    var showClearDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                ListItem(
                    headlineContent = { Text("Dark Mode") },
                    leadingContent = { Icon(Icons.Default.DarkMode, null) },
                    trailingContent = { Switch(checked = isDark, onCheckedChange = { onToggle() }) }
                )
                Divider()
            }
            
            item {
                ListItem(
                    headlineContent = { Text("Clear All Data", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Delete all bills and history") },
                    leadingContent = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { showClearDialog = true }
                )
                Divider()
            }
            
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("ShopCalc Pro v3.0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Complete shop management & calculator", color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Text("Features:", fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "✓ Weight & Quantity Billing",
                            "✓ Product Management",
                            "✓ Sales Dashboard",
                            "✓ Expense Tracking",
                            "✓ Analytics & Reports",
                            "✓ Scientific Calculator",
                            "✓ Change Calculator",
                            "✓ Export CSV Data"
                        ).forEach {
                            Text("  $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear All Data?") },
            text = { Text("This will permanently delete all bills and transaction history. Products will not be affected.") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.nukeData()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
