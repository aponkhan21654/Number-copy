package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.database.AppDatabase
import com.example.data.repository.NumberRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingWidgetService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_FLOATING"
        const val ACTION_STOP = "com.example.action.STOP_FLOATING"
        const val NOTIFICATION_ID = 2026
        const val CHANNEL_ID = "numqueue_floating_channel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private var windowManager: WindowManager? = null
    private var floatingRootView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var repository: NumberRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var badgeTextView: TextView? = null
    private var hudCardView: LinearLayout? = null
    private var hudNextNumberText: TextView? = null
    private var miniToastText: TextView? = null
    private var hideToastRunnable: Runnable? = null

    private var remainingCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        val dao = AppDatabase.getInstance(this).numberDao()
        repository = NumberRepository(dao)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(remainingCount))

        initFloatingView()
        observeQueue()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_floating_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_floating_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(count: Int): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingWidgetService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (count > 0) {
            "বাকি আছে: $count টি নম্বর। ক্লিক করলেই কপি হবে।"
        } else {
            "সারি খালি। নম্বর যোগ করতে অ্যাপে যান।"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NumQueue ফ্লোটিং আইকন চালু আছে")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(appPendingIntent)
            .setOngoing(true)
            .addAction(0, "বন্ধ করুন", stopPendingIntent)
            .addAction(0, "অ্যাপ খুলুন", appPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(count: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun observeQueue() {
        serviceScope.launch {
            repository.queueCountFlow.collectLatest { count ->
                remainingCount = count
                badgeTextView?.text = if (count > 99) "99+" else count.toString()
                badgeTextView?.visibility = if (count > 0) View.VISIBLE else View.GONE
                updateNotification(count)
                updateHudPreview()
            }
        }
    }

    private fun updateHudPreview() {
        serviceScope.launch {
            val next = repository.getNextNumber()
            if (next != null) {
                hudNextNumberText?.text = "পরবর্তী: ${next.number}"
            } else {
                hudNextNumberText?.text = "কোনো নম্বর নেই (সারি খালি)"
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 350
        }

        val context = this
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        floatingRootView = root

        // Floating Bubble Container (Circle with shadow/elevation)
        val bubbleLayout = FrameLayout(context).apply {
            val size = dp(60)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#2563EB"), Color.parseColor("#1D4ED8"))
                setStroke(dp(2), Color.parseColor("#93C5FD"))
            }
            elevation = dp(8).toFloat()
        }

        // Inner Copy Icon
        val copyIcon = ImageView(context).apply {
            val iconSize = dp(30)
            val lp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            setImageResource(android.R.drawable.ic_menu_agenda)
            setColorFilter(Color.WHITE)
        }
        bubbleLayout.addView(copyIcon)

        // Remaining Badge
        badgeTextView = TextView(context).apply {
            val badgeSize = dp(24)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, badgeSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 0, 0, 0)
            }
            minWidth = badgeSize
            layoutParams = lp
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#EF4444")) // Vibrant Red badge
                setStroke(dp(1), Color.WHITE)
            }
            text = "0"
            visibility = View.GONE
        }
        bubbleLayout.addView(badgeTextView)

        // Mini Toast Overlay (shows copied number next to bubble)
        miniToastText = TextView(context).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setMargins(dp(68), 0, 0, 0)
            }
            layoutParams = lp
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            elevation = dp(10).toFloat()
            visibility = View.GONE
        }
        root.addView(miniToastText)

        // Mini HUD Expanded Card (toggled on long press)
        hudCardView = createHudCardView(context)
        root.addView(hudCardView)

        // Add bubble last so it sits above mini toast
        root.addView(bubbleLayout)

        // Touch listener for dragging, single clicking (copy), and long clicking (expand HUD)
        setupTouchListener(bubbleLayout)

        try {
            windowManager?.addView(root, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun createHudCardView(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lp = FrameLayout.LayoutParams(dp(220), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(66), 0, 0, 0)
            }
            layoutParams = lp
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1E293B")) // Slate 800
                setStroke(dp(1), Color.parseColor("#3B82F6"))
            }
            elevation = dp(12).toFloat()
            visibility = View.GONE

            // Header title
            val title = TextView(context).apply {
                text = "⚡ NumQueue কুইক মেনু"
                setTextColor(Color.parseColor("#93C5FD"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            addView(title)

            // Next Number Preview
            hudNextNumberText = TextView(context).apply {
                text = "পরবর্তী: লোড হচ্ছে..."
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(6), 0, dp(10))
            }
            addView(hudNextNumberText)

            // Button 1: Copy & Next
            val btnCopyNext = createMiniButton(context, "📋 কপি ও পরবর্তী", "#2563EB") {
                triggerCopyAndPop()
            }
            addView(btnCopyNext)

            // Button 2: Open App
            val btnOpenApp = createMiniButton(context, "📱 অ্যাপে যান", "#334155") {
                openMainActivity()
                hudCardView?.visibility = View.GONE
            }
            addView(btnOpenApp)

            // Button 3: Close Floating
            val btnClose = createMiniButton(context, "✕ আইকন বন্ধ করুন", "#991B1B") {
                stopSelf()
            }
            addView(btnClose)
        }
    }

    private fun createMiniButton(
        context: Context,
        label: String,
        bgColorHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                setMargins(0, dp(3), 0, dp(3))
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(bgColorHex))
            }
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false
        var downTime = 0L

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTime = System.currentTimeMillis()
                    isClick = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }

                    p.x = initialX + dx
                    p.y = initialY + dy

                    try {
                        windowManager?.updateViewLayout(floatingRootView, p)
                    } catch (_: Exception) {}
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime

                    if (isClick && duration < 350) {
                        // Short Click: Copy and Pop next number!
                        triggerCopyAndPop()
                    } else if (isClick && duration >= 350) {
                        // Long Click: Toggle HUD card
                        toggleHudCard()
                    } else {
                        // Drag released -> Snap smoothly to nearest edge (left or right)
                        snapToNearestEdge()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun triggerCopyAndPop() {
        serviceScope.launch {
            val result = repository.copyAndPopNext(applicationContext)
            showFloatingToast(result.message)
            updateHudPreview()
        }
    }

    private fun showFloatingToast(message: String) {
        mainHandler.post {
            miniToastText?.apply {
                text = message
                visibility = View.VISIBLE
                alpha = 1f
            }

            hideToastRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                miniToastText?.animate()
                    ?.alpha(0f)
                    ?.setDuration(300)
                    ?.withEndAction {
                        miniToastText?.visibility = View.GONE
                    }
                    ?.start()
            }
            hideToastRunnable = runnable
            mainHandler.postDelayed(runnable, 2200)
        }
    }

    private fun toggleHudCard() {
        hudCardView?.let { card ->
            if (card.visibility == View.VISIBLE) {
                card.visibility = View.GONE
            } else {
                updateHudPreview()
                card.visibility = View.VISIBLE
            }
        }
    }

    private fun snapToNearestEdge() {
        val p = params ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)
        val screenWidth = metrics.widthPixels

        p.x = if (p.x + dp(30) < screenWidth / 2) {
            20 // Left snap
        } else {
            screenWidth - dp(75) // Right snap
        }

        try {
            windowManager?.updateViewLayout(floatingRootView, p)
        } catch (_: Exception) {}
    }

    private fun openMainActivity() {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(appIntent)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        hideToastRunnable?.let { mainHandler.removeCallbacks(it) }

        floatingRootView?.let { root ->
            try {
                windowManager?.removeView(root)
            } catch (_: Exception) {}
        }
        floatingRootView = null
    }
}
